package com.newcodes7.small_town.admin.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.crawler.integration.analytics.GoogleAnalyticsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Google Analytics 조회수 동기화 관리 Controller
 */
@Controller
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
@Slf4j
public class AdminGoogleAnalyticsController {

    private final GoogleAnalyticsService googleAnalyticsService;

    /**
     * Google Analytics 조회수 동기화 API
     * 모든 기업의 조회수를 GA에서 가져와 동기화
     */
    @PostMapping("/sync-views")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> syncGoogleAnalyticsViews(
            @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (!googleAnalyticsService.isEnabled()) {
                response.put("success", false);
                response.put("message", "Google Analytics가 설정되지 않았습니다. GA_CREDENTIALS_PATH 환경변수를 확인해주세요.");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("GA 조회수 동기화 시작 - 기간: {}일", days);
            int syncCount = googleAnalyticsService.syncAllCorporationViewCounts(days);

            response.put("success", true);
            response.put("message", String.format("Google Analytics 조회수 동기화 완료 (기간: %d일)", days));
            response.put("syncedCorporations", syncCount);
            response.put("days", days);

            log.info("GA 조회수 동기화 완료 - 동기화된 기업: {}개", syncCount);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("GA 조회수 동기화 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "GA 조회수 동기화 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Google Analytics 전체 기간 조회수 동기화 API
     * GA4 데이터 보관 기간(약 14개월) 전체를 동기화
     */
    @PostMapping("/sync-views-all")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> syncGoogleAnalyticsViewsAllTime() {
        Map<String, Object> response = new HashMap<>();

        try {
            if (!googleAnalyticsService.isEnabled()) {
                response.put("success", false);
                response.put("message", "Google Analytics가 설정되지 않았습니다. GA_CREDENTIALS_PATH 환경변수를 확인해주세요.");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("GA 전체 기간 조회수 동기화 시작");
            int syncCount = googleAnalyticsService.syncAllTimeViewCounts();

            response.put("success", true);
            response.put("message", "Google Analytics 전체 기간 조회수 동기화 완료 (약 14개월)");
            response.put("syncedCorporations", syncCount);

            log.info("GA 전체 기간 조회수 동기화 완료 - 동기화된 기업: {}개", syncCount);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("GA 전체 기간 조회수 동기화 실패: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "GA 전체 기간 조회수 동기화 중 오류가 발생했습니다: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Google Analytics 설정 상태 확인 API
     */
    @GetMapping("/status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getGoogleAnalyticsStatus() {
        Map<String, Object> response = new HashMap<>();

        response.put("enabled", googleAnalyticsService.isEnabled());
        response.put("message", googleAnalyticsService.isEnabled()
            ? "Google Analytics 연동이 활성화되어 있습니다."
            : "Google Analytics가 비활성화되어 있습니다. 서비스 계정 키 파일을 설정해주세요.");

        return ResponseEntity.ok(response);
    }
}
