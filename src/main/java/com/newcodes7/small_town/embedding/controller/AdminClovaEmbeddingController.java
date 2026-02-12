package com.newcodes7.small_town.embedding.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.repository.ClovaArticleChunkRepository;
import com.newcodes7.small_town.embedding.service.ClovaEmbeddingBatchService;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Naver Clova Embedding 관리 Admin API
 *
 * - 배치 임베딩 생성
 * - 단일 Article 임베딩 생성
 * - 임베딩 통계 조회
 */
@Controller
@RequestMapping("/admin/clova")
@RequiredArgsConstructor
@Slf4j
public class AdminClovaEmbeddingController {

    private final ArticleRepository articleRepository;
    private final ClovaArticleChunkRepository clovaChunkRepository;
    private final ClovaEmbeddingBatchService clovaEmbeddingBatchService;

    /**
     * 모든 Article에 대해 Clova 임베딩 연속 생성 (10개씩 트랜잭션 분리)
     *
     * content가 있고 Clova 임베딩이 없는 모든 Article을 처리
     * 10개씩 배치로 나눠서 각각 별도 트랜잭션으로 처리 (중간 실패 시에도 이전 배치는 저장됨)
     *
     * Example: POST /admin/clova/articles/generate-all-embeddings
     */
    @PostMapping("/articles/generate-all-embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateAllClovaEmbeddings() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 전체 개수 조회
            long totalCount = clovaEmbeddingBatchService.countArticlesWithoutClovaEmbedding();

            if (totalCount == 0) {
                response.put("success", true);
                response.put("message", "처리할 Article이 없습니다. 모든 Article에 Clova 임베딩이 생성되었거나 content가 없습니다.");
                response.put("totalArticles", 0);
                return ResponseEntity.ok(response);
            }

            log.info("전체 Clova 임베딩 생성 시작 - 총 {}개 Article", totalCount);

            int batchSize = 10;
            int offset = 0;
            int totalSuccess = 0;
            int totalFailure = 0;
            int totalChunks = 0;
            int batchNumber = 0;

            // 10개씩 배치 처리
            while (true) {
                // 다음 배치 조회 (매번 새로 조회 - 이전 배치에서 처리된 것은 제외됨)
                List<Long> articleIds = clovaEmbeddingBatchService.findArticleIdsWithoutClovaEmbedding(0, batchSize);

                if (articleIds.isEmpty()) {
                    log.info("더 이상 처리할 Article이 없습니다");
                    break;
                }

                batchNumber++;
                log.info("배치 {} 처리 시작 - {}개 Article (ID: {}~{})",
                        batchNumber, articleIds.size(),
                        articleIds.get(articleIds.size() - 1),
                        articleIds.get(0));

                // 10개씩 별도 트랜잭션으로 처리
                Map<String, Object> batchResult = clovaEmbeddingBatchService.processEmbeddingBatch(articleIds);

                int successCount = (int) batchResult.get("successCount");
                int failureCount = (int) batchResult.get("failureCount");
                int chunksGenerated = (int) batchResult.get("totalChunks");

                totalSuccess += successCount;
                totalFailure += failureCount;
                totalChunks += chunksGenerated;

                log.info("배치 {} 완료 - 성공: {}, 실패: {}, 청크: {} (누적: 성공 {}, 청크 {})",
                        batchNumber, successCount, failureCount, chunksGenerated,
                        totalSuccess, totalChunks);

                // 진행 상황 체크 (무한 루프 방지)
                if (successCount == 0 && failureCount == 0) {
                    log.warn("배치 처리 결과가 없습니다. 루프 종료");
                    break;
                }
            }

            response.put("success", true);
            response.put("totalBatches", batchNumber);
            response.put("totalArticlesProcessed", totalSuccess + totalFailure);
            response.put("successArticles", totalSuccess);
            response.put("failureArticles", totalFailure);
            response.put("totalChunksGenerated", totalChunks);
            response.put("message", String.format(
                    "전체 Clova 임베딩 생성 완료: %d개 배치, 성공 %d/%d Articles, %d 청크",
                    batchNumber, totalSuccess, totalSuccess + totalFailure, totalChunks
            ));

            log.info("전체 Clova 임베딩 생성 완료 - 배치: {}, 성공: {}/{}, 청크: {}",
                    batchNumber, totalSuccess, totalSuccess + totalFailure, totalChunks);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("전체 Clova 임베딩 생성 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "전체 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Clova 청크 임베딩 배치 생성
     *
     * content가 있고 Clova 임베딩이 없는 Article을 ID 내림차순으로 처리
     *
     * Query Parameters:
     * - count: 처리할 Article 수 (기본값: 10, 최대: 100)
     *
     * Example: POST /admin/clova/articles/generate-embeddings-batch?count=20
     */
    @GetMapping("/articles/generate-embeddings-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateClovaEmbeddingsBatch(
            @RequestParam(defaultValue = "10") int count) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 최대 100개로 제한
            int limitedCount = Math.min(count, 100);

            log.info("Clova 배치 임베딩 생성 요청 - count: {}", limitedCount);

            // 1. 임베딩이 없는 Article 조회 (ID 내림차순, content 있음)
            List<Article> articles = clovaEmbeddingBatchService.findArticlesWithoutClovaEmbedding(limitedCount);

            if (articles.isEmpty()) {
                response.put("success", true);
                response.put("message", "처리할 Article이 없습니다. 모든 Article에 Clova 임베딩이 생성되었거나 content가 없습니다.");
                response.put("totalArticles", 0);
                return ResponseEntity.ok(response);
            }

            log.info("{}개 Article Clova 임베딩 생성 시작", articles.size());

            // 2. 배치 임베딩 생성
            Map<String, Object> batchResult = clovaEmbeddingBatchService.generateClovaChunkEmbeddingsBatch(articles);

            response.put("success", true);
            response.putAll(batchResult);
            response.put("message", String.format(
                    "Clova 임베딩 생성 완료: %d/%d Articles, %d 청크",
                    batchResult.get("successArticles"),
                    batchResult.get("totalArticles"),
                    batchResult.get("totalChunksGenerated")
            ));

            log.info("Clova 배치 임베딩 생성 완료 - 성공: {}/{}",
                    batchResult.get("successArticles"),
                    batchResult.get("totalArticles"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Clova 배치 임베딩 생성 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "배치 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 단일 Article의 Clova 청크 임베딩 생성
     *
     * Example: POST /admin/clova/articles/123/generate-embeddings
     */
    @PostMapping("/articles/{id}/generate-embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateClovaEmbeddingsForArticle(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article {} Clova 임베딩 생성 요청", id);

            Optional<Article> articleOpt = articleRepository.findById(id);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            Article article = articleOpt.get();

            if (article.getContent() == null || article.getContent().trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Article의 본문이 비어있어 임베딩을 생성할 수 없습니다");
                return ResponseEntity.badRequest().body(response);
            }

            // 임베딩 생성
            int chunksGenerated = clovaEmbeddingBatchService.generateClovaChunkEmbeddingsForArticle(article);

            if (chunksGenerated > 0) {
                response.put("success", true);
                response.put("articleId", id);
                response.put("title", article.getTitle());
                response.put("chunksGenerated", chunksGenerated);
                response.put("message", chunksGenerated + "개 청크 Clova 임베딩 생성 완료");

                log.info("Article {} - {}개 청크 Clova 임베딩 생성 완료", id, chunksGenerated);
            } else {
                response.put("success", false);
                response.put("message", "Clova 임베딩 생성에 실패했습니다. Segmentation 또는 Embedding API 오류");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} Clova 임베딩 생성 실패", id, e);
            response.put("success", false);
            response.put("message", "임베딩 생성 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Clova 임베딩 통계 조회
     *
     * Example: GET /admin/clova/embedding-stats
     */
    @GetMapping("/embedding-stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getClovaEmbeddingStats() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Object> stats = clovaEmbeddingBatchService.getClovaEmbeddingStats();

            response.put("success", true);
            response.putAll(stats);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Clova 임베딩 통계 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "통계 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ==================== 임베딩 재분석 API ====================

    /**
     * 임베딩 재분석 (전체)
     * 기존 임베딩을 삭제하고 재생성 (1초 rate limit)
     * 대상 전체를 10개씩 배치 분할하여 처리
     *
     * Query Parameters:
     * - articleId: 이 ID 미만인 Article만 재분석 (미지정 시 가장 큰 ID부터)
     * - corporationId: 특정 회사의 Article만 재분석
     *
     * Example: GET /admin/clova/articles/regenerate-embeddings
     * Example: GET /admin/clova/articles/regenerate-embeddings?articleId=500
     * Example: GET /admin/clova/articles/regenerate-embeddings?corporationId=3
     */
    @GetMapping("/articles/regenerate-embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> regenerateEmbeddings(
            @RequestParam(required = false) Long articleId,
            @RequestParam(required = false) Long corporationId) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("임베딩 재분석 요청 - articleId: {}, corporationId: {}",
                    articleId, corporationId);

            // 대상 Article ID 목록 조회 (전체)
            List<Long> targetIds;

            if (corporationId != null) {
                targetIds = clovaChunkRepository.findArticleIdsWithEmbeddingByCorporationIdOrderByIdDesc(
                        corporationId);
            } else if (articleId != null) {
                targetIds = clovaChunkRepository.findArticleIdsWithEmbeddingLessThanOrderByIdDesc(
                        articleId);
            } else {
                targetIds = clovaChunkRepository.findArticleIdsWithEmbeddingOrderByIdDesc();
            }

            if (targetIds.isEmpty()) {
                response.put("success", true);
                response.put("message", "재분석할 Article이 없습니다.");
                response.put("totalArticles", 0);
                return ResponseEntity.ok(response);
            }

            log.info("재분석 대상: {}개 Article (ID: {}~{})",
                    targetIds.size(), targetIds.get(0), targetIds.get(targetIds.size() - 1));

            // 10개씩 배치 분할하여 재분석 (프록시를 통한 호출로 @Transactional 적용)
            int batchSize = clovaEmbeddingBatchService.getBatchSize();
            int totalSuccess = 0;
            int totalFailure = 0;
            int totalChunks = 0;
            int batchNumber = 0;

            for (int i = 0; i < targetIds.size(); i += batchSize) {
                List<Long> batchIds = targetIds.subList(i, Math.min(i + batchSize, targetIds.size()));
                batchNumber++;

                log.info("재분석 배치 {} 처리 시작 - {}개 Article", batchNumber, batchIds.size());

                try {
                    Map<String, Object> batchResult = clovaEmbeddingBatchService.processRegenerateBatch(batchIds);

                    int successCount = (int) batchResult.get("successCount");
                    int failureCount = (int) batchResult.get("failureCount");
                    int chunksGenerated = (int) batchResult.get("totalChunks");

                    totalSuccess += successCount;
                    totalFailure += failureCount;
                    totalChunks += chunksGenerated;

                    log.info("재분석 배치 {} 완료 - 성공: {}, 실패: {}, 청크: {} (누적: 성공 {}, 청크 {})",
                            batchNumber, successCount, failureCount, chunksGenerated,
                            totalSuccess, totalChunks);
                } catch (Exception ex) {
                    log.error("재분석 배치 {} 처리 실패: {}", batchNumber, ex.getMessage());
                    totalFailure += batchIds.size();
                }
            }

            log.info("임베딩 재분석 완료 - 배치: {}, 성공: {}/{}, 청크: {}",
                    batchNumber, totalSuccess, totalSuccess + totalFailure, totalChunks);

            response.put("success", true);
            response.put("totalArticles", targetIds.size());
            response.put("totalBatches", batchNumber);
            response.put("successArticles", totalSuccess);
            response.put("failureArticles", totalFailure);
            response.put("totalChunksGenerated", totalChunks);
            response.put("message", String.format(
                    "임베딩 재분석 완료: %d/%d Articles, %d 청크",
                    totalSuccess, targetIds.size(), totalChunks
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("임베딩 재분석 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "재분석 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 단건 임베딩 재분석
     * 특정 Article의 기존 임베딩을 삭제하고 재생성
     *
     * Example: GET /admin/clova/articles/123/regenerate-embeddings
     */
    @GetMapping("/articles/{id}/regenerate-embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> regenerateEmbeddingsForArticle(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article {} 임베딩 재분석 요청", id);

            Optional<Article> articleOpt = articleRepository.findById(id);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            Article article = articleOpt.get();

            if (article.getContent() == null || article.getContent().trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Article의 본문이 비어있어 재분석할 수 없습니다");
                return ResponseEntity.badRequest().body(response);
            }

            // 단건 재분석 (기존 chunk 삭제 후 재생성)
            Map<String, Object> result = clovaEmbeddingBatchService.processRegenerateBatch(List.of(id));

            int successCount = (int) result.get("successCount");
            int totalChunks = (int) result.get("totalChunks");

            if (successCount > 0) {
                response.put("success", true);
                response.put("articleId", id);
                response.put("title", article.getTitle());
                response.put("chunksGenerated", totalChunks);
                response.put("message", totalChunks + "개 청크 임베딩 재분석 완료");
            } else {
                response.put("success", false);
                response.put("message", "임베딩 재분석에 실패했습니다");
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} 임베딩 재분석 실패", id, e);
            response.put("success", false);
            response.put("message", "재분석 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Article의 Clova 임베딩 미리보기 (테스트용)
     * 실제로 저장하지 않고 Segmentation 결과만 반환
     *
     * Example: GET /admin/clova/articles/123/preview-segmentation
     */
    @GetMapping("/articles/{id}/preview-segmentation")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> previewSegmentation(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(id);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            Article article = articleOpt.get();

            if (article.getContent() == null || article.getContent().trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Article의 본문이 비어있습니다");
                return ResponseEntity.badRequest().body(response);
            }

            // 제목 + 본문 결합
            StringBuilder fullText = new StringBuilder();
            String title = article.getTranslatedTitle() != null
                    ? article.getTranslatedTitle()
                    : article.getTitle();
            fullText.append(title).append(". ");
            fullText.append(title).append(". ");
            fullText.append(title).append(". \n\n");
            fullText.append(article.getContent());

            // Segmentation만 실행 (임베딩 없이)
            // 참고: NaverClovaEmbeddingService.segmentDocument() 직접 호출 필요
            // 여기서는 서비스 의존성을 추가해야 함

            response.put("success", true);
            response.put("articleId", id);
            response.put("title", article.getTitle());
            response.put("contentLength", article.getContent().length());
            response.put("message", "Segmentation 미리보기는 별도 구현이 필요합니다");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} Segmentation 미리보기 실패", id, e);
            response.put("success", false);
            response.put("message", "미리보기 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
