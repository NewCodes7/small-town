package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.embedding.service.RelatedArticleService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.search.service.SearchLogService;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 아티클 도메인 Thymeleaf 페이지 렌더링 컨트롤러.
 * (목록/검색, 상세, 좋아요 페이지, 메인 홈, 소개)
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ArticlePageController {

    @Value("${app.base-url}")
    private String baseUrl;

    private final ArticleService articleService;
    private final ArticleSearchService articleSearchService;
    private final SemanticTermExpansionService semanticExpansionService;
    private final CorporationService corporationService;
    private final CategoryRepository categoryRepository;
    private final SearchLogService searchLogService;
    private final RelatedArticleService relatedArticleService;
    private final com.newcodes7.small_town.auth.repository.UserRepository userRepository;
    private final ArticleRepository articleRepository;
    private final com.newcodes7.small_town.theme.service.ThemeService themeService;
    private final com.newcodes7.small_town.search.service.SuggestedSearchTermService suggestedSearchTermService;

    @GetMapping("/articles")
    public String home(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "regions", required = false) List<String> regions,
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "category", required = false) List<String> category,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request,
            Model model) {

        int internalPage = Math.max(0, page - 1);

        // 검색 로그 저장
        if (keyword != null && !keyword.trim().isEmpty()) {
            User user = null;
            if (userDetails != null) {
                user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername()).orElse(null);
                if (user != null) {
                    log.info("검색 로그 저장: userId={}, keyword={}, type=ARTICLE", user.getId(), keyword.trim());
                }
            }
            searchLogService.logSearch(keyword.trim(), SearchLog.SearchType.ARTICLE, null, user, request);
        }

        // view 파라미터가 제공되지 않았을 때만 검색어에 따라 list로 강제 변경
        String effectiveView = view;
        if (effectiveView == null) {
            // view 파라미터가 없을 때 기본값 설정
            if (keyword != null && !keyword.trim().isEmpty()) {
                effectiveView = "list";
            } else {
                effectiveView = "grouped";
            }
        }

        // sort 파라미터 기본값 설정: 검색 시 적합도순, 일반 조회 시 최신순
        String effectiveSort = sort;
        if (effectiveSort == null) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                effectiveSort = "relevance";  // 검색 시 기본값: 적합도순
            } else {
                effectiveSort = "latest";      // 일반 조회 시 기본값: 최신순
            }
        }

        Page<ArticleResponseDto> articles;
        Map<String, Double> expandedTerms = null;
        Map<String, Double> directMatchTerms = new java.util.LinkedHashMap<>();
        Map<String, Double> synonymTerms = new java.util.LinkedHashMap<>();
        Map<String, Double> embeddingTerms = new java.util.LinkedHashMap<>();

        // Get username from UserDetails (null if not logged in)
        String username = userDetails != null ? userDetails.getUsername() : null;

        // 키워드 검색 시 Hybrid 검색 사용 (BM25 + Vector) 또는 따옴표 검색 (ILIKE + Vector)
        if (keyword != null && !keyword.trim().isEmpty() && effectiveView.equals("list")) {
            String trimmedKeyword = keyword.trim();
            String clientIp = Client.getClientIpAddress(request);

            // 따옴표 검색 감지: "키워드" 형식
            if (trimmedKeyword.startsWith("\"") && trimmedKeyword.endsWith("\"") && trimmedKeyword.length() > 2) {
                String exactKeyword = trimmedKeyword.substring(1, trimmedKeyword.length() - 1).toLowerCase();
                articles = articleSearchService.searchArticlesExactMatch(
                    exactKeyword,
                    regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                    category == null || category.isEmpty() ? null : category.stream().sorted().toList(),
                    internalPage,
                    size,
                    effectiveSort,
                    clientIp,
                    username
                ).map(dto -> (ArticleResponseDto) dto);
            } else {
                // 일반 Hybrid 검색 (BM25 + Vector)
                // 검색어 확장 정보 가져오기 (Admin 표시용)
                expandedTerms = semanticExpansionService.expandSearchTerms(trimmedKeyword.toLowerCase());

                // 확장된 검색어를 가중치별로 분류 (템플릿에서 사용)
                if (expandedTerms != null) {
                    for (Map.Entry<String, Double> entry : expandedTerms.entrySet()) {
                        double weight = entry.getValue();
                        if (weight == 1.0) {
                            directMatchTerms.put(entry.getKey(), entry.getValue());
                        } else if (weight == 0.8) {
                            synonymTerms.put(entry.getKey(), entry.getValue());
                        } else if (weight < 0.8) {
                            embeddingTerms.put(entry.getKey(), entry.getValue());
                        }
                    }
                }

                articles = articleSearchService.searchArticlesHybrid(
                    trimmedKeyword.toLowerCase(),
                    expandedTerms,
                    regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                    category == null || category.isEmpty() ? null : category.stream().sorted().toList(),
                    internalPage,
                    size,
                    effectiveSort,
                    clientIp,
                    username
                ).map(dto -> (ArticleResponseDto) dto);
            }
        } else {
            // 일반 목록 조회 또는 grouped view
            articles = articleService.getArticlesWithFilters(
                keyword != null && !keyword.trim().isEmpty() ? keyword.trim().toLowerCase() : null,
                regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                internalPage,
                size,
                effectiveSort,
                effectiveView,
                category == null || category.isEmpty() ? null : category.stream().sorted().toList()
            );
        }

        log.info("필터 조건: keyword='{}', regions={}, 조회된 글 {}개 / 전체 {}개",
                 keyword, regions, articles.getContent().size(), articles.getTotalElements());

        // 회사 목록 가져오기 (모든 회사 포함)
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(null, null, null, PageRequest.of(0, 50));
        List<CorporationResponseDto> corporationsWithLogos = corporations.getContent().stream()
            .limit(20)
            .toList();

        // 카테고리 목록 가져오기
        List<Category> categories = categoryRepository.findAll();

        log.info("로고가 있는 회사 수: {}", corporationsWithLogos.size());

        model.addAttribute("articles", articles);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("hasNext", articles.hasNext());
        model.addAttribute("hasPrevious", articles.hasPrevious());
        model.addAttribute("currentPage", internalPage);
        model.addAttribute("currentSort", effectiveSort);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedRegions", regions != null ? regions : new ArrayList<>());
        model.addAttribute("corporations", corporationsWithLogos);
        model.addAttribute("isGrouped", effectiveView.equals("grouped"));
        model.addAttribute("categories", categories);
        model.addAttribute("expandedTerms", expandedTerms);  // 확장된 검색어 전체 (Admin용)
        model.addAttribute("directMatchTerms", directMatchTerms);  // 직접 매칭 (weight 1.0)
        model.addAttribute("synonymTerms", synonymTerms);  // 유의어 (weight 0.8)
        model.addAttribute("embeddingTerms", embeddingTerms);  // 임베딩 유사어 (weight < 0.8)

        model.addAttribute("canonicalUrl", baseUrl + "/articles");
        return "home";
    }

    @GetMapping("/about")
    public String about(Model model) {
        long totalArticles = articleService.getTotalArticleCount();
        long totalCorporations = corporationService.getTotalCorporationCount();

        model.addAttribute("totalArticles", totalArticles);
        model.addAttribute("totalCorporations", totalCorporations);

        return "about";
    }

    @GetMapping("/articles/{id:\\d+}")
    public String articleDetailRedirect(@PathVariable Long id) {
        // /articles/123 -> /articles/123-slug 리다이렉트
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Article not found"));

        String slug = generateSlug(article);
        String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        return "redirect:/articles/" + id + "-" + encodedSlug;
    }

    @GetMapping("/articles/{id:\\d+}/{slug}")
    public String articleDetailOldFormat(@PathVariable Long id, @PathVariable String slug) {
        // 기존 /articles/123/slug 형식 -> 새 형식으로 리다이렉트
        Article article = articleRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Article not found"));

        String newSlug = generateSlug(article);
        String encodedSlug = URLEncoder.encode(newSlug, StandardCharsets.UTF_8);
        return "redirect:/articles/" + id + "-" + encodedSlug;
    }

    @GetMapping("/articles/{idSlug:\\d+-.*}")
    public String articleDetail(@PathVariable String idSlug, Model model, HttpServletResponse response) {
        // /articles/123-slug 형식
        int dashIndex = idSlug.indexOf('-');
        Long id = Long.parseLong(idSlug.substring(0, dashIndex));

        Article article = articleRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Article not found"));

        // Last-Modified 헤더 설정 (구글 신선도 신호)
        if (article.getPublishedAt() != null) {
            String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(article.getPublishedAt().atOffset(ZoneOffset.UTC));
            response.setHeader("Last-Modified", lastModified);
        }

        ArticleListResponseDto articleDto = new ArticleListResponseDto(article);
        model.addAttribute("article", articleDto);
        model.addAttribute("mainRepresentativeChunkInfo", relatedArticleService.getRepresentativeChunkInfo(id));

        String slug = generateSlug(article);
        String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        model.addAttribute("canonicalUrl", baseUrl + "/articles/" + id + "-" + encodedSlug);
        return "article-detail";
    }

    private String generateSlug(Article article) {
        // 번역된 제목이 있으면 사용, 없으면 원본 제목 사용
        String title = article.getTranslatedTitle() != null ?
            article.getTranslatedTitle() : article.getTitle();

        String slug = title.toLowerCase()
            .replaceAll("[^a-z0-9가-힣\\s-]", "") // 특수문자 제거 (한글, 영문, 숫자, 공백, 하이픈만 남김)
            .replaceAll("\\s+", "-")               // 공백을 하이픈으로
            .replaceAll("-+", "-")                 // 연속된 하이픈을 하나로
            .replaceAll("^-|-$", "");              // 앞뒤 하이픈 제거

        // 최대 100자로 제한
        if (slug.length() > 100) {
            slug = slug.substring(0, 100);
            // 마지막 하이픈 제거
            slug = slug.replaceAll("-$", "");
        }

        return slug;
    }

    // 좋아요 페이지 렌더링
    @GetMapping("/liked-articles")
    public String likedArticles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails,
            Model model) {

        model.addAttribute("currentPage", page);
        model.addAttribute("pageSize", size);
        model.addAttribute("isAuthenticated", userDetails != null);

        return "liked-articles";
    }

    /**
     * 새로운 홈 페이지
     */
    @GetMapping({"", "/"})
    public String newHome(Model model, @AuthenticationPrincipal UserDetails userDetails, HttpServletRequest request) {
        // 기업별 최신 글 1개씩, 최대 8개 (DB 레벨 DISTINCT ON으로 버퍼 크기 무관하게 항상 8개 보장)
        List<ArticleListResponseDto> latestArticles = articleService.getHomeLatestArticles(8, 0);
        long articleCorporationCount = articleService.getArticleCorporationCount();

        // 회사 목록 가져오기 (로고용)
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(
            null, null, null, PageRequest.of(0, 50)
        );
        List<CorporationResponseDto> corporationsWithLogos = corporations.getContent().stream()
            .limit(20)
            .toList();

        // 전체 글 수
        long totalElements = articleService.getTotalArticleCount();

        // 활성화된 테마 목록 조회 (최대 8개)
        List<com.newcodes7.small_town.theme.dto.ThemeResponseDto> themes = themeService.getActiveThemes()
            .stream()
            .limit(8)
            .toList();

        // 이번 주 인기글 조회 (최근 7일, 조회수 순, 최대 8개)
        List<ArticleListResponseDto> popularArticles = articleService.getWeeklyPopularArticles(8);

        model.addAttribute("articles", latestArticles);
        model.addAttribute("hasMoreLatestArticles", articleCorporationCount > 8);
        model.addAttribute("corporations", corporationsWithLogos);
        model.addAttribute("totalElements", totalElements);
        model.addAttribute("themes", themes);
        model.addAttribute("popularArticles", popularArticles);
        model.addAttribute("suggestedKeywords", suggestedSearchTermService.getActiveKeywords());

        model.addAttribute("canonicalUrl", baseUrl + "/");
        return "new-home";
    }
}
