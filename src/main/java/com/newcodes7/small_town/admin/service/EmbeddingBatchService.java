package com.newcodes7.small_town.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.service.ArticleEmbeddingService;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article 임베딩 배치 생성 서비스
 * OpenAI API rate limit을 고려하여 500ms 딜레이 적용
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingBatchService {

    private final ArticleEmbeddingService embeddingService;

    // OpenAI API rate limit 대응을 위한 딜레이 (500ms)
    private static final long RATE_LIMIT_DELAY_MS = 500;

    /**
     * 여러 Article의 청크 임베딩을 배치로 생성
     *
     * @param articles 임베딩 생성할 Article 리스트
     * @return 생성 결과 통계
     */
    @Transactional
    public Map<String, Object> generateChunkEmbeddingsBatch(List<Article> articles) {
        int successArticleCount = 0;
        int failureArticleCount = 0;
        int totalChunksGenerated = 0;
        List<String> errors = new ArrayList<>();

        log.info("배치 청크 임베딩 생성 시작 - 총 {}개 Article", articles.size());

        for (Article article : articles) {
            try {
                // Article의 청크 임베딩 생성
                int chunksGenerated = embeddingService.generateChunkEmbeddingsForArticle(article);

                if (chunksGenerated > 0) {
                    successArticleCount++;
                    totalChunksGenerated += chunksGenerated;

                    // Rate limiting: 청크마다 500ms 딜레이는 generateChunkEmbeddingsForArticle 내부에서 처리됨
                    // Article 간 추가 딜레이
                    Thread.sleep(RATE_LIMIT_DELAY_MS);
                } else {
                    failureArticleCount++;
                    String errorMsg = "Article " + article.getId() + ": 청크 임베딩 생성 실패 (0개 생성)";
                    errors.add(errorMsg);
                    log.warn(errorMsg);
                }

                // 진행 상황 로깅 (10개마다)
                int processed = successArticleCount + failureArticleCount;
                if (processed % 10 == 0) {
                    log.info("임베딩 생성 진행: {}/{} Articles (성공: {}, 총 청크: {})",
                            processed, articles.size(), successArticleCount, totalChunksGenerated);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failureArticleCount++;
                String errorMsg = "Article " + article.getId() + ": 중단됨 - " + e.getMessage();
                errors.add(errorMsg);
                log.error(errorMsg, e);
                break; // 중단 시 루프 종료

            } catch (Exception e) {
                failureArticleCount++;
                String errorMsg = "Article " + article.getId() + ": " + e.getMessage();
                errors.add(errorMsg);
                log.error("청크 임베딩 생성 실패 - Article ID: {}", article.getId(), e);
                // 계속 진행 (다음 Article 처리)
            }
        }

        log.info("배치 청크 임베딩 생성 완료 - Articles: {}, 성공: {}, 총 청크: {}",
                articles.size(), successArticleCount, totalChunksGenerated);

        return Map.of(
                "totalArticles", articles.size(),
                "successArticles", successArticleCount,
                "failureArticles", failureArticleCount,
                "totalChunksGenerated", totalChunksGenerated,
                "errors", errors
        );
    }

    /**
     * Article 단위 임베딩 배치 생성 (레거시)
     * 청크 기반 방식을 권장하지만, 호환성을 위해 유지
     */
    @Transactional
    public Map<String, Object> generateArticleEmbeddingsBatch(List<Article> articles) {
        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();

        log.info("배치 Article 임베딩 생성 시작 (레거시) - 총 {}개 Article", articles.size());

        for (Article article : articles) {
            try {
                float[] embedding = embeddingService.generateArticleEmbedding(article);

                if (embedding != null) {
                    embeddingService.saveEmbeddingToArticle(article.getId(), embedding);
                    successCount++;
                    Thread.sleep(RATE_LIMIT_DELAY_MS);
                } else {
                    failureCount++;
                    errors.add("Article " + article.getId() + ": 임베딩 생성 결과가 null");
                }

            } catch (Exception e) {
                failureCount++;
                errors.add("Article " + article.getId() + ": " + e.getMessage());
                log.error("Article 임베딩 생성 실패 - ID: {}", article.getId(), e);
            }
        }

        return Map.of(
                "total", articles.size(),
                "success", successCount,
                "failure", failureCount,
                "errors", errors
        );
    }

    /**
     * 단일 Article의 청크 임베딩 생성
     *
     * @param article 임베딩 생성할 Article
     * @return 생성된 청크 수
     */
    @Transactional
    public int generateChunkEmbeddingsForSingleArticle(Article article) {
        try {
            log.info("단일 Article 청크 임베딩 생성 시작 - Article ID: {}", article.getId());

            int chunksGenerated = embeddingService.generateChunkEmbeddingsForArticle(article);

            log.info("단일 Article 청크 임베딩 생성 완료 - Article ID: {}, 청크 수: {}",
                    article.getId(), chunksGenerated);

            return chunksGenerated;

        } catch (Exception e) {
            log.error("단일 Article 청크 임베딩 생성 실패 - Article ID: {}", article.getId(), e);
            return 0;
        }
    }
}
