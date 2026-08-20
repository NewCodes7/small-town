package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.search.service.SearchConcurrencyLimiter;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 검색 동시 실행 상한 조회/변경 (AdminSearchWeightController와 같은 패턴).
 * 화면은 admin/search-weights 페이지 안의 섹션으로 함께 제공된다.
 */
@Controller
@RequestMapping("/admin/search/concurrency")
@RequiredArgsConstructor
@Slf4j
public class AdminSearchConcurrencyController {

    private final SearchConcurrencyLimiter searchConcurrencyLimiter;

    @GetMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLimits() {
        Map<String, Object> response = new HashMap<>();
        try {
            SearchConcurrencyLimiter.Limits limits = searchConcurrencyLimiter.getLimits();
            response.put("success", true);
            response.put("maxConcurrent", limits.maxConcurrent());
            response.put("acquireTimeoutMs", limits.acquireTimeoutMs());
            response.put("inUse", searchConcurrencyLimiter.getInUse());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[검색 동시성] 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateLimits(@RequestBody UpdateLimitsRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            searchConcurrencyLimiter.updateLimits(
                    request.maxConcurrent(), request.acquireTimeoutMs(), "admin");
            response.put("success", true);
            response.put("maxConcurrent", request.maxConcurrent());
            response.put("acquireTimeoutMs", request.acquireTimeoutMs());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("[검색 동시성] 업데이트 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "업데이트 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    public record UpdateLimitsRequest(int maxConcurrent, int acquireTimeoutMs) {}
}
