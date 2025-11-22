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
import com.newcodes7.small_town.article.service.ArticleService;
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

        model.addAttribute("articles", articles);
        model.addAttribute("videos", videos);
        model.addAttribute("corporations", corporations);
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
}