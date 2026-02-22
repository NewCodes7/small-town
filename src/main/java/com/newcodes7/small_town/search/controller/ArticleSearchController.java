package com.newcodes7.small_town.search.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.article.service.SemanticTermExpansionService;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.service.SearchLogService;
import com.newcodes7.small_town.global.util.Client;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class ArticleSearchController {

    private final ArticleService articleService;
    private final SemanticTermExpansionService semanticExpansionService;
    private final SearchLogService searchLogService;
    private final UserRepository userRepository;

    @GetMapping("/articles")
    public ResponseEntity<Map<String, Object>> searchArticles(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "regions", required = false) List<String> regions,
            @RequestParam(name = "view", defaultValue = "list") String view,
            @RequestParam(name = "category", required = false) List<String> category,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {

        // 검색 로그 저장
        if (keyword != null && !keyword.trim().isEmpty()) {
            User user = null;
            if (userDetails != null) {
                user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername()).orElse(null);
            }
            searchLogService.logSearch(keyword.trim(), SearchLog.SearchType.ARTICLE, null, user, request);
        }

        // sort 기본값 설정: 검색 시 적합도순, 일반 조회 시 최신순
        String effectiveSort = sort;
        if (effectiveSort == null) {
            if (keyword != null && !keyword.trim().isEmpty()) {
                effectiveSort = "relevance";
            } else {
                effectiveSort = "latest";
            }
        }

        Page<ArticleResponseDto> articles;

        // 키워드 검색 시 Hybrid 검색 사용 (BM25 + Vector) 또는 따옴표 검색 (ILIKE + Vector)
        if (keyword != null && !keyword.trim().isEmpty() && view.equals("list")) {
            String trimmedKeyword = keyword.trim();
            String clientIp = Client.getClientIpAddress(request);
            String username = userDetails != null ? userDetails.getUsername() : null;

            // 따옴표 검색 감지: "키워드" 형식
            if (trimmedKeyword.startsWith("\"") && trimmedKeyword.endsWith("\"") && trimmedKeyword.length() > 2) {
                String exactKeyword = trimmedKeyword.substring(1, trimmedKeyword.length() - 1).toLowerCase();
                articles = articleService.searchArticlesExactMatch(
                    exactKeyword,
                    regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                    category == null || category.isEmpty() ? null : category.stream().sorted().toList(),
                    page,
                    size,
                    effectiveSort,
                    clientIp,
                    username
                ).map(dto -> (ArticleResponseDto) dto);
            } else {
                // 일반 Hybrid 검색 (BM25 + Vector)
                Map<String, Double> expandedTerms = semanticExpansionService.expandSearchTerms(trimmedKeyword.toLowerCase());

                articles = articleService.searchArticlesHybrid(
                    trimmedKeyword.toLowerCase(),
                    expandedTerms,
                    regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                    category == null || category.isEmpty() ? null : category.stream().sorted().toList(),
                    page,
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
                page,
                size,
                effectiveSort,
                view,
                category == null || category.isEmpty() ? null : category.stream().sorted().toList()
            );
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("currentPage", page);
        response.put("totalPages", articles.getTotalPages());
        response.put("totalElements", articles.getTotalElements());
        response.put("hasNext", articles.hasNext());
        response.put("hasPrevious", articles.hasPrevious());
        response.put("currentSort", effectiveSort);
        response.put("keyword", keyword);
        response.put("selectedRegions", regions != null ? regions : new ArrayList<>());
        response.put("view", view);

        return ResponseEntity.ok(response);
    }
}
