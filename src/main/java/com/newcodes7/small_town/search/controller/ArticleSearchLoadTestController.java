package com.newcodes7.small_town.search.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.global.util.Client;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하이브리드 검색 부하테스트 전용 엔드포인트 (RagChatLoadTestController와 같은 패턴).
 *
 * 실사용자 경로(ArticleSearchController.searchArticles)와 다른 점은 셋뿐이다:
 *  1. 쿼리 임베딩을 실 Clova가 아니라 mock 엔드포인트(clova.loadtest-endpoint)로 생성한다 —
 *     부하테스트는 매 요청 고유 키워드를 쓰기 때문에 search_query_embedding DB 캐시가 100% 미스가
 *     되고, 그대로 두면 요청마다 실 Clova 과금 호출이 나간다.
 *  2. mock 임베딩 결과가 실사용자에게 서빙되지 않도록 캐시 키를 분리하고(ArticleSearchService의
 *     "lt:mock:" prefix), search_query_embedding 저장도 건너뛴다.
 *  3. search_log를 남기지 않는다 — 합성 키워드가 인기 검색어/자동완성 통계를 오염시키지 않도록.
 *
 * 접근 통제(RAG 부하테스트 엔드포인트와 동일한 3중 게이트):
 *  1. 기본 비활성(search.loadtest.enabled=false) — 비활성 시 404로 존재 자체를 숨긴다
 *  2. nginx location이 X-LoadTest-Token 헤더($loadtest_bypass)를 검사, 불일치 시 403
 *  3. clova.loadtest-endpoint 미설정이면 503 — 실 Clova로 새는 오설정을 요청 처리 전에 잡는다
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ArticleSearchLoadTestController {

    private final ArticleSearchService articleSearchService;
    private final SemanticTermExpansionService semanticExpansionService;

    @Value("${search.loadtest.enabled:false}")
    private boolean loadTestEnabled;

    @Value("${clova.loadtest-endpoint:}")
    private String clovaLoadTestEndpoint;

    @GetMapping("/api/search/articles/loadtest")
    public ResponseEntity<Map<String, Object>> searchArticlesForLoadTest(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "keyword") String keyword,
            @RequestParam(name = "regions", required = false) List<String> regions,
            @RequestParam(name = "category", required = false) List<String> category,
            HttpServletRequest request) {

        if (!loadTestEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (clovaLoadTestEndpoint == null || clovaLoadTestEndpoint.isBlank()) {
            // 미설정 상태로 실행하면 실 Clova로 과금 호출이 나간다 — 요청 처리 전에 막는다
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "부하테스트 검색인데 clova.loadtest-endpoint가 비어 있습니다 (CLOVA_LOADTEST_ENDPOINT 확인)");
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword는 필수입니다");
        }

        String trimmedKeyword = keyword.trim().toLowerCase();
        String effectiveSort = sort != null ? sort : "relevance";
        String clientIp = Client.getClientIpAddress(request);

        Map<String, Double> expandedTerms = semanticExpansionService.expandSearchTerms(trimmedKeyword);

        Page<ArticleResponseDto> articles = articleSearchService.searchArticlesHybrid(
                trimmedKeyword,
                expandedTerms,
                regions == null || regions.isEmpty() ? null : regions.stream().sorted().toList(),
                category == null || category.isEmpty() ? null : category.stream().sorted().toList(),
                page,
                size,
                effectiveSort,
                clientIp,
                null,
                true
        ).map(dto -> (ArticleResponseDto) dto);

        // 응답 형태는 실사용자 경로와 동일하게 유지 — k6가 같은 방식으로 파싱/검증할 수 있도록.
        // 단 searchLogService.logSearchAsync는 호출하지 않는다 (search_log 오염 방지).
        Map<String, Object> response = new HashMap<>();
        response.put("content", articles.getContent());
        response.put("currentPage", page);
        response.put("totalPages", articles.getTotalPages());
        response.put("totalElements", articles.getTotalElements());
        response.put("hasNext", articles.hasNext());
        response.put("hasPrevious", articles.hasPrevious());
        response.put("currentSort", effectiveSort);
        response.put("keyword", keyword);
        response.put("view", "list");

        return ResponseEntity.ok(response);
    }
}
