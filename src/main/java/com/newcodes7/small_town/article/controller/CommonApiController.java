package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.repository.SearchLogRepository;
import com.newcodes7.small_town.search.service.AutocompleteService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 페이지 공통 보조 API 컨트롤러.
 * (사용자 정보, 카테고리 목록, 자동완성, 검색 기록)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CommonApiController {

    private final CategoryRepository categoryRepository;
    private final AutocompleteService autocompleteService;
    private final SearchLogRepository searchLogRepository;
    private final com.newcodes7.small_town.auth.repository.UserRepository userRepository;

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
        return ResponseEntity.ok(autocompleteService.getAutocompleteSuggestions(query));
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

    private boolean isAdmin(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }
}
