package com.newcodes7.small_town.search.controller;

import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.search.dto.SearchClickRequestDto;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.search.service.SearchClickLogService;
import com.newcodes7.small_town.search.service.SearchLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class ArticleSearchController {

    private final ArticleService articleService;
    private final ArticleSearchService articleSearchService;
    private final SearchLogService searchLogService;
    private final SearchClickLogService searchClickLogService;

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

        // 요청 데이터 미리 추출 (비동기 검색 로그용, 경량 연산)
        String clientIp = Client.getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");
        String username = userDetails != null ? userDetails.getUsername() : null;

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

            // 따옴표 검색 감지: "키워드" 형식
            if (trimmedKeyword.startsWith("\"") && trimmedKeyword.endsWith("\"") && trimmedKeyword.length() > 2) {
                String exactKeyword = trimmedKeyword.substring(1, trimmedKeyword.length() - 1).toLowerCase();
                articles = articleSearchService.searchArticlesExactMatch(
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
                // 검색어 확장(유의어 조인 쿼리 실측 150~165ms)을 여기서 미리 부르지 않는다 —
                // searchArticlesHybrid 내부에서 Clova 임베딩 호출이 뜬 *뒤에* 돌아야 임베딩 대기
                // 구간에 겹쳐 숨는다. expandedTerms를 넘기면 그 순서가 깨진다.
                // (docs/search/SEARCH_TRACE_ANALYSIS.md A-1)
                articles = articleSearchService.searchArticlesHybrid(
                    trimmedKeyword.toLowerCase(),
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

        boolean isAdmin = userDetails != null &&
                userDetails.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));

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
        response.put("isAdmin", isAdmin);

        // 검색 로그 비동기 저장 (응답 속도 우선: 사용자 조회 + 로그 저장 모두 비동기)
        if (keyword != null && !keyword.trim().isEmpty()) {
            searchLogService.logSearchAsync(keyword.trim(), SearchLog.SearchType.ARTICLE, null,
                    username, clientIp, userAgent);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/articles/{articleId}/click")
    public ResponseEntity<Void> recordClick(
            @PathVariable Long articleId,
            @RequestBody SearchClickRequestDto dto,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest request) {
        String username = userDetails != null ? userDetails.getUsername() : null;
        String ip = Client.getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");
        searchClickLogService.logClick(articleId, dto, username, ip, userAgent);
        return ResponseEntity.noContent().build();
    }
}
