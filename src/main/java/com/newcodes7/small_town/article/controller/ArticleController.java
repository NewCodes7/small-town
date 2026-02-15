package com.newcodes7.small_town.article.controller;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.dto.TermAutocompleteDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.TermAutocompleteRepository;
import com.newcodes7.small_town.corporation.repository.CorporationAutocompleteRepository;
import com.newcodes7.small_town.corporation.dto.CorporationAutocompleteDto;
import com.newcodes7.small_town.theme.repository.ThemeAutocompleteRepository;
import com.newcodes7.small_town.theme.dto.ThemeAutocompleteDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.article.service.ArticleLikeService;
import com.newcodes7.small_town.article.service.SemanticTermExpansionService;
import com.newcodes7.small_town.article.service.UserLikeService;
import com.newcodes7.small_town.article.service.ArticleViewService;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.service.SearchLogService;
import com.newcodes7.small_town.global.repository.SearchLogRepository;
import com.newcodes7.small_town.theme.entity.Theme;
import com.newcodes7.small_town.theme.repository.ThemeRepository;
import com.newcodes7.small_town.embedding.service.RelatedArticleService;
import com.newcodes7.small_town.article.dto.RelatedArticleDto;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;

import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;
    @Qualifier("autocompleteExecutor")
    private final ExecutorService autocompleteExecutor;
    private final ArticleLikeService likeService;
    private final UserLikeService userLikeService;
    private final ArticleViewService viewService;
    private final CorporationService corporationService;
    private final CategoryRepository categoryRepository;
    private final VideoRepository videoRepository;
    private final IndustryRepository industryRepository;
    private final ArticleTermRepository articleTermRepository;
    private final TermAutocompleteRepository termAutocompleteRepository;
    private final SearchLogService searchLogService;
    private final SearchLogRepository searchLogRepository;
    private final ArticleRepository articleRepository;
    private final com.newcodes7.small_town.auth.repository.UserRepository userRepository;
    private final com.newcodes7.small_town.article.repository.LikeLogRepository likeLogRepository;
    private final com.newcodes7.small_town.video.repository.VideoLikeLogRepository videoLikeLogRepository;
    private final SemanticTermExpansionService semanticExpansionService;
    private final com.newcodes7.small_town.video.service.VideoService videoService;
    private final com.newcodes7.small_town.theme.service.ThemeService themeService;
    private final ThemeRepository themeRepository;
    private final CorporationAutocompleteRepository corporationAutocompleteRepository;
    private final ThemeAutocompleteRepository themeAutocompleteRepository;
    private final RelatedArticleService relatedArticleService;

    @GetMapping("/articles")
    public String home(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "regions", required = false) List<String> regions,
            @RequestParam(name = "view", required = false) String view,
            @RequestParam(name = "category", required = false) List<String> category,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request,
            Model model) {

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

        // 키워드 검색 시 Hybrid 검색 사용 (BM25 + Summary Vector + RRF)
        if (keyword != null && !keyword.trim().isEmpty() && effectiveView.equals("list")) {
            // 검색어 확장 정보 가져오기 (Admin 표시용)
            expandedTerms = semanticExpansionService.expandSearchTerms(keyword.trim().toLowerCase());

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

            // Hybrid 검색 수행 (BM25 + ILIKE + Binary Boost)
            String clientIp = com.newcodes7.small_town.global.util.Client.getClientIpAddress(request);
            articles = articleService.searchArticlesHybrid(
                keyword.trim().toLowerCase(),
                expandedTerms,
                regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                category == null || category.isEmpty() ? null : category.stream().sorted().toList(),
                page,
                size,
                effectiveSort,
                clientIp,
                username
            ).map(dto -> (ArticleResponseDto) dto);
        } else {
            // 일반 목록 조회 또는 grouped view
            articles = articleService.getArticlesWithFilters(
                keyword != null && !keyword.trim().isEmpty() ? keyword.trim().toLowerCase() : null,
                regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                page,
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

        // 인기글 5개 가져오기 (배너용)
        // Page<ArticleListResponseDto> popularArticles = articleService.getArticleList(0, 5, "popular");

        // 카테고리 목록 가져오기
        List<Category> categories = categoryRepository.findAll();

        log.info("로고가 있는 회사 수: {}", corporationsWithLogos.size());
        // log.info("인기글 수: {}", popularArticles.getContent().size());

        model.addAttribute("articles", articles);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("hasNext", articles.hasNext());
        model.addAttribute("hasPrevious", articles.hasPrevious());
        model.addAttribute("currentPage", page);
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
        // model.addAttribute("popularArticles", popularArticles.getContent());

        return "home";
    }
    
    @GetMapping("/api/articles")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getArticlesWithFilters(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> regions,
            @RequestParam(defaultValue = "list") String view,
            @RequestParam(name = "category", required = false) List<String> category,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request
        ) {

        Page<ArticleResponseDto> articles = articleService.getArticlesWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim().toLowerCase() : null,
            regions == null || regions.isEmpty() ? null : regions,
            page,
            size,
            sort,
            view,
            category == null || category.isEmpty() ? null : category
        );

        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("currentPage", page);
        response.put("totalPages", articles.getTotalPages());
        response.put("totalElements", articles.getTotalElements());
        response.put("hasNext", articles.hasNext());
        response.put("hasPrevious", articles.hasPrevious());
        response.put("currentSort", sort);
        response.put("keyword", keyword);
        response.put("selectedRegions", regions != null ? regions : new ArrayList<>());
        response.put("view", view);

        return ResponseEntity.ok(response);
    }

    // @GetMapping("/api/articles/grouped")
    // @ResponseBody
    // public ResponseEntity<List<GroupedArticlesDto>> getArticlesGroupedByCorporation() {
    //     Map<Corporation, List<Article>> groupedArticles = articleService.getArticlesGroupedByCorporation();

    //     List<GroupedArticlesDto> result = groupedArticles.entrySet().stream()
    //             .map(entry -> new GroupedArticlesDto(
    //                     new CorporationDto(entry.getKey()),
    //                     entry.getValue().stream()
    //                             .map(ArticleListResponseDto::new)
    //                             .collect(Collectors.toList())
    //             ))
    //             .collect(Collectors.toList());

    //     return ResponseEntity.ok(result);
    // }
    
    @PostMapping("/api/articles/{articleId}/like")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleLike(@PathVariable Long articleId,
                                                         @AuthenticationPrincipal UserDetails userDetails,
                                                         HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean isLiked;

        if (userDetails != null) {
            // 인증된 사용자
            isLiked = userLikeService.toggleLike(articleId, userDetails.getUsername());
        } else {
            // 익명 사용자 (IP 기반)
            isLiked = userLikeService.toggleLikeByIp(articleId, ipAddress);
        }

        long likeCount = userLikeService.getLikeCount(articleId);

        Map<String, Object> response = new HashMap<>();
        response.put("isLiked", isLiked);
        response.put("likeCount", likeCount);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/articles/{articleId}/like-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikeStatus(@PathVariable Long articleId,
                                                           @AuthenticationPrincipal UserDetails userDetails,
                                                           HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean hasLiked = false;

        if (userDetails != null) {
            // 인증된 사용자
            hasLiked = userLikeService.hasLiked(articleId, userDetails.getUsername());
        } else {
            // 익명 사용자 (IP 기반)
            hasLiked = userLikeService.hasLikedByIp(articleId, ipAddress);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("hasLiked", hasLiked);
        response.put("authenticated", userDetails != null);

        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/api/articles/{articleId}/view")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> incrementViewCount(@PathVariable Long articleId,
                                                                @AuthenticationPrincipal UserDetails userDetails,
                                                                HttpServletRequest request) {
        String ipAddress = Client.getClientIpAddress(request);
        boolean incremented;
        
        if (userDetails != null) {
            // 인증된 사용자
            incremented = viewService.incrementViewCount(articleId, userDetails.getUsername(), ipAddress);
        } else {
            // 익명 사용자 (IP 기반)
            incremented = viewService.incrementViewCountByIp(articleId, ipAddress);
        }
        
        long viewCount = viewService.getViewCount(articleId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("incremented", incremented);
        response.put("authenticated", userDetails != null);
        
        return ResponseEntity.ok(response);
    }

    /**
     * 관련 글 추천 API
     * 대표 Chunk의 embedding 유사도를 기반으로 관련 글 추천
     *
     * @param articleId Article ID
     * @param limit 결과 수 (기본 3)
     * @return 관련 글 목록
     */
    @GetMapping("/api/articles/{articleId}/related")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRelatedArticles(
            @PathVariable Long articleId,
            @RequestParam(defaultValue = "3") Integer limit) {

        List<RelatedArticleDto> relatedArticles = relatedArticleService.getRelatedArticles(articleId, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("relatedArticles", relatedArticles);
        response.put("count", relatedArticles.size());

        return ResponseEntity.ok(response);
    }

    // @GetMapping("/api/articles/{articleId}/view-status")
    // @ResponseBody
    // public ResponseEntity<Map<String, Object>> getViewStatus(@PathVariable Long articleId,
    //                                                        @AuthenticationPrincipal UserDetails userDetails,
    //                                                        HttpServletRequest request) {
    //     String ipAddress = getClientIpAddress(request);
    //     long viewCount = viewService.getViewCount(articleId);
        
    //     Map<String, Object> response = new HashMap<>();
    //     response.put("viewCount", viewCount);
    //     response.put("authenticated", userDetails != null);
        
    //     // 쿨다운 정보 추가
    //     if (userDetails != null) {
    //         viewService.getLastViewTime(articleId, userDetails.getUsername())
    //             .ifPresent(lastViewTime -> {
    //                 response.put("lastViewTime", lastViewTime);
    //                 response.put("canView", lastViewTime.plusMinutes(30).isBefore(java.time.LocalDateTime.now()));
    //             });
    //     } else {
    //         viewService.getLastViewTimeByIp(articleId, ipAddress)
    //             .ifPresent(lastViewTime -> {
    //                 response.put("lastViewTime", lastViewTime);
    //                 response.put("canView", lastViewTime.plusMinutes(30).isBefore(java.time.LocalDateTime.now()));
    //             });
    //     }
        
    //     return ResponseEntity.ok(response);
    // }
    
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
    public String articleDetail(@PathVariable String idSlug, Model model) {
        // /articles/123-slug 형식
        int dashIndex = idSlug.indexOf('-');
        Long id = Long.parseLong(idSlug.substring(0, dashIndex));

        Article article = articleRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Article not found"));

        ArticleListResponseDto articleDto = new ArticleListResponseDto(article);
        model.addAttribute("article", articleDto);

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

    @GetMapping("/corporations")
    public String corporations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) List<Integer> industries,
            @RequestParam(defaultValue = "popular") String sort,
            Model model) {

        // 정렬 옵션 설정
        Sort sortOption;
        if ("popular".equals(sort)) {
            // 인기순: 조회수 내림차순, 이름 오름차순
            sortOption = Sort.by(
                Sort.Order.desc("viewCount"),
                Sort.Order.asc("name")
            );
        } else {
            // 이름순: 이름 오름차순
            sortOption = Sort.by(Sort.Order.asc("name"));
        }

        // 통합 필터링 메서드 호출
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(
                search, filter, industries, PageRequest.of(page, size, sortOption)
        );
        
        // Statistics
        long totalCorporations = corporationService.getTotalCorporationCount();
        long totalArticles = articleService.getTotalArticleCount();
        long totalVideos = videoRepository.countByDeletedAtIsNull();

        long domesticCount = totalCorporations;
        long overseasCount = 0;
        
        // Industry 목록 조회
        List<Industry> allIndustries = industryRepository.findAll();

        model.addAttribute("corporations", corporations);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", corporations.getTotalPages());
        model.addAttribute("totalElements", corporations.getTotalElements());
        model.addAttribute("hasNext", corporations.hasNext());
        model.addAttribute("hasPrevious", corporations.hasPrevious());
        model.addAttribute("currentSearch", search);
        model.addAttribute("currentFilter", filter);
        model.addAttribute("currentSort", sort);
        model.addAttribute("selectedIndustries", industries);
        model.addAttribute("allIndustries", allIndustries);

        // Statistics
        model.addAttribute("totalCorporations", totalCorporations);
        model.addAttribute("domesticCount", domesticCount);
        model.addAttribute("overseasCount", overseasCount);
        model.addAttribute("totalArticles", totalArticles);
        model.addAttribute("totalVideos", totalVideos);

        return "corporations";
    }

    @GetMapping("/corporations/{corporationId}")
    public String corporationDetail(@PathVariable Long corporationId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  @RequestParam(required = false) String keyword,
                                  @AuthenticationPrincipal UserDetails userDetails,
                                  HttpServletRequest request,
                                  Model model) {
        CorporationDetailDto corporation = articleService.getCorporationDetail(corporationId);

        // 검색 로그 저장 (keyword 파라미터가 있고 로그인한 경우)
        if (keyword != null && !keyword.trim().isEmpty() && userDetails != null) {
            User user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername()).orElse(null);
            if (user != null) {
                log.info("검색 로그 저장: userId={}, keyword={}, type=CORPORATION, targetId={}",
                    user.getId(), keyword.trim(), corporationId);
                searchLogService.logSearch(keyword.trim(), SearchLog.SearchType.CORPORATION, corporationId, user, request);
            }
        }

        Page<ArticleListResponseDto> articles = articleService.getArticlesByCorporation(corporationId, page, size);

        model.addAttribute("corporation", corporation);
        model.addAttribute("articles", articles);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());

        return "corporation-detail";
    }
    
    @DeleteMapping("/api/admin/articles/{articleId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteArticle(
            @PathVariable Long articleId,
            @AuthenticationPrincipal UserDetails userDetails) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // 관리자 권한 확인
            if (userDetails == null || !isAdmin(userDetails)) {
                response.put("status", "error");
                response.put("message", "관리자 권한이 필요합니다.");
                return ResponseEntity.status(403).body(response);
            }
            
            // 게시글 삭제
            articleService.deleteArticle(articleId);
            
            response.put("status", "success");
            response.put("message", "게시글이 성공적으로 삭제되었습니다.");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("게시글 삭제 중 오류 발생: {}", e.getMessage(), e);
            
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 관리자용 글 발행일 수정 API
     */
    @PutMapping("/api/admin/articles/{articleId}/publish-date")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateArticlePublishDate(
            @PathVariable Long articleId,
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal UserDetails userDetails) {

        Map<String, Object> response = new HashMap<>();

        // 관리자 권한 확인
        if (userDetails == null || !isAdmin(userDetails)) {
            response.put("status", "error");
            response.put("message", "관리자 권한이 필요합니다.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        }

        try {
            String publishedAtStr = request.get("publishedAt");
            if (publishedAtStr == null || publishedAtStr.trim().isEmpty()) {
                response.put("status", "error");
                response.put("message", "발행일이 제공되지 않았습니다.");
                return ResponseEntity.badRequest().body(response);
            }

            // LocalDateTime으로 파싱 (datetime-local 형식: yyyy-MM-ddTHH:mm)
            LocalDateTime publishedAt = LocalDateTime.parse(publishedAtStr);

            // 글 발행일 업데이트
            boolean updated = articleService.updateArticlePublishDate(articleId, publishedAt);

            if (updated) {
                response.put("status", "success");
                response.put("message", "발행일이 성공적으로 수정되었습니다.");
                return ResponseEntity.ok(response);
            } else {
                response.put("status", "error");
                response.put("message", "글을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }

        } catch (DateTimeParseException e) {
            response.put("status", "error");
            response.put("message", "올바르지 않은 날짜 형식입니다.");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("글 발행일 수정 중 오류 발생: {}", e.getMessage(), e);

            response.put("status", "error");
            response.put("message", "발행일 수정 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/api/user-info")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getUserInfo(
            @AuthenticationPrincipal UserDetails userDetails) {
        
        Map<String, Object> response = new HashMap<>();
        
        if (userDetails == null) {
            response.put("authenticated", false);
            response.put("isAdmin", false);
        } else {
            response.put("authenticated", true);
            response.put("isAdmin", isAdmin(userDetails));
            response.put("username", userDetails.getUsername());
        }
        
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/api/categories")
    @ResponseBody
    public ResponseEntity<List<Category>> getAllCategories() {
        List<Category> categories = categoryRepository.findAll();
        return ResponseEntity.ok(categories);
    }

    /**
     * 자동완성 검색어 API
     * 사용자 입력값으로 시작하는 term, corporation, theme을 빈도수 순으로 반환
     *
     * GET /api/autocomplete?q=검색어
     *
     * 응답 형식 (순서 보존, 크기 최적화):
     * - Corporation: [0, name, id, logoUrl]
     * - Theme: [1, id, name]
     * - Term: "termString"
     */
    @GetMapping("/api/autocomplete")
    @ResponseBody
    public ResponseEntity<List<Object>> getAutocompleteSuggestions(
            @RequestParam(name = "q", required = false) String query) {

        long startTime = System.currentTimeMillis();
        List<Object> suggestions = new ArrayList<>();

        if (query == null || query.trim().isEmpty() || query.trim().length() < 1) {
            return ResponseEntity.ok(suggestions);
        }

        String trimmedQuery = query.trim().toLowerCase();

        // 검색어를 자모 분해 (예: "프로제" → "ㅍㅡㄹㅗㅈㅔ", "톳" → "ㅌㅗㅅ")
        String decomposedQuery = KoreanCharacterUtil.decomposeHangul(trimmedQuery);
        long prepTime = System.currentTimeMillis();
        log.debug("[자동완성] 준비 시간: {}ms", prepTime - startTime);

        // ===== 병렬 검색 (CompletableFuture 사용) =====
        long parallelStartTime = System.currentTimeMillis();

        // 검색 패턴 준비 (JdbcTemplate용)
        String query1Param = trimmedQuery + "%";
        String query2Param = decomposedQuery + "%";

        // 1. Corporation 검색 (비동기, JdbcTemplate) - JPA 우회로 10배 이상 빠름
        CompletableFuture<List<Object>> corporationFuture = CompletableFuture.supplyAsync(() -> {
            long corpStartTime = System.currentTimeMillis();
            List<Object> corpResults = new ArrayList<>();

            // JdbcTemplate 기반 초고속 검색
            List<CorporationAutocompleteDto> corporations = corporationAutocompleteRepository
                .findAutocompleteCorporationsWithTwoPatterns(query1Param, query2Param, 2);

            for (CorporationAutocompleteDto corp : corporations) {
                String displayName = corp.getDisplayName(decomposedQuery);
                // [0, name, id, logoUrl] - 명시적 배열 사용
                corpResults.add(new Object[]{0, displayName, corp.getId(), corp.getEffectiveLogoUrl()});
            }

            long corpTime = System.currentTimeMillis() - corpStartTime;
            log.debug("[자동완성] Corporation 검색 (JDBC): {}ms", corpTime);
            return corpResults;
        }, autocompleteExecutor).exceptionally(ex -> {
            log.error("[자동완성] Corporation 검색 실패", ex);
            return new ArrayList<>();
        });

        // 2. Theme 검색 (비동기, JdbcTemplate) - JPA 우회로 10배 이상 빠름
        CompletableFuture<List<Object>> themeFuture = CompletableFuture.supplyAsync(() -> {
            long themeStartTime = System.currentTimeMillis();
            List<Object> themeResults = new ArrayList<>();

            // JdbcTemplate 기반 초고속 검색 (단순 버전 사용)
            List<ThemeAutocompleteDto> themes = themeAutocompleteRepository
                .findAutocompleteThemesSimple(query1Param, query2Param, 2);

            for (ThemeAutocompleteDto theme : themes) {
                // [1, id, name] - 명시적 배열 사용
                themeResults.add(new Object[]{1, theme.getId(), theme.getName()});
            }

            long themeTime = System.currentTimeMillis() - themeStartTime;
            log.debug("[자동완성] Theme 검색 (JDBC): {}ms", themeTime);
            return themeResults;
        }, autocompleteExecutor).exceptionally(ex -> {
            log.error("[자동완성] Theme 검색 실패", ex);
            return new ArrayList<>();
        });

        // 3. Term 검색 (비동기, Covering Index 최적화) - 전용 executor 사용으로 cold start 방지
        CompletableFuture<List<Object>> termFuture = CompletableFuture.supplyAsync(() -> {
            long termStartTime = System.currentTimeMillis();
            List<Object> termResults = new ArrayList<>();

            // 한글 검색 패턴 생성 (예: "프롲" → ["프롲", "프로ㅈ"])
            List<String> searchPatterns = KoreanCharacterUtil.generateSearchPatterns(trimmedQuery);
            
            // Term 검색 (최대 4개)
            String termQuery = decomposedQuery.toLowerCase() + "%";
            List<TermAutocompleteDto> termDtos = termAutocompleteRepository.findAutocompleteTerms(
                termQuery, 4);

            for (TermAutocompleteDto termDto : termDtos) {
                // Just the term string
                termResults.add(termDto.getTerm());
            }

            long termTime = System.currentTimeMillis() - termStartTime;
            log.debug("[자동완성] Term 검색: {}ms", termTime);
            return termResults;
        }, autocompleteExecutor).exceptionally(ex -> {
            log.error("[자동완성] Term 검색 실패", ex);
            return new ArrayList<>();
        });

        // 모든 비동기 작업 완료 대기 및 결과 합치기
        CompletableFuture.allOf(corporationFuture, themeFuture, termFuture).join();

        // 순서대로 결과 추가 (Corporation → Theme → Term)
        suggestions.addAll(corporationFuture.join());
        suggestions.addAll(themeFuture.join());
        suggestions.addAll(termFuture.join());

        long parallelTime = System.currentTimeMillis() - parallelStartTime;
        long totalTime = System.currentTimeMillis() - startTime;
        log.info("[자동완성] 총 실행 시간: {}ms (준비: {}ms, 병렬검색: {}ms) - 검색어: '{}'",
                totalTime,
                prepTime - startTime,
                parallelTime,
                query);

        return ResponseEntity.ok(suggestions);
    }

    /**
     * 사용자별 검색 기록 조회 API
     * 로그인한 사용자의 최근 검색 기록을 반환 (최대 10개)
     *
     * GET /api/search-history
     */
    @GetMapping("/api/search-history")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getSearchHistory(
            @AuthenticationPrincipal UserDetails userDetails) {

        List<Map<String, Object>> history = new ArrayList<>();

        if (userDetails == null) {
            log.debug("검색 기록 조회: 사용자 미인증");
            return ResponseEntity.ok(history);
        }

        log.debug("검색 기록 조회: username={}", userDetails.getUsername());

        // 사용자 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername())
            .orElse(null);

        if (user == null) {
            log.warn("검색 기록 조회: 사용자를 찾을 수 없음 username={}", userDetails.getUsername());
            return ResponseEntity.ok(history);
        }

        log.debug("검색 기록 조회: 사용자 찾음 userId={}", user.getId());

        // 최근 검색 기록 조회 (최대 50개 가져와서 중복 제거 후 10개)
        PageRequest pageRequest = PageRequest.of(0, 50);
        List<SearchLog> searchLogs = searchLogRepository.findRecentSearchesByUser(user, pageRequest);

        log.info("검색 기록 조회: userId={}, 총 검색 로그 수={}", user.getId(), searchLogs.size());

        // 중복 제거 (keyword + type + targetId 조합으로)
        Set<String> seen = new LinkedHashSet<>();
        for (SearchLog searchLog : searchLogs) {
            String key = searchLog.getSearchKeyword() + "|" + searchLog.getSearchType() + "|" + searchLog.getTargetId();

            if (!seen.contains(key) && seen.size() < 10) {
                seen.add(key);

                Map<String, Object> item = new HashMap<>();
                item.put("type", searchLog.getSearchType().name().toLowerCase());
                item.put("keyword", searchLog.getSearchKeyword());

                if (searchLog.getTargetId() != null) {
                    item.put("id", searchLog.getTargetId());
                }

                history.add(item);
                log.debug("검색 기록 추가: keyword={}, type={}, targetId={}",
                    searchLog.getSearchKeyword(), searchLog.getSearchType(), searchLog.getTargetId());
            }
        }

        log.info("검색 기록 조회 완료: userId={}, 반환 개수={}", user.getId(), history.size());

        return ResponseEntity.ok(history);
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

    // 로그인 사용자 좋아요 목록 API (아티클 + 비디오 통합)
    @GetMapping("/api/liked-items")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikedItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // email, oauthUsername, oauthProviderId 중 하나로 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername())
            .orElse(null);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 아티클 좋아요 조회
        java.util.List<com.newcodes7.small_town.article.entity.LikeLog> articleLikes =
            likeLogRepository.findLikedArticlesWithTimestampByUserId(user.getId());

        // 비디오 좋아요 조회
        java.util.List<com.newcodes7.small_town.video.entity.VideoLikeLog> videoLikes =
            videoLikeLogRepository.findLikedVideosWithTimestampByUserId(user.getId());

        // LikedItemDto로 변환
        java.util.List<com.newcodes7.small_town.article.dto.LikedItemDto> allItems = new java.util.ArrayList<>();

        for (com.newcodes7.small_town.article.entity.LikeLog like : articleLikes) {
            allItems.add(new com.newcodes7.small_town.article.dto.LikedItemDto(
                like.getArticle(), like.getCreatedAt()));
        }

        for (com.newcodes7.small_town.video.entity.VideoLikeLog like : videoLikes) {
            allItems.add(new com.newcodes7.small_town.article.dto.LikedItemDto(
                like.getVideo(), like.getCreatedAt()));
        }

        // 좋아요 시간순으로 정렬 (최신순)
        allItems.sort((a, b) -> b.getLikedAt().compareTo(a.getLikedAt()));

        // 페이지네이션 적용
        int start = page * size;
        int end = Math.min(start + size, allItems.size());
        java.util.List<com.newcodes7.small_town.article.dto.LikedItemDto> pagedItems =
            start < allItems.size() ? allItems.subList(start, end) : java.util.Collections.emptyList();

        Map<String, Object> response = new HashMap<>();
        response.put("content", pagedItems);
        response.put("currentPage", page);
        response.put("totalPages", (int) Math.ceil((double) allItems.size() / size));
        response.put("totalElements", allItems.size());
        response.put("hasNext", end < allItems.size());
        response.put("hasPrevious", page > 0);

        return ResponseEntity.ok(response);
    }

    // 로그인 사용자 좋아요 목록 API (아티클만 - 하위 호환성)
    @GetMapping("/api/articles/liked")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLikedArticles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Page<ArticleListResponseDto> articles = userLikeService.getLikedArticles(
            userDetails.getUsername(), page, size);

        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("currentPage", page);
        response.put("totalPages", articles.getTotalPages());
        response.put("totalElements", articles.getTotalElements());
        response.put("hasNext", articles.hasNext());
        response.put("hasPrevious", articles.hasPrevious());

        return ResponseEntity.ok(response);
    }

    // ID 배치 조회 API (비로그인 사용자용)
    @GetMapping("/api/articles/batch")
    @ResponseBody
    public ResponseEntity<List<ArticleListResponseDto>> getArticlesByIds(
            @RequestParam List<Long> ids) {

        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }

        // 최대 100개로 제한
        List<Long> limitedIds = ids.stream().limit(100).collect(java.util.stream.Collectors.toList());

        List<Article> articles = articleRepository.findAllByIdIn(limitedIds);
        List<ArticleListResponseDto> dtos = articles.stream()
            .map(ArticleListResponseDto::new)
            .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    // localStorage 좋아요 마이그레이션 API
    @PostMapping("/api/articles/migrate-likes")
    @ResponseBody
    public ResponseEntity<com.newcodes7.small_town.article.dto.MigrationResultDto> migrateLikesFromLocalStorage(
            @RequestBody List<Long> articleIds,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        com.newcodes7.small_town.article.dto.MigrationResultDto result =
            userLikeService.migrateLikesFromLocalStorage(userDetails.getUsername(), articleIds);

        return ResponseEntity.ok(result);
    }

    /**
     * 어드민용 검색 API (기존 home() 로직 사용)
     */
    @GetMapping("/api/admin/articles/search")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> searchArticlesForAdmin(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "sort", defaultValue = "relevance") String sort,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {

        // 기존 home()과 동일한 검색 로직 사용
        Page<? extends ArticleResponseDto> articles;
        String username = userDetails != null ? userDetails.getUsername() : null;

        if (keyword != null && !keyword.trim().isEmpty()) {
            // Hybrid 검색 사용 (BM25 + ILIKE + Binary Boost)
            String clientIp = com.newcodes7.small_town.global.util.Client.getClientIpAddress(request);
            articles = articleService.searchArticlesHybrid(
                keyword.trim().toLowerCase(),
                null,  // regions
                null,  // category
                page,
                size,
                sort,
                clientIp,
                username
            );
        } else {
            // 키워드 없으면 최신순 조회
            articles = articleService.getArticlesWithFilters(
                null,
                null,
                page,
                size,
                "latest",
                "list",
                null
            );
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("totalElements", articles.getTotalElements());
        response.put("totalPages", articles.getTotalPages());
        response.put("currentPage", page);
        response.put("hasNext", articles.hasNext());

        return ResponseEntity.ok(response);
    }

    /**
     * 배치 좋아요 상태 조회 API (Article)
     */
    @PostMapping("/api/articles/like-status/batch")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getBatchArticleLikeStatus(
            @RequestBody Map<String, List<Long>> request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {

        List<Long> articleIds = request.get("articleIds");
        if (articleIds == null || articleIds.isEmpty()) {
            return ResponseEntity.ok(Map.of("likeStatus", Map.of()));
        }

        String clientIp = com.newcodes7.small_town.global.util.Client.getClientIpAddress(httpRequest);
        String username = userDetails != null ? userDetails.getUsername() : null;

        Map<Long, Boolean> likeStatusMap = articleService.getLikeStatusMap(articleIds, username, clientIp);

        return ResponseEntity.ok(Map.of("likeStatus", likeStatusMap));
    }

    /**
     * 새로운 홈 페이지
     */
    @GetMapping({"", "/"})
    public String newHome(Model model, @AuthenticationPrincipal UserDetails userDetails, HttpServletRequest request) {
        // 최신 블로그 글 50개 조회 (좋아요 상태는 별도 API로 조회)
        Page<ArticleResponseDto> allArticles = articleService.getArticlesWithFilters(
            null,  // keyword
            null,  // regions
            0,     // page
            50,    // size - 여유있게 가져옴
            "latest",  // sort
            "list",    // view
            null   // category
        );

        // 기업별로 첫 번째 글만 선택 (최신순이므로 첫 번째가 가장 최신)
        Map<Long, ArticleResponseDto> uniqueArticles = new LinkedHashMap<>();
        for (ArticleResponseDto articleDto : allArticles.getContent()) {
            // ArticleListResponseDto로 캐스팅하여 corporation 접근
            ArticleListResponseDto article = (ArticleListResponseDto) articleDto;
            Long corpId = article.getCorporation().getId();
            if (!uniqueArticles.containsKey(corpId)) {
                uniqueArticles.put(corpId, articleDto);
                if (uniqueArticles.size() >= 8) break;  // 8개만 수집
            }
        }
        List<ArticleResponseDto> latestArticles = new ArrayList<>(uniqueArticles.values());

        // 최신 영상 50개 조회 (중복 제거를 위해 더 많이 가져옴, 좋아요 상태는 별도 API로 조회)
        Page<com.newcodes7.small_town.video.dto.VideoResponseDto> allVideos = videoService.getVideosWithFilters(
            null,  // keyword
            null,  // regions
            0,     // page
            50,    // size - 여유있게 가져옴
            "latest",  // sort
            "list",    // view
            null   // category
        );

        // 기업별로 첫 번째 영상만 선택 (최신순이므로 첫 번째가 가장 최신)
        Map<Long, com.newcodes7.small_town.video.dto.VideoResponseDto> uniqueVideos = new LinkedHashMap<>();
        for (com.newcodes7.small_town.video.dto.VideoResponseDto videoDto : allVideos.getContent()) {
            com.newcodes7.small_town.video.dto.VideoListResponseDto video = (com.newcodes7.small_town.video.dto.VideoListResponseDto) videoDto;
            Long corpId = video.getCorporation().getId();
            if (!uniqueVideos.containsKey(corpId)) {
                uniqueVideos.put(corpId, videoDto);
                if (uniqueVideos.size() >= 8) break;  // 8개만 수집
            }
        }
        List<com.newcodes7.small_town.video.dto.VideoResponseDto> latestVideos = new ArrayList<>(uniqueVideos.values());

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

        model.addAttribute("articles", latestArticles);
        model.addAttribute("videos", latestVideos);
        model.addAttribute("corporations", corporationsWithLogos);
        model.addAttribute("totalElements", totalElements);
        model.addAttribute("themes", themes);

        return "new-home";
    }

    /**
     * API: 아티클 검색 (어드민용)
     */
    @GetMapping("/api/articles/search")
    @ResponseBody
    public ResponseEntity<Page<ArticleResponseDto>> searchArticles(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {

        Page<ArticleResponseDto> articles = articleService.getArticlesWithFilters(
            keyword,
            null,  // regions
            page,
            size,
            "latest",  // sort
            "list",    // view
            null       // category
        );

        return ResponseEntity.ok(articles);
    }

    private boolean isAdmin(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}