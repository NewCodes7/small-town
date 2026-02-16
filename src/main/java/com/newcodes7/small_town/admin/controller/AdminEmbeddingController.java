package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.admin.service.EmbeddingBatchService;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleEmbeddingService;
import com.newcodes7.small_town.article.service.TermEmbeddingService;
import com.newcodes7.small_town.crawler.dto.ArticleSummaryResponse;
import com.newcodes7.small_town.crawler.integration.openai.OpenaiService;
import com.newcodes7.small_town.embedding.repository.ClovaArticleChunkRepository;
import com.newcodes7.small_town.embedding.service.RepresentativeChunkService;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article & Term Embedding 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminEmbeddingController {

    private final ArticleRepository articleRepository;
    private final ClovaArticleChunkRepository clovaArticleChunkRepository;
    private final EmbeddingBatchService embeddingBatchService;
    private final TermEmbeddingService termEmbeddingService;
    private final OpenaiService openaiService;
    private final ArticleEmbeddingService articleEmbeddingService;
    private final RepresentativeChunkService representativeChunkService;

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
    @org.springframework.web.bind.annotation.PostMapping("/articles/generate-chunk-embeddings-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateChunkEmbeddingsBatch(@RequestBody(required = false) Map<String, Object> request) {
        if (request == null) {
            request = new HashMap<>();
        }
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
            long totalChunks = clovaArticleChunkRepository.countAllChunks();
            long chunksWithEmbedding = clovaArticleChunkRepository.countChunksWithEmbedding();
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

    // ===== Term 임베딩 관련 API =====

    /**
     * Term 임베딩 배치 생성
     *
     * RequestBody:
     * {
     *   "batchSize": 100 (optional, default: 100)
     * }
     */
    @org.springframework.web.bind.annotation.PostMapping("/terms/generate-embeddings-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateTermEmbeddingsBatch(@RequestBody(required = false) Map<String, Object> request) {
        if (request == null) {
            request = new HashMap<>();
        }
        Map<String, Object> response = new HashMap<>();

        try {
            Integer batchSize = request.containsKey("batchSize")
                ? ((Number) request.get("batchSize")).intValue()
                : 100;

            log.info("Term 임베딩 배치 생성 요청 - batchSize: {}", batchSize);

            // Term 임베딩 생성
            TermEmbeddingService.TermEmbeddingResult result =
                termEmbeddingService.generateEmbeddingsForTerms(batchSize);

            response.put("success", true);
            response.put("successCount", result.getSuccessCount());
            response.put("failedCount", result.getFailedCount());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("message", String.format("%d개 Term 임베딩 생성 완료 (실패: %d)",
                result.getSuccessCount(), result.getFailedCount()));

            log.info("Term 임베딩 배치 생성 완료 - 성공: {}, 실패: {}, 처리 시간: {}ms",
                    result.getSuccessCount(), result.getFailedCount(), result.getProcessingTimeMs());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Term 임베딩 배치 생성 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "배치 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 단일 Term 임베딩 생성
     */
    @GetMapping("/terms/{id}/generate-embedding")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateTermEmbedding(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Term {} 임베딩 생성 요청", id);

            boolean success = termEmbeddingService.generateEmbeddingForTerm(id);

            if (success) {
                response.put("success", true);
                response.put("termId", id);
                response.put("message", "Term 임베딩 생성 완료");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "Term 임베딩 생성 실패");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Term {} 임베딩 생성 실패", id, e);
            response.put("success", false);
            response.put("message", "임베딩 생성 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Term 임베딩 통계 조회
     */
    @GetMapping("/terms/embedding-stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getTermEmbeddingStats() {
        Map<String, Object> response = new HashMap<>();

        try {
            TermEmbeddingService.TermEmbeddingStats stats = termEmbeddingService.getEmbeddingStats();

            response.put("success", true);
            response.put("totalTerms", stats.getTotalTerms());
            response.put("termsWithEmbedding", stats.getTermsWithEmbedding());
            response.put("termsWithoutEmbedding", stats.getTermsWithoutEmbedding());
            response.put("completionPercentage", String.format("%.2f%%", stats.getCompletionPercentage()));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Term 임베딩 통계 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "통계 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ===== Article Summary 생성 관련 API (프로토타입) =====

    /**
     * 단일 Article의 요약 생성 + 임베딩 저장
     *
     * LLM을 통해 "3줄 요약 + 핵심 키워드"를 생성하고,
     * 이를 Article.summary 필드에 저장한 후 임베딩 생성
     */
    @Transactional
    @GetMapping("/articles/{id}/generate-summary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateArticleSummary(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article {} 요약 생성 요청", id);

            Optional<Article> articleOpt = articleRepository.findById(id);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다: " + id);
                return ResponseEntity.badRequest().body(response);
            }

            Article article = articleOpt.get();

            // 1. 본문 확인
            if (article.getContent() == null || article.getContent().trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "본문이 비어있어 요약을 생성할 수 없습니다");
                return ResponseEntity.badRequest().body(response);
            }

            // 2. LLM으로 요약 생성
            ArticleSummaryResponse summaryResponse = openaiService.generateArticleSummary(article);

            // 3. Article.summary 필드에 저장 (사용자 노출용 텍스트)
            article.setSummary(summaryResponse.toDisplayText());
            articleRepository.save(article);

            // 4. summary 기반 임베딩 생성 및 저장
            float[] embedding = generateSummaryEmbedding(article);
            if (embedding != null) {
                saveEmbeddingToArticle(article, embedding);
            }

            response.put("success", true);
            response.put("articleId", id);
            response.put("summary", summaryResponse.toDisplayText());
            response.put("keywords", summaryResponse.getKeywords());
            response.put("embeddingGenerated", embedding != null);
            response.put("message", "요약 생성 및 임베딩 저장 완료");

            log.info("Article {} - 요약 생성 완료", id);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} 요약 생성 실패", id, e);
            response.put("success", false);
            response.put("message", "요약 생성 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 여러 Article의 요약 배치 생성
     *
     * RequestBody:
     * {
     *   "articleIds": [1, 2, 3] (optional),
     *   "corporationId": 123 (optional),
     *   "withoutSummary": true (optional, default: true),
     *   "limit": 10 (optional, default: 10)
     * }
     */
    @Transactional
    @org.springframework.web.bind.annotation.PostMapping("/articles/generate-summary-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateArticleSummaryBatch(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long corporationId,
            @RequestParam(required = false, defaultValue = "true") Boolean withoutSummary,
            @RequestBody(required = false) Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Query Parameter 우선, 없으면 RequestBody에서 추출
            List<Long> articleIds = null;

            if (request != null) {
                @SuppressWarnings("unchecked")
                List<Integer> articleIdsInt = request.containsKey("articleIds")
                    ? (List<Integer>) request.get("articleIds")
                    : null;

                if (articleIdsInt != null) {
                    articleIds = articleIdsInt.stream().map(Integer::longValue).toList();
                }

                // RequestBody에서도 파라미터를 가져올 수 있도록 지원
                if (corporationId == null && request.containsKey("corporationId")) {
                    corporationId = ((Number) request.get("corporationId")).longValue();
                }
                if (limit == null && request.containsKey("limit")) {
                    limit = ((Number) request.get("limit")).intValue();
                }
                if (request.containsKey("withoutSummary")) {
                    withoutSummary = (Boolean) request.get("withoutSummary");
                }
            }

            // 기본값 설정
            if (limit == null) {
                limit = 50; // 기본 50개
            }
            if (withoutSummary == null) {
                withoutSummary = true;
            }

            log.info("배치 요약 생성 요청 - articleIds: {}, corporationId: {}, withoutSummary: {}, limit: {}",
                    articleIds, corporationId, withoutSummary, limit);

            // 1. Article 조회
            List<Article> articles;
            if (articleIds != null && !articleIds.isEmpty()) {
                // 특정 ID들만 조회
                articles = articleRepository.findAllById(articleIds);
            } else if (withoutSummary) {
                // summary가 없는 Article 조회
                if (corporationId != null) {
                    articles = articleRepository.findArticlesWithContentAndWithoutSummaryByCorporationId(
                            corporationId, PageRequest.of(0, limit));
                } else {
                    articles = articleRepository.findArticlesWithContentAndWithoutSummary(
                            PageRequest.of(0, limit));
                }
            } else {
                // content가 있는 Article 조회
                if (corporationId != null) {
                    articles = articleRepository.findByCorporationIdAndContentIsNotNullAndDeletedAtIsNull(
                            corporationId, PageRequest.of(0, limit));
                } else {
                    articles = articleRepository.findByContentIsNotNullAndDeletedAtIsNull(
                            PageRequest.of(0, limit));
                }
            }

            if (articles.isEmpty()) {
                response.put("success", true);
                response.put("message", "처리할 Article이 없습니다");
                response.put("totalArticles", 0);
                return ResponseEntity.ok(response);
            }

            log.info("{}개 Article 요약 생성 시작", articles.size());

            // 2. 배치 처리
            int successCount = 0;
            int failedCount = 0;
            List<Map<String, Object>> results = new java.util.ArrayList<>();

            for (Article article : articles) {
                try {
                    // 본문 확인
                    if (article.getContent() == null || article.getContent().trim().isEmpty()) {
                        log.warn("Article {} - 본문이 없어 스킵", article.getId());
                        failedCount++;
                        continue;
                    }

                    // LLM 요약 생성
                    ArticleSummaryResponse summaryResponse = openaiService.generateArticleSummary(article);

                    // summary 저장
                    article.setSummary(summaryResponse.toDisplayText());
                    articleRepository.save(article);

                    // 임베딩 생성
                    float[] embedding = generateSummaryEmbedding(article);
                    if (embedding != null) {
                        saveEmbeddingToArticle(article, embedding);
                    }

                    Map<String, Object> result = new HashMap<>();
                    result.put("articleId", article.getId());
                    result.put("title", article.getTitle());
                    result.put("summary", summaryResponse.toDisplayText());
                    result.put("keywords", summaryResponse.getKeywords());
                    results.add(result);

                    successCount++;

                    // Rate Limit 방지를 위한 대기 (1초)
                    Thread.sleep(1000);

                } catch (Exception e) {
                    log.error("Article {} 요약 생성 실패", article.getId(), e);
                    failedCount++;
                }
            }

            response.put("success", true);
            response.put("totalArticles", articles.size());
            response.put("successCount", successCount);
            response.put("failedCount", failedCount);
            response.put("results", results);
            response.put("message", String.format("%d개 요약 생성 완료 (실패: %d)",
                    successCount, failedCount));

            log.info("배치 요약 생성 완료 - 성공: {}/{}", successCount, articles.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("배치 요약 생성 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "배치 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ===== Article Title 생성 관련 API =====

    /**
     * 여러 Article의 제목 배치 생성
     *
     * RequestBody:
     * {
     *   "articleIds": [1, 2, 3] (optional),
     *   "corporationId": 123 (optional),
     *   "withoutGeneratedTitle": true (optional, default: true),
     *   "limit": 10 (optional, default: 10)
     * }
     */
    @Transactional
    @org.springframework.web.bind.annotation.PostMapping("/articles/generate-title-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> generateArticleTitleBatch(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long corporationId,
            @RequestParam(required = false, defaultValue = "true") Boolean withoutGeneratedTitle,
            @RequestBody(required = false) Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Query Parameter 우선, 없으면 RequestBody에서 추출
            List<Long> articleIds = null;

            if (request != null) {
                @SuppressWarnings("unchecked")
                List<Integer> articleIdsInt = request.containsKey("articleIds")
                    ? (List<Integer>) request.get("articleIds")
                    : null;

                if (articleIdsInt != null) {
                    articleIds = articleIdsInt.stream().map(Integer::longValue).toList();
                }

                // RequestBody에서도 파라미터를 가져올 수 있도록 지원
                if (corporationId == null && request.containsKey("corporationId")) {
                    corporationId = ((Number) request.get("corporationId")).longValue();
                }
                if (limit == null && request.containsKey("limit")) {
                    limit = ((Number) request.get("limit")).intValue();
                }
                if (request.containsKey("withoutGeneratedTitle")) {
                    withoutGeneratedTitle = (Boolean) request.get("withoutGeneratedTitle");
                }
            }

            // 기본값 설정
            if (limit == null) {
                limit = 10;
            }
            if (withoutGeneratedTitle == null) {
                withoutGeneratedTitle = true;
            }

            log.info("배치 제목 생성 요청 - articleIds: {}, corporationId: {}, withoutGeneratedTitle: {}, limit: {}",
                    articleIds, corporationId, withoutGeneratedTitle, limit);

            // 1. Article 조회
            List<Article> articles;
            if (articleIds != null && !articleIds.isEmpty()) {
                // 특정 ID들만 조회
                articles = articleRepository.findAllById(articleIds);
            } else if (withoutGeneratedTitle) {
                // generatedTitle이 없고 content가 있는 Article 조회
                if (corporationId != null) {
                    articles = articleRepository.findByGeneratedTitleIsNullAndContentIsNotNullAndCorporationIdAndDeletedAtIsNull(
                            corporationId, PageRequest.of(0, limit));
                } else {
                    articles = articleRepository.findByGeneratedTitleIsNullAndContentIsNotNullAndDeletedAtIsNull(
                            PageRequest.of(0, limit));
                }
            } else {
                // content가 있는 Article 조회
                if (corporationId != null) {
                    articles = articleRepository.findByCorporationIdAndContentIsNotNullAndDeletedAtIsNull(
                            corporationId, PageRequest.of(0, limit));
                } else {
                    articles = articleRepository.findByContentIsNotNullAndDeletedAtIsNull(
                            PageRequest.of(0, limit));
                }
            }

            if (articles.isEmpty()) {
                response.put("success", true);
                response.put("message", "처리할 Article이 없습니다");
                response.put("totalArticles", 0);
                return ResponseEntity.ok(response);
            }

            log.info("{}개 Article 제목 생성 시작", articles.size());

            // 2. 배치 처리
            int successCount = 0;
            int failedCount = 0;
            List<Map<String, Object>> results = new java.util.ArrayList<>();

            for (Article article : articles) {
                try {
                    // 본문 확인
                    if (article.getContent() == null || article.getContent().trim().isEmpty()) {
                        log.warn("Article {} - 본문이 없어 스킵", article.getId());
                        failedCount++;
                        continue;
                    }

                    // 제목 생성
                    String generatedTitle = openaiService.generateArticleTitle(article);

                    // generatedTitle 저장
                    article.setGeneratedTitle(generatedTitle);
                    articleRepository.save(article);

                    Map<String, Object> result = new HashMap<>();
                    result.put("articleId", article.getId());
                    result.put("originalTitle", article.getTitle());
                    result.put("generatedTitle", generatedTitle);
                    results.add(result);

                    successCount++;

                    // Rate Limit 방지를 위한 대기 (1초)
                    Thread.sleep(1000);

                } catch (Exception e) {
                    log.error("Article {} 제목 생성 실패", article.getId(), e);
                    failedCount++;
                }
            }

            response.put("success", true);
            response.put("totalArticles", articles.size());
            response.put("successCount", successCount);
            response.put("failedCount", failedCount);
            response.put("results", results);
            response.put("message", String.format("%d개 제목 생성 완료 (실패: %d)",
                    successCount, failedCount));

            log.info("배치 제목 생성 완료 - 성공: {}/{}", successCount, articles.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("배치 제목 생성 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "배치 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ===== 대표 Chunk 선정 관련 API =====

    /**
     * 단일 Article의 대표 Chunk 선정
     */
    @GetMapping("/articles/{id}/select-representative-chunk")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> selectRepresentativeChunk(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article {} 대표 Chunk 선정 요청", id);

            Long chunkId = representativeChunkService.selectRepresentativeChunk(id);

            if (chunkId != null) {
                response.put("success", true);
                response.put("articleId", id);
                response.put("representativeChunkId", chunkId);
                response.put("message", "대표 Chunk 선정 완료");
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "대표 Chunk를 선정할 수 없습니다 (청크가 없거나 임베딩이 없음)");
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            log.error("Article {} 대표 Chunk 선정 실패", id, e);
            response.put("success", false);
            response.put("message", "대표 Chunk 선정 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 대표 Chunk가 없는 Article에 대해 배치 선정
     *
     * 대표 Chunk가 아직 선정되지 않은 Article들만 처리
     *
     * @param limit 처리할 최대 Article 수 (기본값: 100, 0이면 전체)
     */
    @org.springframework.web.bind.annotation.GetMapping("/articles/select-representative-chunks-batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> selectRepresentativeChunksBatch(
            @RequestParam(required = false, defaultValue = "100") Integer limit) {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("대표 Chunk 배치 선정 요청 (미선정 Article만, limit={})", limit);

            RepresentativeChunkService.BatchResult result =
                representativeChunkService.selectRepresentativeChunksForAll(limit);

            response.put("success", true);
            response.put("successCount", result.getSuccess());
            response.put("skippedCount", result.getSkipped());
            response.put("failedCount", result.getFailed());
            response.put("totalProcessed", result.getTotal());
            response.put("remainingCount", result.getRemainingCount());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("message", String.format(
                "대표 Chunk 선정 완료: 성공=%d, 스킵=%d, 실패=%d, 남은 Article=%d, 소요시간=%dms",
                result.getSuccess(), result.getSkipped(), result.getFailed(),
                result.getRemainingCount(), result.getProcessingTimeMs()));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("대표 Chunk 배치 선정 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "배치 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 모든 Article의 대표 Chunk 강제 재선정
     *
     * 이미 대표 Chunk가 있는 Article도 다시 선정
     */
    @org.springframework.web.bind.annotation.PostMapping("/articles/reselect-representative-chunks-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reselectAllRepresentativeChunks() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("대표 Chunk 전체 강제 재선정 요청");

            RepresentativeChunkService.BatchResult result =
                representativeChunkService.reselectAllRepresentativeChunks();

            response.put("success", true);
            response.put("successCount", result.getSuccess());
            response.put("skippedCount", result.getSkipped());
            response.put("failedCount", result.getFailed());
            response.put("totalProcessed", result.getTotal());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("message", String.format(
                "대표 Chunk 재선정 완료: 성공=%d, 스킵=%d, 실패=%d, 소요시간=%dms",
                result.getSuccess(), result.getSkipped(), result.getFailed(), result.getProcessingTimeMs()));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("대표 Chunk 전체 재선정 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "재선정 처리 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 대표 Chunk 통계 조회
     */
    @GetMapping("/articles/representative-chunk-stats")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRepresentativeChunkStats() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 전체 Article 수 (청크가 있는)
            long totalArticlesWithChunks = clovaArticleChunkRepository.findArticleIdsWithEmbedding().size();

            // 대표 Chunk가 선정된 Article 수
            long articlesWithRepresentative = clovaArticleChunkRepository.findArticleIdsWithRepresentativeChunk().size();

            // 대표 Chunk가 없는 Article 수
            long articlesWithoutRepresentative = clovaArticleChunkRepository.findArticleIdsWithoutRepresentativeChunk().size();

            // 전체 대표 Chunk 수
            long totalRepresentativeChunks = clovaArticleChunkRepository.countRepresentativeChunks();

            // 커버리지 계산
            double coverage = totalArticlesWithChunks > 0
                ? (double) articlesWithRepresentative / totalArticlesWithChunks * 100
                : 0.0;

            response.put("success", true);
            response.put("totalArticlesWithChunks", totalArticlesWithChunks);
            response.put("articlesWithRepresentative", articlesWithRepresentative);
            response.put("articlesWithoutRepresentative", articlesWithoutRepresentative);
            response.put("totalRepresentativeChunks", totalRepresentativeChunks);
            response.put("coverage", String.format("%.2f%%", coverage));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("대표 Chunk 통계 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "통계 조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    private float[] generateSummaryEmbedding(Article article) {
        if (article.getSummary() == null || article.getSummary().trim().isEmpty()) {
            return null;
        }
        return articleEmbeddingService.generateEmbedding(article.getSummary());
    }

    private void saveEmbeddingToArticle(Article article, float[] embedding) {
        article.setEmbedding(embedding);
        article.setEmbeddingGeneratedAt(LocalDateTime.now());
        articleRepository.save(article);
    }
}
