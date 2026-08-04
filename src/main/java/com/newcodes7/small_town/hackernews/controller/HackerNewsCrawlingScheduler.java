package com.newcodes7.small_town.hackernews.controller;

import com.newcodes7.small_town.hackernews.service.HackerNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hacker News 크롤링 스케줄러
 * 매일 03:30에 인기 스토리 + 댓글을 크롤링
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true")
public class HackerNewsCrawlingScheduler {

    private final HackerNewsService hackerNewsService;

    /**
     * Hacker News 인기 스토리 + 댓글 크롤링 스케줄러
     * 매일 03:30에 실행
     */
    @Scheduled(cron = "${hackernews.schedule.cron:0 30 3 * * ?}", zone = "Asia/Seoul")
    public void scheduledHackerNewsCrawling() {
        log.info("스케줄된 Hacker News 크롤링 작업 시작");

        try {
            int savedCount = hackerNewsService.crawlTopStoriesWithComments();
            log.info("스케줄된 Hacker News 크롤링 작업 완료 - 신규 스토리: {}개", savedCount);
        } catch (Exception e) {
            log.error("스케줄된 Hacker News 크롤링 작업 중 오류 발생", e);
        }
    }
}
