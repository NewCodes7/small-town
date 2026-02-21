package com.newcodes7.small_town.embedding.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.dto.RelatedArticleDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.entity.ClovaArticleChunk;
import com.newcodes7.small_town.embedding.entity.ClovaChunkVector;
import com.newcodes7.small_town.embedding.repository.ClovaArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ClovaChunkVectorRepository;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 관련 글 추천 서비스
 * 대표 Chunk의 embedding을 기반으로 유사한 글을 추천
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RelatedArticleService {

    private final ClovaArticleChunkRepository chunkRepository;
    private final ClovaChunkVectorRepository chunkVectorRepository;
    private final ArticleRepository articleRepository;
    private final RepresentativeChunkService representativeChunkService;

    private static final int DEFAULT_LIMIT = 3;
    private static final double MINIMUM_SIMILARITY = 0.3;

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
        ClovaArticleChunk representativeChunk = chunkRepository.findRepresentativeByArticleId(articleId);

        if (representativeChunk == null) {
            log.debug("Article ID {} has no representative chunk, attempting to select one", articleId);
            // 대표 chunk가 없으면 선정 시도
            Long chunkId = representativeChunkService.selectRepresentativeChunk(articleId);
            if (chunkId != null) {
                representativeChunk = chunkRepository.findById(chunkId).orElse(null);
            }
        }

        if (representativeChunk == null) {
            log.debug("Article ID {} has no representative chunk", articleId);
            return List.of();
        }

        // 2. ClovaChunkVector에서 embeddingNormalized 조회
        ClovaChunkVector chunkVector = chunkVectorRepository.findById(representativeChunk.getId()).orElse(null);
        if (chunkVector == null || chunkVector.getEmbeddingNormalized() == null) {
            log.debug("Article ID {} has no representative chunk with embedding", articleId);
            return List.of();
        }
        String embeddingStr = convertToPostgresArray(chunkVector.getEmbeddingNormalized());

        // 3. 대표 chunk 기반 유사도 검색
        List<Object[]> results = chunkRepository.findRelatedArticlesByRepresentativeChunk(
                articleId, embeddingStr, resultLimit + 5);  // 여유있게 조회

        if (results.isEmpty()) {
            log.debug("No related articles found for article ID {}", articleId);
            return List.of();
        }

        // 4. Article ID 목록 추출 및 유사도 맵 생성
        List<Long> relatedArticleIds = new ArrayList<>();
        List<Double> similarities = new ArrayList<>();

        for (Object[] row : results) {
            Long relatedId = ((Number) row[0]).longValue();
            Double similarity = ((Number) row[1]).doubleValue();

            if (similarity >= MINIMUM_SIMILARITY) {
                relatedArticleIds.add(relatedId);
                similarities.add(similarity);
            }
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
                    .ifPresent(article -> relatedArticles.add(new RelatedArticleDto(article, similarity)));
        }

        log.debug("Found {} related articles for article ID {}", relatedArticles.size(), articleId);
        return relatedArticles;
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
}
