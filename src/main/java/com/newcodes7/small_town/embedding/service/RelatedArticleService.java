package com.newcodes7.small_town.embedding.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.dto.RelatedArticleDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.ChunkVector;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ChunkContentRepository;
import com.newcodes7.small_town.embedding.repository.ChunkVectorRepository;
import com.newcodes7.small_town.global.config.BitVectorType;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관련 글 추천 서비스
 * 대표 Chunk의 embedding을 기반으로 유사한 글을 추천
 *
 * 검색 전략:
 * - Binary 임베딩이 있으면 2단계 검색 (Binary HNSW → halfvec Reranking)
 * - Binary 임베딩이 없으면 halfvec 직접 비교 (fallback)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelatedArticleService {

    private final ArticleChunkRepository chunkRepository;
    private final ChunkVectorRepository chunkVectorRepository;
    private final ChunkContentRepository chunkContentRepository;
    private final ArticleRepository articleRepository;
    private final RepresentativeChunkService representativeChunkService;

    private static final int DEFAULT_LIMIT = 3;
    private static final double MINIMUM_SIMILARITY = 0.5;
    /** Stage 1 Binary HNSW 후보 수 (대표 청크 기준, 아티클 1개당 1청크) */
    private static final int CANDIDATE_LIMIT = 100;

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

        // 3. 유사도 검색: binary 임베딩 있으면 2단계 검색, 없으면 halfvec 직접 비교 (fallback)
        List<Object[]> results;
        if (representativeChunk.getEmbeddingBinary() != null) {
            String binaryStr = BitVectorType.toPostgresBitString(representativeChunk.getEmbeddingBinary(), 1024);
            results = chunkRepository.findRelatedArticlesByTwoStageSearch(
                    articleId, embeddingStr, binaryStr, CANDIDATE_LIMIT, MINIMUM_SIMILARITY, resultLimit);
        } else {
            log.debug("Article ID {} has no binary embedding, falling back to direct halfvec search", articleId);
            results = chunkRepository.findRelatedArticlesByRepresentativeChunk(
                    articleId, embeddingStr, resultLimit + 5);
            results = results.stream()
                    .filter(row -> ((Number) row[1]).doubleValue() >= MINIMUM_SIMILARITY)
                    .toList();
        }

        if (results.isEmpty()) {
            log.debug("No related articles found for article ID {}", articleId);
            return List.of();
        }

        // 4. Article ID 목록 및 유사도 추출
        List<Long> relatedArticleIds = new ArrayList<>();
        List<Double> similarities = new ArrayList<>();
        for (Object[] row : results) {
            relatedArticleIds.add(((Number) row[0]).longValue());
            similarities.add(((Number) row[1]).doubleValue());
        }

        if (relatedArticleIds.isEmpty()) {
            log.debug("No related articles above similarity threshold for article ID {}", articleId);
            return List.of();
        }

        // 5. Article 엔티티 조회 (Corporation fetch join)
        List<Article> articles = articleRepository.findByIdInWithCorporation(relatedArticleIds);

        // 6. 순서 유지하며 DTO 변환
        List<RelatedArticleDto> relatedArticles = new ArrayList<>();
        for (int i = 0; i < relatedArticleIds.size() && relatedArticles.size() < resultLimit; i++) {
            Long id = relatedArticleIds.get(i);
            Double similarity = similarities.get(i);

            articles.stream()
                    .filter(a -> a.getId().equals(id))
                    .findFirst()
                    .ifPresent(article -> {
                        String chunkContent = chunkContentRepository.findRepresentativeContentByArticleId(article.getId());
                        relatedArticles.add(new RelatedArticleDto(article, similarity, chunkContent));
                    });
        }

        log.debug("Found {} related articles for article ID {}", relatedArticles.size(), articleId);
        return relatedArticles;
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
