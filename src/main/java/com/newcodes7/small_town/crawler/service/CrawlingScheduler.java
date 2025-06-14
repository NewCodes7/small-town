package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.config.CrawlerProperties;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
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
    private final CrawlerProperties crawlerProperties;
    
    @Scheduled(cron = "${crawler.schedule.cron}")
    public void scheduledCrawling() {
        log.info("스케줄된 크롤링 작업 시작");
        
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