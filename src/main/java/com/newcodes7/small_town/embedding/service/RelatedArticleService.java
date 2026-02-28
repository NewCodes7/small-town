package com.newcodes7.small_town.embedding.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.dto.RelatedArticleDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.ChunkVector;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ChunkContentRepository;
import com.newcodes7.small_town.embedding.repository.ChunkVectorRepository;
import com.newcodes7.small_town.global.config.BitVectorType;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.TermSource;
import com.newcodes7.small_town.search.scorer.HybridSearchScorer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관련 글 추천 서비스
 * 대표 Chunk의 embedding을 기반으로 유사한 글을 추천
 *
 * 검색 전략:
 * - Binary 임베딩이 있으면 2단계 검색 (Binary HNSW → halfvec Reranking)
 * - Binary 임베딩이 없으면 halfvec 직접 비교 (fallback)
 * - BM25 (title term 기반) + Vector NSF 하이브리드 리랭킹
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelatedArticleService {

    private final ArticleChunkRepository chunkRepository;
    private final ChunkVectorRepository chunkVectorRepository;
    private final ChunkContentRepository chunkContentRepository;
    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final HybridSearchScorer hybridSearchScorer;
    private final RepresentativeChunkService representativeChunkService;

    private static final int DEFAULT_LIMIT = 3;
    private static final double MINIMUM_SIMILARITY = 0.5;
    /** Stage 1 Binary HNSW 후보 수 (대표 청크 기준, 아티클 1개당 1청크) */
    private static final int CANDIDATE_LIMIT = 200;
    /** AVG 계산에 사용할 상위 청크 수 (청크 수가 적은 글도 커버) */
    private static final int TOP_K_CHUNKS = 3;
    /** BM25 후보 수 */
    private static final int BM25_LIMIT = 100;
    /** title term 최대 수 */
    private static final int TOP_TITLE_TERMS = 10;

    /**
     * 관련 글 조회
     *
     * @param articleId 현재 Article ID
     * @param limit 결과 수 (기본 3)
     * @return 관련 글 목록
     */
    @Transactional(readOnly = true)
    public List<RelatedArticleDto> getRelatedArticles(Long articleId, Integer limit) {
        int resultLimit = limit != null ? limit : DEFAULT_LIMIT;

        // 1. 현재 Article의 대표 chunk 조회
        ArticleChunk representativeChunk = getOrSelectRepresentativeChunk(articleId);

        if (representativeChunk == null) {
            log.debug("Article ID {} has no representative chunk", articleId);
            return List.of();
        }

        // 2. ChunkVector에서 embeddingNormalized 조회
        ChunkVector chunkVector = chunkVectorRepository.findById(representativeChunk.getId()).orElse(null);
        if (chunkVector == null || chunkVector.getEmbeddingNormalized() == null) {
            log.debug("Article ID {} has no representative chunk with embedding", articleId);
            return List.of();
        }
        String embeddingStr = convertToPostgresArray(chunkVector.getEmbeddingNormalized());

        // 3. Vector 검색: binary 임베딩 있으면 2단계 검색, 없으면 halfvec 직접 비교 (fallback)
        List<Object[]> vectorResults;
        if (representativeChunk.getEmbeddingBinary() != null) {
            String binaryStr = BitVectorType.toPostgresBitString(representativeChunk.getEmbeddingBinary(), 1024);
            vectorResults = chunkRepository.findRelatedArticlesByTwoStageSearch(
                    articleId, embeddingStr, binaryStr, CANDIDATE_LIMIT, TOP_K_CHUNKS, MINIMUM_SIMILARITY, resultLimit * 10);
        } else {
            log.debug("Article ID {} has no binary embedding, falling back to direct halfvec search", articleId);
            vectorResults = chunkRepository.findRelatedArticlesByRepresentativeChunk(
                    articleId, embeddingStr, TOP_K_CHUNKS, resultLimit * 10 + 5);
            vectorResults = vectorResults.stream()
                    .filter(row -> ((Number) row[1]).doubleValue() >= MINIMUM_SIMILARITY)
                    .toList();
        }

        // 4. Vector 점수 맵 구성
        Map<Long, Double> vectorScores = new HashMap<>();
        for (Object[] row : vectorResults) {
            vectorScores.put(((Number) row[0]).longValue(), ((Number) row[1]).doubleValue());
        }

        // 5. BM25 검색 (title term 기반)
        Map<Long, Double> bm25Scores = performBM25Search(articleId);

        // 6. NSF 리랭킹
        HybridSearchScorer.NSFResult nsfResult = hybridSearchScorer.calculateNSFScores(bm25Scores, vectorScores);
        Map<Long, Double> nsfScores = nsfResult.getNsfScores();

        if (nsfScores.isEmpty()) {
            log.debug("No related articles found for article ID {}", articleId);
            return List.of();
        }

        // 7. NSF 스코어 내림차순 정렬 → 상위 resultLimit개 ID 추출
        List<Long> relatedArticleIds = nsfScores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(resultLimit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // 8. Article 엔티티 조회 (Corporation fetch join)
        List<Article> articles = articleRepository.findByIdInWithCorporation(relatedArticleIds);

        // 9. 순서 유지하며 DTO 변환
        List<RelatedArticleDto> relatedArticles = new ArrayList<>();
        for (Long id : relatedArticleIds) {
            if (relatedArticles.size() >= resultLimit) break;
            Double nsfScore = nsfScores.get(id);
            articles.stream()
                    .filter(a -> a.getId().equals(id))
                    .findFirst()
                    .ifPresent(article -> {
                        String chunkContent = chunkContentRepository.findRepresentativeContentByArticleId(article.getId());
                        relatedArticles.add(new RelatedArticleDto(article, nsfScore, chunkContent));
                    });
        }

        log.debug("Found {} related articles for article ID {} (bm25: {}, vector: {}, nsf: {})",
                relatedArticles.size(), articleId, bm25Scores.size(), vectorScores.size(), nsfScores.size());
        return relatedArticles;
    }

    /**
     * 현재 article의 title term으로 BM25 검색 수행
     * title term이 없거나 BM25 쿼리 생성 실패 시 빈 맵 반환
     */
    private Map<Long, Double> performBM25Search(Long articleId) {
        try {
            // title/both term 조회 (score 내림차순, 상위 TOP_TITLE_TERMS개)
            List<ArticleTerm> allTerms = articleTermRepository.findByArticleIdOrderByScoreDesc(articleId);
            List<ArticleTerm> titleTerms = allTerms.stream()
                    .filter(at -> at.getSource() == TermSource.TITLE || at.getSource() == TermSource.BOTH)
                    .limit(TOP_TITLE_TERMS)
                    .collect(Collectors.toList());

            if (titleTerms.isEmpty()) {
                log.debug("Article ID {} has no title terms for BM25 search", articleId);
                return new HashMap<>();
            }

            // 모두 weight 1.0 (직접 매칭)
            Map<String, Double> termWeights = new LinkedHashMap<>();
            for (ArticleTerm at : titleTerms) {
                termWeights.put(at.getTerm().getTerm(), 1.0);
            }

            String bm25Query = hybridSearchScorer.buildBM25Query(termWeights);
            if (bm25Query == null) {
                return new HashMap<>();
            }

            List<Object[]> results = articleRepository.searchByBM25(bm25Query, BM25_LIMIT);

            Map<Long, Double> bm25Scores = new HashMap<>();
            for (Object[] row : results) {
                Long id = ((Number) row[0]).longValue();
                if (id.equals(articleId)) continue;  // 현재 article 제외
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (score != null) bm25Scores.put(id, score);
            }

            log.debug("BM25 search for article ID {}: {} terms, {} results", articleId, titleTerms.size(), bm25Scores.size());
            return bm25Scores;

        } catch (Exception e) {
            log.warn("BM25 search failed for article ID {}: {}", articleId, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 메인 Article의 대표 chunk 정보 조회 (Admin 디버그 표시용)
     */
    @Transactional(readOnly = true)
    public RepresentativeChunkInfo getRepresentativeChunkInfo(Long articleId) {
        ArticleChunk representativeChunk = getOrSelectRepresentativeChunk(articleId);
        if (representativeChunk == null) {
            return null;
        }

        String content = chunkContentRepository.findRepresentativeContentByArticleId(articleId);
        return new RepresentativeChunkInfo(representativeChunk.getChunkIndex(), content);
    }

    private ArticleChunk getOrSelectRepresentativeChunk(Long articleId) {
        ArticleChunk representativeChunk = chunkRepository.findRepresentativeByArticleId(articleId);

        if (representativeChunk == null) {
            log.debug("Article ID {} has no representative chunk, attempting to select one", articleId);
            Long chunkId = representativeChunkService.selectRepresentativeChunk(articleId);
            if (chunkId != null) {
                representativeChunk = chunkRepository.findById(chunkId).orElse(null);
            }
        }
        return representativeChunk;
    }

    /**
     * float 배열을 PostgreSQL halfvec 포맷으로 변환
     */
    private String convertToPostgresArray(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    public record RepresentativeChunkInfo(Integer chunkIndex, String content) {
    }
}
