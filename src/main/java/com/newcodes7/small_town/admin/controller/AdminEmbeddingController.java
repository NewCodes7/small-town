package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.admin.service.EmbeddingBatchService;
import com.newcodes7.small_town.article.repository.ArticleChunkRepository;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article Embedding 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminEmbeddingController {

    private final ArticleRepository articleRepository;
    private final ArticleChunkRepository articleChunkRepository;
    private final EmbeddingBatchService embeddingBatchService;

    /**
     * 단일 Article의 청크 임베딩 생성
     */
    @GetMapping("/articles/{id}/generate-chunk-embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateChunkEmbeddings(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article {} 청크 임베딩 생성 요청", id);

            Optional<Article> articleOpt = articleRepository.findById(id);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            Article article = articleOpt.get();

            // 임베딩 생성
            int chunksGenerated = embeddingBatchService.generateChunkEmbeddingsForSingleArticle(article);

            response.put("success", true);
            response.put("articleId", id);
            response.put("chunksGenerated", chunksGenerated);
            response.put("message", chunksGenerated + "개 청크 임베딩 생성 완료");

            log.info("Article {} - {}개 청크 임베딩 생성 완료", id, chunksGenerated);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} 청크 임베딩 생성 실패", id, e);
            response.put("success", false);
            response.put("message", "임베딩 생성 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 여러 Article의 청크 임베딩 배치 생성
     *
     * RequestBody:
     * {
     *   "corporationId": 123 (optional),
     *   "withoutEmbedding": true (optional, default: true),
     *   "limit": 50 (optional, default: 50)
     * }
     */
    @GetMapping("/articles/generate-chunk-embeddings-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateChunkEmbeddingsBatch(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            Long corporationId = request.containsKey("corporationId")
                ? ((Number) request.get("corporationId")).longValue()
                : null;
            Boolean withoutEmbedding = request.containsKey("withoutEmbedding")
                ? (Boolean) request.get("withoutEmbedding")
                : true;
            Integer limit = request.containsKey("limit")
                ? ((Number) request.get("limit")).intValue()
                : 50;

            log.info("배치 청크 임베딩 생성 요청 - corporationId: {}, withoutEmbedding: {}, limit: {}",
                    corporationId, withoutEmbedding, limit);

            // 1. Article 조회
            List<Article> articles;
            if (withoutEmbedding) {
                // 임베딩이 없는 Article 조회
                if (corporationId != null) {
                    articles = articleRepository.findArticlesWithoutEmbeddingByCorporationId(
                            corporationId, PageRequest.of(0, limit));
                } else {
                    articles = articleRepository.findArticlesWithoutEmbedding(PageRequest.of(0, limit));
                }
            } else {
                // 모든 Article 조회
                if (corporationId != null) {
                    articles = articleRepository.findByCorporationIdAndDeletedAtIsNullWithPaging(
                            corporationId, PageRequest.of(0, limit)).getContent();
                } else {
                    articles = articleRepository.findByDeletedAtIsNull(PageRequest.of(0, limit)).getContent();
                }
            }

            if (articles.isEmpty()) {
                response.put("success", true);
                response.put("message", "처리할 Article이 없습니다");
                response.put("totalArticles", 0);
                return ResponseEntity.ok(response);
            }

            log.info("{}개 Article 청크 임베딩 생성 시작", articles.size());

            // 2. 배치 임베딩 생성
            Map<String, Object> batchResult = embeddingBatchService.generateChunkEmbeddingsBatch(articles);

            response.put("success", true);
            response.putAll(batchResult);

            log.info("배치 청크 임베딩 생성 완료 - 성공: {}/{}, 총 청크: {}",
                    batchResult.get("successArticles"),
                    batchResult.get("totalArticles"),
                    batchResult.get("totalChunksGenerated"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("배치 청크 임베딩 생성 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "배치 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 임베딩 통계 조회
     */
    @GetMapping("/articles/embedding-stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getEmbeddingStats() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Article 통계
            long totalArticles = articleRepository.countByDeletedAtIsNull();
            long articlesWithContent = articleRepository.countArticlesWithContent();
            long articlesWithoutContent = totalArticles - articlesWithContent;

            // Chunk 통계
            long totalChunks = articleChunkRepository.countAllChunks();
            long chunksWithEmbedding = articleChunkRepository.countChunksWithEmbedding();
            long chunksWithoutEmbedding = totalChunks - chunksWithEmbedding;

            // 커버리지 계산
            double articleContentCoverage = totalArticles > 0
                    ? (double) articlesWithContent / totalArticles * 100
                    : 0.0;
            double chunkEmbeddingCoverage = totalChunks > 0
                    ? (double) chunksWithEmbedding / totalChunks * 100
                    : 0.0;

            response.put("success", true);
            response.put("totalArticles", totalArticles);
            response.put("articlesWithContent", articlesWithContent);
            response.put("articlesWithoutContent", articlesWithoutContent);
            response.put("articleContentCoverage", String.format("%.2f%%", articleContentCoverage));
            response.put("totalChunks", totalChunks);
            response.put("chunksWithEmbedding", chunksWithEmbedding);
            response.put("chunksWithoutEmbedding", chunksWithoutEmbedding);
            response.put("chunkEmbeddingCoverage", String.format("%.2f%%", chunkEmbeddingCoverage));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("임베딩 통계 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "통계 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
