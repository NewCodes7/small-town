package com.newcodes7.small_town.admin.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.UserDictionaryRepository;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.article.service.ArticleTermService;
import com.newcodes7.small_town.video.repository.VideoTermRepository;
import com.newcodes7.small_town.video.service.VideoTermService;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationUpdateDto;
import com.newcodes7.small_town.corporation.exception.CorporationException;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.crawler.repository.ArticleSummaryRepository;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.crawler.service.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.service.TitleTranslationService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleSummary;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.service.SearchLogService;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.service.VideoService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {
    
    private final CorporationService corporationService;
    private final IndustryRepository industryRepository;
    private final CrawlingService crawlingService;
    private final CategoryRepository categoryRepository;
    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final ArticleSummaryRepository articleSummaryRepository;
    private final TitleTranslationService titleTranslationService;
    private final ArticlePersistenceService articlePersistenceService;
    private final VideoRepository videoRepository;
    private final VideoService videoService;
    private final ArticleTermService articleTermService;
    private final ArticleTermRepository articleTermRepository;
    private final VideoTermService videoTermService;
    private final VideoTermRepository videoTermRepository;
    private final UserDictionaryRepository userDictionaryRepository;
    private final SearchLogService searchLogService;

    // 기업 목록 페이지
    @GetMapping("/corporations")
    public String corporationList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            Model model) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        // 통합 필터링 메서드 사용
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(search, null, null, pageable);

        model.addAttribute("corporations", corporations);
        if (search != null && !search.trim().isEmpty()) {
            model.addAttribute("search", search);
        }
        return "admin/corporation/list";
    }
    
    // 기업 등록 폼 페이지
    @GetMapping("/corporations/new")
    public String corporationForm(Model model) {
        model.addAttribute("corporation", new CorporationCreateDto());
        model.addAttribute("industries", industryRepository.findAll());
        return "admin/corporation/form";
    }
    
    // 기업 등록 처리
    @PostMapping("/corporations")
    public String createCorporation(
            @Valid @ModelAttribute("corporation") CorporationCreateDto dto,
            @RequestParam(value = "logoFile", required = false) MultipartFile logoFile,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        
        if (bindingResult.hasErrors()) {
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/form";
        }
        
        try {
            if (logoFile != null && !logoFile.isEmpty()) {
                corporationService.createCorporationWithLogo(dto, logoFile);
            } else {
                corporationService.createCorporation(dto);
            }
            redirectAttributes.addFlashAttribute("successMessage", "기업이 성공적으로 등록되었습니다.");
            return "redirect:/admin/corporations";
        } catch (Exception e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/form";
        }
    }
    
    // 기업 수정 폼 페이지
    @GetMapping("/corporations/{id}/edit")
    public String corporationEditForm(@PathVariable Long id, Model model) {
        try {
            CorporationResponseDto corporation = corporationService.getCorporationById(id);
            CorporationUpdateDto updateDto = new CorporationUpdateDto();
            updateDto.setName(corporation.getName());
            updateDto.setAlternateName(corporation.getAlternateName());
            updateDto.setHomeLink(corporation.getHomeLink());
            updateDto.setBlogLink(corporation.getBlogLink());
            updateDto.setCrewLink(corporation.getCrewLink());
            updateDto.setLogoUrl(corporation.getLogoUrl());
            updateDto.setYoutubeUrl(corporation.getYoutubeUrl());
            updateDto.setBaseUrl(corporation.getBaseUrl());
            updateDto.setArticle(corporation.getArticle());
            updateDto.setTitle(corporation.getTitle());
            updateDto.setLink(corporation.getLink());
            updateDto.setThumbnail(corporation.getThumbnail());
            updateDto.setPublish(corporation.getPublish());
            updateDto.setPublishFormat(corporation.getPublishFormat());
            updateDto.setEffectiveLogoUrl(corporation.getEffectiveLogoUrl());
            
            model.addAttribute("corporation", updateDto);
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/edit";
        } catch (CorporationException e) {
            return "redirect:/admin/corporations";
        }
    }
    
    // 기업 수정 처리
    @PostMapping("/corporations/{id}")
    public String updateCorporation(
            @PathVariable Long id,
            @Valid @ModelAttribute("corporation") CorporationUpdateDto dto,
            @RequestParam(value = "logoFile", required = false) MultipartFile logoFile,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        
        if (bindingResult.hasErrors()) {
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/edit";
        }
        
        try {
            if (logoFile != null && !logoFile.isEmpty()) {
                corporationService.updateCorporationWithLogo(id, dto, logoFile);
            } else {
                corporationService.updateCorporation(id, dto);
            }
            redirectAttributes.addFlashAttribute("successMessage", "기업 정보가 성공적으로 수정되었습니다.");
            return "redirect:/admin/corporations";
        } catch (Exception e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("corporationId", id);
            model.addAttribute("industries", industryRepository.findAll());
            return "admin/corporation/edit";
        }
    }
    
    // 기업 삭제 처리
    @PostMapping("/corporations/{id}/delete")
    public String deleteCorporation(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            corporationService.deleteCorporation(id);
            redirectAttributes.addFlashAttribute("successMessage", "기업이 성공적으로 삭제되었습니다.");
        } catch (CorporationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/corporations";
    }

    // 글 목록 페이지
    @GetMapping("/articles")
    public String articleList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "articles") String tab,
            Model model) {

        Pageable pageable;

        // 기업 탭의 경우 조회수 기준 내림차순 정렬
        if (tab.equals("corporations")) {
            pageable = PageRequest.of(page, size, Sort.by("viewCount").descending().and(Sort.by("name").ascending()));
        } else if (tab.equals("terms") || tab.equals("stats")) {
            pageable = PageRequest.of(page, size, Sort.by("id").descending());
        } else {
            pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending());
        }

        // 블로그 글 목록
        Page<Article> articles;
        if (search != null && !search.trim().isEmpty() && tab.equals("articles")) {
            articles = articleRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
            model.addAttribute("search", search);
        } else if (tab.equals("articles")) {
            articles = articleRepository.findByDeletedAtIsNull(pageable);
        } else {
            articles = Page.empty(pageable);
        }

        // 유튜브 영상 목록
        Page<Video> videos;
        if (search != null && !search.trim().isEmpty() && tab.equals("videos")) {
            videos = videoRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
            model.addAttribute("search", search);
        } else if (tab.equals("videos")) {
            videos = videoRepository.findByDeletedAtIsNull(pageable);
        } else {
            videos = Page.empty(pageable);
        }

        // 기업 목록 (조회수 포함)
        Page<CorporationResponseDto> corporations;
        if (search != null && !search.trim().isEmpty() && tab.equals("corporations")) {
            corporations = corporationService.getCorporationsWithFilters(search, null, null, pageable);
            model.addAttribute("search", search);
        } else if (tab.equals("corporations")) {
            corporations = corporationService.getCorporationsWithFilters(null, null, null, pageable);
        } else {
            corporations = Page.empty(pageable);
        }

        // Term 분석 목록 (Article)
        Page<Article> articlesWithTerms;
        if (tab.equals("terms")) {
            if (search != null && !search.trim().isEmpty()) {
                articlesWithTerms = articleRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
                model.addAttribute("search", search);
            } else {
                articlesWithTerms = articleRepository.findByDeletedAtIsNull(pageable);
            }
        } else {
            articlesWithTerms = Page.empty(pageable);
        }

        // Term 분석 목록 (Video)
        Page<Video> videosWithTerms;
        if (tab.equals("terms")) {
            if (search != null && !search.trim().isEmpty()) {
                videosWithTerms = videoRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
            } else {
                videosWithTerms = videoRepository.findByDeletedAtIsNull(pageable);
            }
        } else {
            videosWithTerms = Page.empty(pageable);
        }

        // Term 통계 (Article)
        List<com.newcodes7.small_town.article.repository.ArticleTermRepository.TermStatistics> articleTermStats = null;
        if (tab.equals("stats")) {
            Pageable statsPageable = PageRequest.of(page, size);
            if (search != null && !search.trim().isEmpty()) {
                articleTermStats = articleTermRepository.findTermStatisticsBySearch(search, statsPageable);
                model.addAttribute("search", search);
            } else {
                articleTermStats = articleTermRepository.findTermStatistics(statsPageable);
            }
        }

        // Term 통계 (Video)
        List<com.newcodes7.small_town.video.repository.VideoTermRepository.TermStatistics> videoTermStats = null;
        if (tab.equals("stats")) {
            Pageable statsPageable = PageRequest.of(page, size);
            if (search != null && !search.trim().isEmpty()) {
                videoTermStats = videoTermRepository.findTermStatisticsBySearch(search, statsPageable);
            } else {
                videoTermStats = videoTermRepository.findTermStatistics(statsPageable);
            }
        }

        // 검색 로그
        Page<SearchLog> searchLogs = null;
        if (tab.equals("searchlogs")) {
            Pageable searchLogPageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
            searchLogs = searchLogService.getAllSearchLogs(searchLogPageable);
        }

        model.addAttribute("articles", articles);
        model.addAttribute("videos", videos);
        model.addAttribute("corporations", corporations);
        model.addAttribute("articlesWithTerms", articlesWithTerms);
        model.addAttribute("videosWithTerms", videosWithTerms);
        model.addAttribute("articleTermStats", articleTermStats);
        model.addAttribute("videoTermStats", videoTermStats);
        model.addAttribute("searchLogs", searchLogs);
        model.addAttribute("categories", categoryRepository.findAll());
        model.addAttribute("currentTab", tab);
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
     * 사용 가능한 카테고리 목록 조회 API
     */
    @GetMapping("/categories")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCategories() {
        Map<String, Object> response = new HashMap<>();

        try {
            response.put("success", true);
            response.put("categories", categoryRepository.findAll());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "카테고리 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
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
     * 해외 기업 글 제목 번역 실행 API
     */
    @GetMapping("/articles/translate-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateOverseasArticleTitles() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행하여 오래 걸리는 작업이 UI를 블로킹하지 않도록 함
            new Thread(() -> {
                try {
                    titleTranslationService.translateAllOverseasArticleTitles();
                } catch (Exception e) {
                    log.error("제목 번역 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "해외 기업 글 제목 번역 작업이 시작되었습니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 기업의 글 제목 번역 API
     */
    @GetMapping("/corporations/{corporationId}/translate-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateCorporationTitles(@PathVariable Long corporationId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행
            new Thread(() -> {
                try {
                    titleTranslationService.translateCorporationArticleTitles(corporationId);
                } catch (Exception e) {
                    log.error("기업 {} 제목 번역 중 오류 발생", corporationId, e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "기업의 글 제목 번역 작업이 시작되었습니다.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 글 번역된 제목 수정 API
     */
    @PutMapping("/articles/{articleId}/translated-title")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticleTranslatedTitle(
            @PathVariable Long articleId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String translatedTitle = request.get("translatedTitle");

            if (translatedTitle == null) {
                response.put("success", false);
                response.put("message", "번역된 제목이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // ArticlePersistenceService를 통해 캐시 무효화와 함께 수정
            articlePersistenceService.updateArticleTranslatedTitle(articleId, translatedTitle);

            response.put("success", true);
            response.put("message", "번역된 제목이 성공적으로 수정되었습니다.");
            response.put("articleId", articleId);
            response.put("translatedTitle", translatedTitle.trim().isEmpty() ? null : translatedTitle.trim());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("번역된 제목 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역된 제목 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 영상 상세 정보 조회 API
     */
    @GetMapping("/videos/{videoId}/detail")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getVideoDetail(@PathVariable Long videoId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Video> videoOpt = videoRepository.findById(videoId);
            if (videoOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "영상을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Video video = videoOpt.get();

            // DTO로 변환하여 직렬화 문제 해결
            Map<String, Object> videoData = new HashMap<>();
            videoData.put("id", video.getId());
            videoData.put("title", video.getTitle());
            videoData.put("translatedTitle", video.getTranslatedTitle());
            videoData.put("link", video.getLink());
            videoData.put("thumbnailUrl", video.getThumbnailUrl());
            videoData.put("description", video.getDescription());
            videoData.put("viewCount", video.getViewCount());
            videoData.put("likeCount", video.getLikeCount());
            videoData.put("publishedAt", video.getPublishedAt());
            videoData.put("videoId", video.getVideoId());

            if (video.getCategory() != null) {
                Map<String, Object> categoryData = new HashMap<>();
                categoryData.put("id", video.getCategory().getId());
                categoryData.put("name", video.getCategory().getName());
                videoData.put("category", categoryData);
            }

            response.put("success", true);
            response.put("video", videoData);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "영상 정보 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 영상 기본 정보 수정 API
     */
    @PutMapping("/videos/{videoId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateVideo(
            @PathVariable Long videoId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String title = (String) request.get("title");
            String translatedTitle = (String) request.get("translatedTitle");
            String link = (String) request.get("link");
            String thumbnailUrl = (String) request.get("thumbnailUrl");
            String categoryName = (String) request.get("categoryName");

            // VideoService를 통해 캐시 무효화와 함께 수정
            videoService.updateVideoBasicInfo(
                videoId, title, translatedTitle, link, thumbnailUrl, categoryName);

            response.put("success", true);
            response.put("message", "영상 정보가 성공적으로 수정되었습니다.");
            response.put("videoId", videoId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "영상 정보 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 영상 카테고리 수정 API
     */
    @PutMapping("/videos/{videoId}/category")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateVideoCategory(
            @PathVariable Long videoId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String categoryName = request.get("categoryName");

            if (categoryName == null || categoryName.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "카테고리 이름이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            videoService.updateVideoCategory(videoId, categoryName.trim());

            response.put("success", true);
            response.put("message", "카테고리가 성공적으로 수정되었습니다.");
            response.put("videoId", videoId);
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
     * 영상 번역된 제목 수정 API
     */
    @PutMapping("/videos/{videoId}/translated-title")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateVideoTranslatedTitle(
            @PathVariable Long videoId,
            @RequestBody Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String translatedTitle = request.get("translatedTitle");

            if (translatedTitle == null) {
                response.put("success", false);
                response.put("message", "번역된 제목이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // VideoService를 통해 캐시 무효화와 함께 수정
            videoService.updateVideoTranslatedTitle(videoId, translatedTitle);

            response.put("success", true);
            response.put("message", "번역된 제목이 성공적으로 수정되었습니다.");
            response.put("videoId", videoId);
            response.put("translatedTitle", translatedTitle.trim().isEmpty() ? null : translatedTitle.trim());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("번역된 제목 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "번역된 제목 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 해외 기업 비디오 제목 번역 실행 API
     */
    @GetMapping("/videos/translate-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateOverseasVideoTitles() {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행하여 오래 걸리는 작업이 UI를 블로킹하지 않도록 함
            new Thread(() -> {
                try {
                    titleTranslationService.translateAllOverseasVideoTitles();
                } catch (Exception e) {
                    log.error("비디오 제목 번역 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "해외 기업 비디오 제목 번역 작업이 시작되었습니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 기업의 비디오 제목 번역 API
     */
    @GetMapping("/corporations/{corporationId}/translate-video-titles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> translateCorporationVideoTitles(@PathVariable Long corporationId) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 비동기로 실행
            new Thread(() -> {
                try {
                    titleTranslationService.translateCorporationVideoTitles(corporationId);
                } catch (Exception e) {
                    log.error("기업 {} 비디오 제목 번역 중 오류 발생", corporationId, e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "기업의 비디오 제목 번역 작업이 시작되었습니다.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "번역 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Industry 목록 조회 API
     */
    @GetMapping("/industries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getIndustries() {
        Map<String, Object> response = new HashMap<>();

        try {
            response.put("success", true);
            response.put("industries", industryRepository.findAll());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Industry 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Industry 생성 API
     */
    @PostMapping("/industries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> createIndustry(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String name = request.get("name");

            if (name == null || name.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "Industry 이름이 필요합니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 중복 체크
            if (industryRepository.existsByName(name.trim())) {
                response.put("success", false);
                response.put("message", "이미 존재하는 Industry입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            com.newcodes7.small_town.corporation.entity.Industry industry =
                com.newcodes7.small_town.corporation.entity.Industry.builder()
                    .name(name.trim())
                    .build();

            com.newcodes7.small_town.corporation.entity.Industry savedIndustry = industryRepository.save(industry);

            response.put("success", true);
            response.put("message", "Industry가 성공적으로 생성되었습니다.");
            response.put("industry", savedIndustry);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Industry 생성 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Industry 생성 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Industry 삭제 API
     * Industry를 삭제하면 cascade로 해당 Industry를 사용하는 모든 기업에서 자동으로 제거됩니다.
     */
    @DeleteMapping("/industries/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteIndustry(@PathVariable Integer id) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Industry 존재 확인
            com.newcodes7.small_town.corporation.entity.Industry industry =
                industryRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Industry입니다. ID: " + id));

            // Industry를 사용하는 기업 수 확인
            int corporationCount = industry.getCorporationIndustries().size();

            log.info("Industry 삭제 시작 - ID: {}, 이름: {}, 연결된 기업 수: {}",
                     id, industry.getName(), corporationCount);

            // Industry 삭제 (cascade로 CorporationIndustry도 자동 삭제됨)
            industryRepository.delete(industry);

            response.put("success", true);
            response.put("message", String.format("Industry '%s'가 성공적으로 삭제되었습니다. (%d개 기업에서 제거됨)",
                                                   industry.getName(), corporationCount));
            response.put("deletedIndustryName", industry.getName());
            response.put("affectedCorporationCount", corporationCount);

            log.info("Industry 삭제 완료 - ID: {}, 이름: {}", id, industry.getName());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Industry 삭제 실패 - 존재하지 않는 ID: {}", id);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Industry 삭제 중 오류 발생 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Industry 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Corporation의 Industry 수정 API
     */
    @PutMapping("/corporations/{corporationId}/industries")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateCorporationIndustries(
            @PathVariable Long corporationId,
            @RequestBody Map<String, Object> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            @SuppressWarnings("unchecked")
            List<Integer> industryIds = (List<Integer>) request.get("industryIds");

            if (industryIds == null) {
                industryIds = new ArrayList<>();
            }

            // CorporationUpdateDto 생성 (기존 정보 유지)
            CorporationResponseDto corporation = corporationService.getCorporationById(corporationId);
            CorporationUpdateDto updateDto = new CorporationUpdateDto();
            updateDto.setName(corporation.getName());
            updateDto.setHomeLink(corporation.getHomeLink());
            updateDto.setBlogLink(corporation.getBlogLink());
            updateDto.setCrewLink(corporation.getCrewLink());
            updateDto.setLogoUrl(corporation.getLogoUrl());
            updateDto.setYoutubeUrl(corporation.getYoutubeUrl());
            updateDto.setBaseUrl(corporation.getBaseUrl());
            updateDto.setArticle(corporation.getArticle());
            updateDto.setTitle(corporation.getTitle());
            updateDto.setLink(corporation.getLink());
            updateDto.setThumbnail(corporation.getThumbnail());
            updateDto.setPublish(corporation.getPublish());
            updateDto.setPublishFormat(corporation.getPublishFormat());
            updateDto.setIndustryIds(industryIds);

            corporationService.updateCorporation(corporationId, updateDto);

            response.put("success", true);
            response.put("message", "Industry가 성공적으로 수정되었습니다.");
            response.put("corporationId", corporationId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Corporation Industry 수정 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Industry 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 카테고리 삭제 API
     * 카테고리를 삭제하면 해당 카테고리를 가진 모든 Article과 Video의 category가 null로 설정됩니다.
     */
    @DeleteMapping("/categories/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteCategory(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 카테고리 존재 확인
            com.newcodes7.small_town.global.entity.Category category =
                categoryRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 카테고리입니다. ID: " + id));

            // 이미 삭제된 카테고리인지 확인
            if (category.getDeletedAt() != null) {
                response.put("success", false);
                response.put("message", "이미 삭제된 카테고리입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 해당 카테고리를 사용하는 Article 수 확인 및 category를 null로 설정
            List<Article> articlesWithCategory = articleRepository.findAll().stream()
                .filter(a -> a.getCategory() != null && a.getCategory().getId().equals(id) && a.getDeletedAt() == null)
                .toList();

            int articleCount = articlesWithCategory.size();
            for (Article article : articlesWithCategory) {
                article.setCategory(null);
            }
            articleRepository.saveAll(articlesWithCategory);

            // 해당 카테고리를 사용하는 Video 수 확인 및 category를 null로 설정
            List<Video> videosWithCategory = videoRepository.findAll().stream()
                .filter(v -> v.getCategory() != null && v.getCategory().getId().equals(id) && v.getDeletedAt() == null)
                .toList();

            int videoCount = videosWithCategory.size();
            for (Video video : videosWithCategory) {
                video.setCategory(null);
            }
            videoRepository.saveAll(videosWithCategory);

            log.info("카테고리 삭제 시작 - ID: {}, 이름: {}, 연결된 Article 수: {}, 연결된 Video 수: {}",
                     id, category.getName(), articleCount, videoCount);

            // 카테고리 soft delete
            com.newcodes7.small_town.global.entity.Category updatedCategory =
                com.newcodes7.small_town.global.entity.Category.builder()
                    .id(category.getId())
                    .name(category.getName())
                    .createdAt(category.getCreatedAt())
                    .updatedAt(category.getUpdatedAt())
                    .deletedAt(java.time.LocalDateTime.now())
                    .build();
            categoryRepository.save(updatedCategory);

            response.put("success", true);
            response.put("message", String.format("카테고리 '%s'가 성공적으로 삭제되었습니다. (Article %d개, Video %d개의 카테고리가 제거됨)",
                                                   category.getName(), articleCount, videoCount));
            response.put("deletedCategoryName", category.getName());
            response.put("affectedArticleCount", articleCount);
            response.put("affectedVideoCount", videoCount);

            log.info("카테고리 삭제 완료 - ID: {}, 이름: {}", id, category.getName());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("카테고리 삭제 실패 - 존재하지 않는 ID: {}", id);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("카테고리 삭제 중 오류 발생 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "카테고리 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Article Term 추출 및 저장 API
     * 모든 article의 title과 translatedTitle에서 형태소를 분석하여 term으로 저장
     *
     * GET /admin/articles/extract-terms
     */
    @GetMapping("/articles/extract-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractArticleTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article term 추출 요청 시작");

            // 비동기로 실행하여 오래 걸리는 작업이 UI를 블로킹하지 않도록 함
            new Thread(() -> {
                try {
                    ArticleTermService.ArticleTermExtractionResult result =
                            articleTermService.extractAndSaveAllArticleTerms();

                    log.info("Article term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedArticles(),
                            result.getSkippedArticles(),
                            result.getFailedArticles(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Article term 추출 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Article term 추출 작업이 시작되었습니다. 이미 term이 있는 article은 건너뜁니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 추출 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 추출 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Article term 강제 재분석 (배치 작업)
     * 모든 article에 대해 term을 강제로 다시 추출 (기존 term이 있어도 재분석)
     *
     * GET /admin/articles/reextract-all-terms
     */
    @GetMapping("/articles/reextract-all-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reextractAllArticleTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Article term 강제 재분석 요청 시작");

            // 비동기로 실행
            new Thread(() -> {
                try {
                    ArticleTermService.ArticleTermExtractionResult result =
                            articleTermService.extractAndSaveAllArticleTerms(true);

                    log.info("Article term 강제 재분석 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedArticles(),
                            result.getSkippedArticles(),
                            result.getFailedArticles(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Article term 강제 재분석 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Article term 강제 재분석 작업이 시작되었습니다. 모든 article을 재분석합니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 강제 재분석 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 강제 재분석 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Article의 Term 조회 API
     *
     * GET /admin/articles/{articleId}/terms
     */
    @GetMapping("/articles/{articleId}/terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticleTerms(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            List<com.newcodes7.small_town.global.entity.ArticleTerm> terms =
                    articleTermService.getArticleTerms(articleId);

            // DTO로 변환
            List<Map<String, Object>> termDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.ArticleTerm articleTerm : terms) {
                Map<String, Object> termData = new HashMap<>();
                termData.put("id", articleTerm.getId());
                termData.put("term", articleTerm.getTerm().getTerm());
                termData.put("termType", articleTerm.getTerm().getTermType());
                termData.put("frequency", articleTerm.getFrequency());
                termData.put("createdAt", articleTerm.getCreatedAt());
                termDataList.add(termData);
            }

            response.put("success", true);
            response.put("articleId", articleId);
            response.put("terms", termDataList);
            response.put("totalTerms", termDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article term 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Term 삭제 및 불용어 등록 API
     *
     * DELETE /admin/terms/{termId}
     */
    @DeleteMapping("/terms/{termId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteTerm(
            @PathVariable Long termId,
            @RequestBody(required = false) Map<String, String> request) {

        Map<String, Object> response = new HashMap<>();

        try {
            String reason = request != null ? request.get("reason") : null;

            int deletedCount = articleTermService.deleteTermAndAddToStopwords(termId, reason);

            response.put("success", true);
            response.put("message", String.format("Term이 삭제되고 불용어로 등록되었습니다. (ArticleTerm + VideoTerm 총 %d개 삭제)", deletedCount));
            response.put("deletedTermCount", deletedCount);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("Term 삭제 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Article의 Term 재분석 API
     *
     * POST /admin/articles/{articleId}/reanalyze-terms
     */
    @PostMapping("/articles/{articleId}/reanalyze-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reanalyzeArticleTerms(@PathVariable Long articleId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Article> articleOpt = articleRepository.findById(articleId);
            if (articleOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Article을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Article article = articleOpt.get();

            // Term 재분석 및 저장
            int termCount = articleTermService.extractAndSaveTermsForArticle(article);

            response.put("success", true);
            response.put("message", String.format("Term 재분석이 완료되었습니다. (%d개 추출)", termCount));
            response.put("articleId", articleId);
            response.put("termCount", termCount);

            log.info("Article ID {} term 재분석 완료: {} terms", articleId, termCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Article ID {} term 재분석 중 오류 발생: {}", articleId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 재분석 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 사용자 정의 단어 사전 목록 조회 API
     *
     * GET /admin/user-dictionary
     */
    @GetMapping("/user-dictionary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserDictionary() {
        Map<String, Object> response = new HashMap<>();

        try {
            List<com.newcodes7.small_town.global.entity.UserDictionary> userDictionaries =
                    userDictionaryRepository.findAllOrderByCreatedAtDesc();

            // DTO로 변환
            List<Map<String, Object>> dictDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.UserDictionary userDict : userDictionaries) {
                Map<String, Object> dictData = new HashMap<>();
                dictData.put("id", userDict.getId());
                dictData.put("word", userDict.getWord());
                dictData.put("posTag", userDict.getPosTag());
                dictData.put("reason", userDict.getReason());
                dictData.put("createdAt", userDict.getCreatedAt());
                dictDataList.add(dictData);
            }

            response.put("success", true);
            response.put("userDictionaries", dictDataList);
            response.put("totalCount", dictDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("사용자 사전 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "사용자 사전 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 사용자 정의 단어 추가 API
     *
     * POST /admin/user-dictionary
     */
    @PostMapping("/user-dictionary")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> addUserDictionary(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String word = request.get("word");
            String posTag = request.get("posTag");
            String reason = request.get("reason");

            if (word == null || word.trim().isEmpty()) {
                response.put("success", false);
                response.put("message", "단어는 필수 입력 항목입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 기본 품사는 NNG (일반명사)
            if (posTag == null || posTag.trim().isEmpty()) {
                posTag = "NNG";
            }

            // 중복 체크
            if (userDictionaryRepository.existsByWord(word.trim())) {
                response.put("success", false);
                response.put("message", "이미 등록된 단어입니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // 사용자 단어 저장
            com.newcodes7.small_town.global.entity.UserDictionary userDict =
                com.newcodes7.small_town.global.entity.UserDictionary.builder()
                    .word(word.trim())
                    .posTag(posTag.trim())
                    .reason(reason != null ? reason.trim() : null)
                    .build();

            com.newcodes7.small_town.global.entity.UserDictionary saved =
                    userDictionaryRepository.save(userDict);

            response.put("success", true);
            response.put("message", "사용자 단어가 등록되었습니다. 애플리케이션을 재시작하면 형태소 분석에 적용됩니다.");
            response.put("userDictionary", Map.of(
                "id", saved.getId(),
                "word", saved.getWord(),
                "posTag", saved.getPosTag(),
                "reason", saved.getReason() != null ? saved.getReason() : ""
            ));

            log.info("사용자 단어 등록: {} ({}), 사유: {}", word.trim(), posTag.trim(), reason);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("사용자 단어 등록 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "사용자 단어 등록 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 사용자 정의 단어 삭제 API
     *
     * DELETE /admin/user-dictionary/{id}
     */
    @DeleteMapping("/user-dictionary/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteUserDictionary(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            // UserDictionary 존재 확인
            com.newcodes7.small_town.global.entity.UserDictionary userDict =
                userDictionaryRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자 단어입니다. ID: " + id));

            String word = userDict.getWord();

            // UserDictionary 삭제
            userDictionaryRepository.delete(userDict);

            response.put("success", true);
            response.put("message", String.format("사용자 단어 '%s'가 삭제되었습니다. 애플리케이션을 재시작하면 형태소 분석에서 제외됩니다.", word));
            response.put("deletedWord", word);

            log.info("사용자 단어 삭제: {} (ID: {})", word, id);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("사용자 단어 삭제 실패 - 존재하지 않는 ID: {}", id);
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);

        } catch (Exception e) {
            log.error("사용자 단어 삭제 중 오류 발생 - ID: {}, 오류: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "사용자 단어 삭제 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Video Term 추출 및 저장 API
     *
     * GET /admin/videos/extract-terms
     */
    @GetMapping("/videos/extract-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> extractVideoTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Video term 추출 요청 시작");

            // 비동기로 실행
            new Thread(() -> {
                try {
                    VideoTermService.VideoTermExtractionResult result =
                            videoTermService.extractAndSaveAllVideoTerms();

                    log.info("Video term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedVideos(),
                            result.getSkippedVideos(),
                            result.getFailedVideos(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Video term 추출 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Video term 추출 작업이 시작되었습니다. 이미 term이 있는 video는 건너뜁니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video term 추출 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 추출 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Video term 강제 재분석 (배치 작업)
     * 모든 video에 대해 term을 강제로 다시 추출 (기존 term이 있어도 재분석)
     *
     * GET /admin/videos/reextract-all-terms
     */
    @GetMapping("/videos/reextract-all-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reextractAllVideoTerms() {
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Video term 강제 재분석 요청 시작");

            // 비동기로 실행
            new Thread(() -> {
                try {
                    VideoTermService.VideoTermExtractionResult result =
                            videoTermService.extractAndSaveAllVideoTerms(true);

                    log.info("Video term 강제 재분석 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                            result.getProcessedVideos(),
                            result.getSkippedVideos(),
                            result.getFailedVideos(),
                            result.getTotalTerms(),
                            result.getProcessingTimeMs());

                } catch (Exception e) {
                    log.error("Video term 강제 재분석 배치 작업 중 오류 발생", e);
                }
            }).start();

            response.put("success", true);
            response.put("message", "Video term 강제 재분석 작업이 시작되었습니다. 모든 video를 재분석합니다. 로그를 확인해주세요.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video term 강제 재분석 작업 시작 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 강제 재분석 작업 시작 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Video의 Term 조회 API
     *
     * GET /admin/videos/{videoId}/terms
     */
    @GetMapping("/videos/{videoId}/terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getVideoTerms(@PathVariable Long videoId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Video> videoOpt = videoRepository.findById(videoId);
            if (videoOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Video를 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            List<com.newcodes7.small_town.global.entity.VideoTerm> terms =
                    videoTermService.getVideoTerms(videoId);

            // DTO로 변환
            List<Map<String, Object>> termDataList = new ArrayList<>();
            for (com.newcodes7.small_town.global.entity.VideoTerm videoTerm : terms) {
                Map<String, Object> termData = new HashMap<>();
                termData.put("id", videoTerm.getId());
                termData.put("term", videoTerm.getTerm().getTerm());
                termData.put("termType", videoTerm.getTerm().getTermType());
                termData.put("frequency", videoTerm.getFrequency());
                termData.put("createdAt", videoTerm.getCreatedAt());
                termDataList.add(termData);
            }

            response.put("success", true);
            response.put("videoId", videoId);
            response.put("terms", termDataList);
            response.put("totalTerms", termDataList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video term 조회 중 오류 발생: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 조회 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 특정 Video의 Term 재분석 API
     *
     * POST /admin/videos/{videoId}/reanalyze-terms
     */
    @PostMapping("/videos/{videoId}/reanalyze-terms")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reanalyzeVideoTerms(@PathVariable Long videoId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Optional<Video> videoOpt = videoRepository.findById(videoId);
            if (videoOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Video를 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

            Video video = videoOpt.get();

            // Term 재분석 및 저장
            int termCount = videoTermService.extractAndSaveTermsForVideo(video);

            response.put("success", true);
            response.put("message", String.format("Term 재분석이 완료되었습니다. (%d개 추출)", termCount));
            response.put("videoId", videoId);
            response.put("termCount", termCount);

            log.info("Video ID {} term 재분석 완료: {} terms", videoId, termCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Video ID {} term 재분석 중 오류 발생: {}", videoId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Term 재분석 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}