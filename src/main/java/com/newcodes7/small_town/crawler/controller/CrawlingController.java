package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.VideoCrawlResult;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.persistence.VideoPersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.service.YouTubeCrawlingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crawling")
@RequiredArgsConstructor
@Slf4j
public class CrawlingController {

    private final CrawlingService crawlingService;
    private final YouTubeCrawlingService youtubeCrawlingService;
    private final ArticlePersistenceService articlePersistenceService;
    private final VideoPersistenceService videoPersistenceService;

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
            List<com.newcodes7.small_town.crawler.dto.VideoCrawlResult> results = youtubeCrawlingService.crawlAllYouTube();

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
            VideoCrawlResult result = youtubeCrawlingService.crawlSingleYouTube(corporationId, null);

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
     * 기존 글들과 비디오들에 대한 AI 분석 실행
     */
    @GetMapping("/analyze-existing")
    public ResponseEntity<Map<String, Object>> analyzeExistingContent() {
        try {
            log.info("기존 콘텐츠 AI 분석 API 호출 (Article + Video)");

            Map<String, Object> combinedResult = new HashMap<>();

            // Article 분석
            log.info("Article AI 분석 시작");
            Map<String, Object> articleResult = articlePersistenceService.analyzeExistingArticles();

            // Video 분석
            log.info("Video AI 분석 시작");
            Map<String, Object> videoResult = videoPersistenceService.analyzeExistingVideos();

            // 결과 통합
            combinedResult.put("success", true);
            combinedResult.put("articles", articleResult);
            combinedResult.put("videos", videoResult);

            // 전체 통계
            int totalProcessed = (int) articleResult.getOrDefault("processedCount", 0) +
                                (int) videoResult.getOrDefault("processedCount", 0);
            int totalSuccess = (int) articleResult.getOrDefault("successCount", 0) +
                              (int) videoResult.getOrDefault("successCount", 0);
            int totalFailure = (int) articleResult.getOrDefault("failureCount", 0) +
                              (int) videoResult.getOrDefault("failureCount", 0);

            combinedResult.put("totalProcessed", totalProcessed);
            combinedResult.put("totalSuccess", totalSuccess);
            combinedResult.put("totalFailure", totalFailure);
            combinedResult.put("message", String.format(
                "전체 분석 완료. Article: %d개, Video: %d개 (성공: %d, 실패: %d)",
                articleResult.getOrDefault("processedCount", 0),
                videoResult.getOrDefault("processedCount", 0),
                totalSuccess,
                totalFailure
            ));

            return ResponseEntity.ok(combinedResult);

        } catch (Exception e) {
            log.error("기존 콘텐츠 AI 분석 API 오류", e);
            return ResponseEntity.badRequest()
                    .body(Map.of(
                        "success", false,
                        "message", "AI 분석 중 오류가 발생했습니다: " + e.getMessage(),
                        "error", e.getClass().getSimpleName()
                    ));
        }
    }
}