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
    private final ClovaEmbeddingBatchService clovaEmbeddingBatchService;

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
