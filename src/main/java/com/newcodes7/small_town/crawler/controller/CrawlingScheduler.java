package com.newcodes7.small_town.crawler.controller;

import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.service.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.CrawlingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true")
public class CrawlingScheduler {

    private final CrawlingService crawlingService;
    private final ArticlePersistenceService articlePersistenceService;
    
    @Scheduled(cron = "${crawler.schedule.cron}", zone = "Asia/Seoul")
    public void scheduledCrawling() {
        log.info("스케줄된 크롤링 작업 시작");
        
        try {
            List<CrawlResult> results = crawlingService.crawlAllBlogs();
            Map<String, Object> aiResults = articlePersistenceService.analyzeExistingArticles();
            
            long successCount = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .count();
            
            long failureCount = results.size() - successCount;
            
            long totalNewArticles = results.stream()
                    .filter(CrawlResult::isSuccess)
                    .mapToLong(CrawlResult::getNewArticles)
                    .sum();
            
            log.info("스케줄된 크롤링 작업 완료 - 성공: {}개, 실패: {}개, 신규 글: {}개", 
                successCount, failureCount, totalNewArticles);
            
            // 실패한 경우 로그 출력
            results.stream()
                    .filter(result -> !result.isSuccess())
                    .forEach(result -> {
                        String corpName = result.getCorporation() != null ? 
                            result.getCorporation().getName() : "Unknown";
                        log.warn("크롤링 실패 - 기업: {}, 오류: {}", corpName, result.getErrorMessage());
                    });
                    
        } catch (Exception e) {
            log.error("스케줄된 크롤링 작업 중 오류 발생", e);
        }
    }
}