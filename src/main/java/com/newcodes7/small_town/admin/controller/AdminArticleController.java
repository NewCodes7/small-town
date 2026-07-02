package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.service.AdminArticleListService;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.repository.ArticleSummaryRepository;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.ChunkContent;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ChunkContentRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleSummary;
import com.newcodes7.small_town.global.entity.RelatedContentKeyword;
import com.newcodes7.small_town.term.service.ArticleTermService;
import com.newcodes7.small_town.term.service.RelatedContentKeywordService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Article 기본 관리 Controller
 */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminArticleController {

    private final ArticleRepository articleRepository;
    private final ArticleSummaryRepository articleSummaryRepository;
    private final ArticlePersistenceService articlePersistenceService;
    private final CategoryRepository categoryRepository;
    private final AdminArticleListService adminArticleListService;
    private final RelatedContentKeywordService relatedContentKeywordService;
    private final ArticleTermService articleTermService;
    private final ArticleChunkRepository articleChunkRepository;
    private final ChunkContentRepository chunkContentRepository;

    /**
     * 글 목록 페이지
     */
    @GetMapping("/articles")
    public String articleList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "articles") String tab,
            @RequestParam(defaultValue = "frequency") String sort,
            Model model) {

        // Service 계층에서 모든 데이터 조회
        AdminArticleListService.ArticleListData data =
                adminArticleListService.getArticleListData(tab, search, sort, page, size);

        // search 파라미터가 있으면 Model에 추가
        if (search != null && !search.trim().isEmpty()) {
            model.addAttribute("search", search);
        }

        // Model에 데이터 추가
        model.addAttribute("articles", data.getArticles());
        model.addAttribute("videos", data.getVideos());
        model.addAttribute("corporations", data.getCorporations());
        model.addAttribute("themes", data.getThemes());
        model.addAttribute("articlesWithTerms", data.getArticlesWithTerms());
        model.addAttribute("videosWithTerms", data.getVideosWithTerms());
        model.addAttribute("articleTermStats", data.getArticleTermStats());
        model.addAttribute("articleTermStatsTotal", data.getArticleTermStatsTotal());
        model.addAttribute("articleTermTotalPages", data.getArticleTermTotalPages());
        model.addAttribute("videoTermStats", data.getVideoTermStats());
        model.addAttribute("videoTermStatsTotal", data.getVideoTermStatsTotal());
        model.addAttribute("videoTermTotalPages", data.getVideoTermTotalPages());
        model.addAttribute("searchLogs", data.getSearchLogs());
        model.addAttribute("aiSummaryLogs", data.getAiSummaryLogs());
        model.addAttribute("users", data.getUsers());
        model.addAttribute("likeLogs", data.getLikeLogs());
        model.addAttribute("crawlingRuns", data.getCrawlingRuns());
        model.addAttribute("categories", categoryRepository.findAll());
        model.addAttribute("currentTab", tab);
        model.addAttribute("currentSort", sort);
        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);

        return "admin/article/list";
    }

    /**
     * 글 카테고리 수정 API
     */
    @PutMapping("/articles/{articleId}/category")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticleCategory(
            @PathVariable Long articleId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String categoryName = request.get("categoryName");

            if (categoryName == null || categoryName.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "카테고리 이름이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            articlePersistenceService.updateArticleCategory(articleId, categoryName.trim());

            response.put("success", true);
            response.put("message", "카테고리가 성공적으로 수정되었습니다.");
            response.put("articleId", articleId);
            response.put("categoryName", categoryName.trim());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "카테고리 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 상세 정보 조회 API
     */
    @GetMapping("/articles/{articleId}/detail")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticleDetail(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "글을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Article article = articleOpt.get();

            // DTO로 변환하여 직렬화 문제 해결
            Map<String, Object> articleData = new HashMap<>();
            articleData.put("id", article.getId());
            articleData.put("title", article.getTitle());
            articleData.put("translatedTitle", article.getTranslatedTitle());
            articleData.put("link", article.getLink());
            articleData.put("thumbnailImage", article.getThumbnailImage());
            articleData.put("summary", article.getSummary());
            articleData.put("viewCount", article.getViewCount());
            articleData.put("likeCount", article.getLikeCount());
            articleData.put("publishedAt", article.getPublishedAt());

            if (article.getCategory() != null) {
                Map<String, Object> categoryData = new HashMap<>();
                categoryData.put("id", article.getCategory().getId());
                categoryData.put("name", article.getCategory().getName());
                articleData.put("category", categoryData);
            }

            response.put("success", true);
            response.put("article", articleData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "글 정보 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 기본 정보 수정 API
     */
    @PutMapping("/articles/{articleId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticle(
            @PathVariable Long articleId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String title = (String) request.get("title");
            String translatedTitle = (String) request.get("translatedTitle");
            String link = (String) request.get("link");
            String thumbnailUrl = (String) request.get("thumbnailUrl");
            String categoryName = (String) request.get("categoryName");

            // ArticlePersistenceService를 통해 캐시 무효화와 함께 수정
            articlePersistenceService.updateArticleBasicInfo(
                articleId, title, translatedTitle, link, thumbnailUrl, categoryName);

            response.put("success", true);
            response.put("message", "글 정보가 성공적으로 수정되었습니다.");
            response.put("articleId", articleId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "글 정보 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 요약 목록 조회 API
     */
    @GetMapping("/articles/{articleId}/summaries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticleSummaries(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "글을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            List<ArticleSummary> summaries = articleSummaryRepository.findByArticleIdAndDeletedAtIsNullOrderByCreatedAt(articleId);

            // DTO로 변환하여 직렬화 문제 해결
            List<Map<String, Object>> summaryDataList = new ArrayList<>();
            for (ArticleSummary summary : summaries) {
                Map<String, Object> summaryData = new HashMap<>();
                summaryData.put("id", summary.getId());
                summaryData.put("contentType", summary.getContentType());
                summaryData.put("content", summary.getContent());
                summaryData.put("createdAt", summary.getCreatedAt());
                summaryData.put("updatedAt", summary.getUpdatedAt());
                summaryDataList.add(summaryData);
            }

            response.put("success", true);
            response.put("summaries", summaryDataList);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "요약 정보 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 요약 수정 API
     */
    @PutMapping("/articles/{articleId}/summaries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticleSummaries(
            @PathVariable Long articleId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "글을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Article article = articleOpt.get();

            // 기존 요약들을 soft delete
            List<ArticleSummary> existingSummaries = articleSummaryRepository.findByArticleIdAndDeletedAtIsNullOrderByCreatedAt(articleId);
            for (ArticleSummary summary : existingSummaries) {
                summary.setDeletedAt(java.time.LocalDateTime.now());
            }
            articleSummaryRepository.saveAll(existingSummaries);

            // 새로운 요약들 저장
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> summariesData = (List<Map<String, Object>>) request.get("summaries");

            List<ArticleSummary> newSummaries = new ArrayList<>();
            for (Map<String, Object> summaryData : summariesData) {
                String contentType = (String) summaryData.get("contentType");
                String content = (String) summaryData.get("content");

                if (content != null && !content.trim().isEmpty()) {
                    ArticleSummary summary = ArticleSummary.builder()
                            .article(article)
                            .contentType(contentType != null ? contentType : "li")
                            .content(content.trim())
                            .build();
                    newSummaries.add(summary);
                }
            }

            articleSummaryRepository.saveAll(newSummaries);

            response.put("success", true);
            response.put("message", "요약이 성공적으로 수정되었습니다.");
            response.put("articleId", articleId);
            response.put("summaryCount", newSummaries.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "요약 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 본문(content) 조회 API
     */
    @Transactional(readOnly = true)
    @GetMapping("/articles/{articleId}/content")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticleContent(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "글을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Article article = articleOpt.get();

            response.put("success", true);
            response.put("articleId", article.getId());
            response.put("title", article.getTitle());
            response.put("content", article.getContent());
            response.put("hasContent", article.getContent() != null && !article.getContent().isBlank());
            response.put("contentLength", article.getContent() != null ? article.getContent().length() : 0);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} content 조회 중 오류 발생", articleId, e);
            response.put("success", false);
            response.put("message", "본문 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ============================================
    // 관련 글 키워드 관리 API
    // ============================================

    /**
     * 모든 관련 글 키워드 조회
     */
    @GetMapping("/related-content-keywords")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getAllRelatedContentKeywords() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<RelatedContentKeyword> keywords = relatedContentKeywordService.getAllKeywords();
            response.put("success", true);
            response.put("keywords", keywords);
            response.put("count", keywords.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("관련 글 키워드 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "키워드 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 관련 글 키워드 추가
     */
    @PostMapping("/related-content-keywords")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addRelatedContentKeyword(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String keyword = request.get("keyword");
            if (keyword == null || keyword.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "키워드는 필수입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            RelatedContentKeyword saved = relatedContentKeywordService.addKeyword(keyword);
            response.put("success", true);
            response.put("message", "키워드가 추가되었습니다.");
            response.put("keyword", saved);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("관련 글 키워드 추가 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "키워드 추가 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 관련 글 키워드 수정
     */
    @PutMapping("/related-content-keywords/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateRelatedContentKeyword(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String keyword = request.get("keyword");
            if (keyword == null || keyword.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "키워드는 필수입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            RelatedContentKeyword updated = relatedContentKeywordService.updateKeyword(id, keyword);
            response.put("success", true);
            response.put("message", "키워드가 수정되었습니다.");
            response.put("keyword", updated);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("관련 글 키워드 수정 중 오류 발생: id={}", id, e);
            response.put("success", false);
            response.put("message", "키워드 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 관련 글 키워드 삭제
     */
    @DeleteMapping("/related-content-keywords/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteRelatedContentKeyword(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            relatedContentKeywordService.deleteKeyword(id);
            response.put("success", true);
            response.put("message", "키워드가 삭제되었습니다.");
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("관련 글 키워드 삭제 중 오류 발생: id={}", id, e);
            response.put("success", false);
            response.put("message", "키워드 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ============================================
    // Embedding 정보 조회 API
    // ============================================

    /**
     * Article의 embedding 정보 조회
     */
    @GetMapping("/articles/{articleId}/embeddings")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticleEmbeddings(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Article article = articleOpt.get();

            // 해당 Article의 모든 chunk 조회
            List<ArticleChunk> chunks = articleChunkRepository.findByArticleIdOrderByChunkIndexAsc(articleId);

            // Chunk 정보를 DTO로 변환
            List<Long> chunkIds = chunks.stream().map(ArticleChunk::getId).toList();
            Map<Long, String> contentMap = chunkContentRepository.findAllById(chunkIds).stream()
                    .collect(Collectors.toMap(ChunkContent::getId, ChunkContent::getContent));

            List<Map<String, Object>> chunkDataList = new ArrayList<>();
            int totalChunks = chunks.size();
            int chunksWithEmbedding = 0;

            for (ArticleChunk chunk : chunks) {
                String content = contentMap.getOrDefault(chunk.getId(), "");
                Map<String, Object> chunkData = new HashMap<>();
                chunkData.put("id", chunk.getId());
                chunkData.put("chunkIndex", chunk.getChunkIndex());
                chunkData.put("content", content);
                chunkData.put("contentLength", content.length());
                boolean hasEmbedding = chunk.getEmbeddingBinary() != null;
                chunkData.put("hasEmbedding", hasEmbedding);
                chunkData.put("embeddingDimension", hasEmbedding ? 1024 : 0);
                chunkData.put("embeddingGeneratedAt", chunk.getEmbeddingGeneratedAt());
                chunkData.put("createdAt", chunk.getCreatedAt());

                if (hasEmbedding) {
                    chunksWithEmbedding++;
                }

                chunkDataList.add(chunkData);
            }

            // 임베딩 정보 추가
            boolean hasChunkEmbedding = articleChunkRepository.existsByArticleId(articleId);
            long chunkCount = articleChunkRepository.countByArticleId(articleId);

            response.put("success", true);
            response.put("articleId", articleId);
            response.put("articleTitle", article.getTitle());
            response.put("totalChunks", totalChunks);
            response.put("chunksWithEmbedding", chunksWithEmbedding);
            response.put("chunksWithoutEmbedding", totalChunks - chunksWithEmbedding);
            response.put("embeddingCoverage", totalChunks > 0 ? (double) chunksWithEmbedding / totalChunks * 100 : 0);
            response.put("chunks", chunkDataList);
            response.put("hasChunkEmbedding", hasChunkEmbedding);
            response.put("chunkCount", chunkCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article {} embedding 정보 조회 중 오류 발생", articleId, e);
            response.put("success", false);
            response.put("message", "Embedding 정보 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Chunk의 전체 embedding 벡터 조회
     */
    @GetMapping("/chunks/{chunkId}/embedding")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getChunkEmbedding(@PathVariable Long chunkId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<ArticleChunk> chunkOpt = articleChunkRepository.findById(chunkId);
            if (chunkOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Chunk를 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            ArticleChunk chunk = chunkOpt.get();

            boolean hasEmbedding = chunk.getEmbeddingBinary() != null;
            response.put("success", true);
            response.put("chunkId", chunkId);
            response.put("chunkIndex", chunk.getChunkIndex());
            response.put("articleId", chunk.getArticle().getId());
            response.put("hasEmbedding", hasEmbedding);

            if (hasEmbedding) {
                response.put("embeddingDimension", 1024);
                response.put("embeddingGeneratedAt", chunk.getEmbeddingGeneratedAt());
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Chunk {} embedding 조회 중 오류 발생", chunkId, e);
            response.put("success", false);
            response.put("message", "Embedding 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ===== Article Term 추출 API =====

    /**
     * 최신 article부터 지정된 개수만큼 term 추출
     * POST /admin/articles/extract-terms
     *
     * @param limit 추출할 article 개수 (기본값: 50, 최대: 500)
     * @param forceReanalyze 이미 term이 있어도 재분석 여부 (기본값: false)
     * @return 추출 결과
     */
    @PostMapping("/articles/extract-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractArticleTerms(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean forceReanalyze) {

        Map<String, Object> response = new HashMap<>();

        try {
            // limit 범위 체크
            if (limit < 1) {
                limit = 1;
            } else if (limit > 500) {
                limit = 500;
            }

            log.info("최신 article {} 개 term 추출 API 호출 (강제재분석: {})", limit, forceReanalyze);

            ArticleTermService.ArticleTermExtractionResult result =
                    articleTermService.extractLatestArticleTerms(limit, forceReanalyze);

            response.put("success", true);
            response.put("processedArticles", result.getProcessedArticles());
            response.put("skippedArticles", result.getSkippedArticles());
            response.put("failedArticles", result.getFailedArticles());
            response.put("totalTerms", result.getTotalTerms());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("message", String.format(
                    "Term 추출 완료: 처리=%d, 건너뜀=%d, 실패=%d, term=%d",
                    result.getProcessedArticles(),
                    result.getSkippedArticles(),
                    result.getFailedArticles(),
                    result.getTotalTerms()
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 추출 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "Term 추출 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 article의 term 추출
     * POST /admin/articles/{id}/extract-terms
     *
     * @param id article ID
     * @return 추출 결과
     */
    @PostMapping("/articles/{id}/extract-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractSingleArticleTerms(@PathVariable Long id) {

        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article ID {} term 추출 API 호출", id);

            ArticleTermService.ArticleTermExtractionResult result =
                    articleTermService.extractTermsForSingleArticle(id);

            response.put("success", true);
            response.put("articleId", id);
            response.put("processedArticles", result.getProcessedArticles());
            response.put("failedArticles", result.getFailedArticles());
            response.put("totalTerms", result.getTotalTerms());
            response.put("processingTimeMs", result.getProcessingTimeMs());
            response.put("message", String.format(
                    "Article ID %d term 추출 완료: term=%d개",
                    id,
                    result.getTotalTerms()
            ));

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Article ID {} term 추출 실패: {}", id, e.getMessage());
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Article ID {} term 추출 중 오류 발생", id, e);
            response.put("success", false);
            response.put("message", "Term 추출 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
