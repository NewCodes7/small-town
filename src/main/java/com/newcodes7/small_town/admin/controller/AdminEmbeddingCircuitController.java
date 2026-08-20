package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.embedding.service.EmbeddingCircuitBreaker;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Clova 임베딩 서킷 브레이커 상태 조회 / 임계치 변경 / 수동 리셋.
 * 화면은 admin/search-weights 페이지 안의 섹션으로 함께 제공된다.
 */
@Controller
@RequestMapping("/admin/embedding/circuit")
@RequiredArgsConstructor
@Slf4j
public class AdminEmbeddingCircuitController {

    private final EmbeddingCircuitBreaker circuitBreaker;

    @GetMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            EmbeddingCircuitBreaker.Settings s = circuitBreaker.getSettings();
            response.put("success", true);
            response.put("state", circuitBreaker.getState().name());
            response.put("failureRate", circuitBreaker.getFailureRate());
            response.put("slowCallRate", circuitBreaker.getSlowCallRate());
            response.put("enabled", s.enabled());
            response.put("failureRateThreshold", s.failureRateThreshold());
            response.put("slowCallRateThreshold", s.slowCallRateThreshold());
            response.put("slowCallDurationMs", s.slowCallDurationMs());
            response.put("waitDurationOpenMs", s.waitDurationOpenMs());
            response.put("slidingWindowSize", s.slidingWindowSize());
            response.put("minimumNumberOfCalls", s.minimumNumberOfCalls());
            response.put("permittedCallsHalfOpen", s.permittedCallsHalfOpen());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[임베딩 차단기] 조회 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "조회 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PutMapping
    @ResponseBody
    public ResponseEntity<Map<String, Object>> update(@RequestBody UpdateRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            circuitBreaker.updateSettings(new EmbeddingCircuitBreaker.Settings(
                    request.enabled(),
                    request.failureRateThreshold(),
                    request.slowCallRateThreshold(),
                    request.slowCallDurationMs(),
                    request.waitDurationOpenMs(),
                    request.slidingWindowSize(),
                    request.minimumNumberOfCalls(),
                    request.permittedCallsHalfOpen()), "admin");
            response.put("success", true);
            response.put("state", circuitBreaker.getState().name());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("[임베딩 차단기] 업데이트 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "업데이트 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /** 장애 복구를 확인한 뒤 대기 없이 즉시 재개시킬 때 사용한다. */
    @PostMapping("/reset")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> reset() {
        Map<String, Object> response = new HashMap<>();
        try {
            circuitBreaker.reset();
            response.put("success", true);
            response.put("state", circuitBreaker.getState().name());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[임베딩 차단기] 리셋 중 오류 발생", e);
            response.put("success", false);
            response.put("message", "리셋 중 오류 발생: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    public record UpdateRequest(
            boolean enabled,
            double failureRateThreshold,
            double slowCallRateThreshold,
            int slowCallDurationMs,
            int waitDurationOpenMs,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            int permittedCallsHalfOpen) {}
}
