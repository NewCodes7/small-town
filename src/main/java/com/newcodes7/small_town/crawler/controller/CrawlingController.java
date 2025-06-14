package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crawling")
@RequiredArgsConstructor
@Slf4j
public class CrawlingController {
    
    private final CrawlingService crawlingService;
    
    /**
     * 모든 기업 블로그 크롤링 실행
     * 테스트를 위해 GET 메서드로 구현
     * 실제 운영에서는 POST 메서드로 변경할 수 있음
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> crawlAllBlogs() {
        try {
            log.info("전체 크롤링 API 호출");
            List<CrawlResult> results = crawlingService.crawlAllBlogs();
            
            long successCount = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .count();
            
            long totalNewArticles = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .mapToLong(CrawlResult::getNewArticles)
                    .sum();
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "크롤링이 완료되었습니다.",
                "totalCorporations", results.size(),
                "successCount", successCount,
                "failureCount", results.size() - successCount,
                "totalNewArticles", totalNewArticles,
                "results", results
            ));
            
        } catch (Exception e) {
            log.error("전체 크롤링 API 오류", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                        "success", false,
                        "message", "크롤링 중 오류가 발생했습니다: " + e.getMessage()
                    ));
        }
    }
    
    /**
     * 특정 기업 블로그 크롤링 실행
     */
    @GetMapping("/corporation/{corporationId}")
    public ResponseEntity<Map<String, Object>> crawlSingleBlog(@PathVariable Long corporationId) {
        try {
            log.info("개별 크롤링 API 호출 - corporationId: {}", corporationId);
            CrawlResult result = crawlingService.crawlSingleBlog(corporationId);
            
            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "크롤링이 완료되었습니다.",
                    "corporationName", result.getCorporation().getName(),
                    "totalArticles", result.getTotalArticles(),
                    "newArticles", result.getNewArticles(),
                    "result", result
                ));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                            "success", false,
                            "message", result.getErrorMessage(),
                            "corporationId", corporationId
                        ));
            }
            
        } catch (Exception e) {
            log.error("개별 크롤링 API 오류 - corporationId: {}", corporationId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                        "success", false,
                        "message", "크롤링 중 오류가 발생했습니다: " + e.getMessage(),
                        "corporationId", corporationId
                    ));
        }
    }
    
    /**
     * 크롤링 통계 조회
     */
    @GetMapping("/stats")
    public ResponseEntity<CrawlingStats> getCrawlingStats() {
        try {
            CrawlingStats stats = crawlingService.getCrawlingStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("크롤링 통계 조회 오류", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}