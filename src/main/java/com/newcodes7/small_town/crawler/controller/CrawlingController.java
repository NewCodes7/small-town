package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.crawler.service.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
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
    private final ArticlePersistenceService articlePersistenceService;
    
    /**
     * 모든 기업의 블로그 및 YouTube 모두 크롤링 실행
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> crawlAll() {
        try {
            log.info("전체 크롤링 API 호출 (블로그 + YouTube)");
            List<CrawlResult> results = crawlingService.crawlAll();

            long successCount = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .count();

            long totalNewArticles = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .mapToLong(CrawlResult::getNewArticles)
                    .sum();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "전체 크롤링이 완료되었습니다.",
                "totalCorporations", results.size(),
                "successCount", successCount,
                "failureCount", results.size() - successCount,
                "totalNewArticles", totalNewArticles,
                "results", results
            ));

        } catch (Exception e) {
            log.error("전체 크롤링 API 오류", e);
            throw e;
        }
    }

    /**
     * 모든 기업의 블로그만 크롤링 실행
     */
    @GetMapping("/blogs")
    public ResponseEntity<Map<String, Object>> crawlAllBlogs() {
        try {
            log.info("블로그 전용 크롤링 API 호출");
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
                "message", "블로그 크롤링이 완료되었습니다.",
                "totalCorporations", results.size(),
                "successCount", successCount,
                "failureCount", results.size() - successCount,
                "totalNewArticles", totalNewArticles,
                "results", results
            ));

        } catch (Exception e) {
            log.error("블로그 크롤링 API 오류", e);
            throw e;
        }
    }

    /**
     * 모든 기업의 YouTube만 크롤링 실행
     */
    @GetMapping("/youtube")
    public ResponseEntity<Map<String, Object>> crawlAllYouTube() {
        try {
            log.info("YouTube 전용 크롤링 API 호출");
            List<com.newcodes7.small_town.crawler.dto.VideoCrawlResult> results = crawlingService.crawlAllYouTube();

            long successCount = results.stream()
                    .filter(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::isSuccess)
                    .count();

            long totalNewVideos = results.stream()
                    .filter(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::isSuccess)
                    .mapToLong(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::getNewVideos)
                    .sum();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "YouTube 크롤링이 완료되었습니다.",
                "totalCorporations", results.size(),
                "successCount", successCount,
                "failureCount", results.size() - successCount,
                "totalNewVideos", totalNewVideos,
                "results", results
            ));

        } catch (Exception e) {
            log.error("YouTube 크롤링 API 오류", e);
            throw e;
        }
    }
    
    /**
     * 특정 기업 블로그 크롤링 실행
     */
    @GetMapping("/blogs/{corporationId}")
    public ResponseEntity<Map<String, Object>> crawlSingleBlog(@PathVariable("corporationId") Long corporationId) {
        try {
            log.info("개별 블로그 크롤링 API 호출 - corporationId: {}", corporationId);
            CrawlResult result = crawlingService.crawlSingleBlog(corporationId, null);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "블로그 크롤링이 완료되었습니다.",
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
            log.error("개별 블로그 크롤링 API 오류 - corporationId: {}", corporationId, e);
            throw e;
        }
    }

    /**
     * 특정 기업 YouTube 크롤링 실행
     */
    @GetMapping("/youtube/{corporationId}")
    public ResponseEntity<Map<String, Object>> crawlSingleYouTube(@PathVariable("corporationId") Long corporationId) {
        try {
            log.info("개별 YouTube 크롤링 API 호출 - corporationId: {}", corporationId);
            VideoCrawlResult result = crawlingService.crawlSingleYouTube(corporationId, null);

            if (result.isSuccess()) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "YouTube 크롤링이 완료되었습니다.",
                    "corporationName", result.getCorporation().getName(),
                    "totalVideos", result.getTotalVideos(),
                    "newVideos", result.getNewVideos(),
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
            log.error("개별 YouTube 크롤링 API 오류 - corporationId: {}", corporationId, e);
            throw e;
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
            throw e;
        }
    }

    /**
     * 기존 글들에 대한 AI 분석 실행
     */
    @GetMapping("/analyze-existing")
    public ResponseEntity<Map<String, Object>> analyzeExistingArticles() {
        try {
            log.info("기존 글 AI 분석 API 호출");
            Map<String, Object> result = articlePersistenceService.analyzeExistingArticles();

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("기존 글 AI 분석 API 오류", e);
            return ResponseEntity.badRequest()
                    .body(Map.of(
                        "success", false,
                        "message", "기존 글 AI 분석 중 오류가 발생했습니다: " + e.getMessage(),
                        "error", e.getClass().getSimpleName()
                    ));
        }
    }
}