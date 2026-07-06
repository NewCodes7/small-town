package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.MigrationResultDto;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.like.service.UserLikeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아티클 코어 REST API 컨트롤러.
 * (목록/필터, ID 배치 조회, 검색, 좋아요 마이그레이션)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ArticleApiController {

    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final UserLikeService userLikeService;

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
    public ResponseEntity<MigrationResultDto> migrateLikesFromLocalStorage(
            @RequestBody List<Long> articleIds,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        MigrationResultDto result =
            userLikeService.migrateLikesFromLocalStorage(userDetails.getUsername(), articleIds);

        return ResponseEntity.ok(result);
    }

    /**
     * API: 인기글 조회 (주간/월간)
     */
    @GetMapping("/api/articles/popular")
    @ResponseBody
    public ResponseEntity<List<ArticleListResponseDto>> getPopularArticles(
            @RequestParam(name = "period", defaultValue = "weekly") String period,
            @RequestParam(name = "limit", defaultValue = "8") int limit) {

        List<ArticleListResponseDto> articles;
        if ("monthly".equals(period)) {
            articles = articleService.getMonthlyPopularArticles(limit);
        } else if ("weekly".equals(period)) {
            articles = articleService.getWeeklyPopularArticles(limit);
        } else {
            throw new InvalidParameterException("period", period, "period는 weekly 또는 monthly여야 합니다");
        }

        return ResponseEntity.ok(articles);
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
}
