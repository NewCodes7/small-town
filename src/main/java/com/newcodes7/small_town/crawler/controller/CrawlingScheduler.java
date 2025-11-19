package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.service.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.service.TitleTranslationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true")
public class CrawlingScheduler {

    private final CrawlingService crawlingService;
    private final TitleTranslationService titleTranslationService;
    private final ArticlePersistenceService articlePersistenceService;

    /**
     * 블로그 크롤링 스케줄러
     */
    @Scheduled(cron = "${crawler.schedule.blog.cron:0 0 2 * * ?}", zone = "Asia/Seoul")
    public void scheduledBlogCrawling() {
        log.info("스케줄된 블로그 크롤링 작업 시작");

        try {
            List<CrawlResult> results = crawlingService.crawlAllBlogs();

            long successCount = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .count();

            long failureCount = results.size() - successCount;

            long totalNewArticles = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .mapToLong(CrawlResult::getNewArticles)
                    .sum();

            log.info("스케줄된 블로그 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 신규 글: {}개",
                successCount, failureCount, totalNewArticles);

            // 실패한 경우 로그 출력
            results.stream()
                    .filter(result -> !result.isSuccess())
                    .forEach(result -> {
                        String corpName = result.getCorporation() != null ?
                            result.getCorporation().getName() : "Unknown";
                        log.warn("블로그 크롤링 실패 - 기업: {}, 오류: {}", corpName, result.getErrorMessage());
                    });

        } catch (Exception e) {
            log.error("스케줄된 블로그 크롤링 작업 중 오류 발생", e);
        }
    }

    /**
     * YouTube 크롤링 스케줄러
     */
    @Scheduled(cron = "${crawler.schedule.youtube.cron:0 30 2 * * ?}", zone = "Asia/Seoul")
    public void scheduledYouTubeCrawling() {
        log.info("스케줄된 YouTube 크롤링 작업 시작");

        try {
            List<com.newcodes7.small_town.crawler.dto.VideoCrawlResult> results = crawlingService.crawlAllYouTube();

            long successCount = results.stream()
                    .filter(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::isSuccess)
                    .count();

            long failureCount = results.size() - successCount;

            long totalNewVideos = results.stream()
                    .filter(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::isSuccess)
                    .mapToLong(com.newcodes7.small_town.crawler.dto.VideoCrawlResult::getNewVideos)
                    .sum();

            log.info("스케줄된 YouTube 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 신규 영상: {}개",
                successCount, failureCount, totalNewVideos);

            // 실패한 경우 로그 출력
            results.stream()
                    .filter(result -> !result.isSuccess())
                    .forEach(result -> {
                        String corpName = result.getCorporation() != null ?
                            result.getCorporation().getName() : "Unknown";
                        log.warn("YouTube 크롤링 실패 - 기업: {}, 오류: {}", corpName, result.getErrorMessage());
                    });

        } catch (Exception e) {
            log.error("스케줄된 YouTube 크롤링 작업 중 오류 발생", e);
        }
    }

    /**
     * 제목 번역 및 AI 카테고리 분류 스케줄러
     */
    @Scheduled(cron = "${crawler.schedule.analysis.cron:0 0 5 * * ?}", zone = "Asia/Seoul")
    public void scheduledTranslationAndAnalysis() {
        log.info("스케줄된 번역 및 AI 분석 작업 시작");

        try {
            // 1. 해외 기업 글 제목 번역
            log.info("해외 기업 글 제목 번역 시작");
            titleTranslationService.translateAllOverseasArticleTitles();
            log.info("해외 기업 글 제목 번역 완료");

            // 2. 해외 기업 영상 제목 번역
            log.info("해외 기업 영상 제목 번역 시작");
            titleTranslationService.translateAllOverseasVideoTitles();
            log.info("해외 기업 영상 제목 번역 완료");

            // 3. 미분류 글 AI 카테고리 분류
            log.info("미분류 글 AI 카테고리 분류 시작");
            var result = articlePersistenceService.analyzeExistingArticles();
            log.info("미분류 글 AI 카테고리 분류 완료 - {}", result.get("message"));

            log.info("스케줄된 번역 및 AI 분석 작업 완료");

        } catch (Exception e) {
            log.error("스케줄된 번역 및 AI 분석 작업 중 오류 발생", e);
        }
    }
}