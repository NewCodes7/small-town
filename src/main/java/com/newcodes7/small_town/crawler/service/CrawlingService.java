package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.config.CrawlerProperties;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.service.BlogCrawler;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlingService {
    
    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerArticleRepository crawlerArticleRepository;
    private final ApplicationContext applicationContext;
    private final CrawlerProperties crawlerProperties;
    
    // TODO: 스레드 수 최적화 필요 (현재는 5개)
    private final ExecutorService executorService = 
        Executors.newFixedThreadPool(5); 
    
    /**
     * 모든 기업 블로그 크롤링
     */
    public List<CrawlResult> crawlAllBlogs() {
        if (!crawlerProperties.isEnabled()) {
            log.info("크롤링이 비활성화되어 있습니다.");
            return new ArrayList<>();
        }
        
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();
        log.info("크롤링 시작 - 대상 기업: {}개", corporations.size());
        
        // 비동기로 크롤링 요청 
        List<CompletableFuture<CrawlResult>> futures = new ArrayList<>();
        for (Corporation corporation : corporations) {
            CompletableFuture<CrawlResult> future = CompletableFuture.supplyAsync(() -> {
                return crawlSingleBlog(corporation.getId());
            }, executorService);
            
            futures.add(future);
        }
        
        // 모든 크롤링 완료 대기
        List<CrawlResult> results = new ArrayList<>();
        for (CompletableFuture<CrawlResult> future : futures) {
            try {
                CrawlResult result = future.get(5, TimeUnit.MINUTES); // 동기 블로킹 *최대 5분 대기 
                results.add(result);
            } catch (Exception e) {
                log.error("크롤링 작업 실행 중 오류 발생: {}", e.getMessage());
            }
        }
        
        log.info("전체 크롤링 완료 - 처리된 기업: {}개", results.size());
        return results;
    }
    
    /**
     * 특정 기업 블로그 크롤링
     */
    @Transactional
    public CrawlResult crawlSingleBlog(Long corporationId) {
        Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(corporationId);
        if (corporation == null) {
            return CrawlResult.failure(null, "기업을 찾을 수 없습니다: " + corporationId);
        }
        
        if (corporation.getBlogLink() == null || corporation.getBlogLink().trim().isEmpty()) {
            return CrawlResult.failure(corporation, "블로그 링크가 없습니다.");
        }
        
        WebDriver driver = null;
        try {
            driver = applicationContext.getBean(WebDriver.class);
            
            // 적절한 크롤러 선택
            BlogCrawler crawler = selectCrawler(corporation.getBlogLink());
            log.info("크롤링 시작 - 기업: {}, 크롤러: {}", corporation.getName(), crawler.getProviderName());
            
            // 크롤링 실행
            List<Article> crawledArticles = crawler.crawl(driver, corporation);
            
            // 중복 제거 및 저장
            int newArticlesCount = 0;
            List<Article> savedArticles = new ArrayList<>();
            
            for (Article article : crawledArticles) {
                if (!crawlerArticleRepository.findByLinkAndDeletedAtIsNull(article.getLink()).isPresent()) {
                    Article savedArticle = crawlerArticleRepository.save(article);
                    savedArticles.add(savedArticle);
                    newArticlesCount++;
                }
            }
            
            log.info("크롤링 완료 - 기업: {}, 전체: {}개, 신규: {}개", 
                corporation.getName(), crawledArticles.size(), newArticlesCount);
            
            return CrawlResult.success(corporation, savedArticles, newArticlesCount);
            
        } catch (Exception e) {
            log.error("크롤링 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            return CrawlResult.failure(corporation, e.getMessage());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                    log.warn("WebDriver 종료 중 오류: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * 블로그 URL에 따라 적절한 크롤러 선택
     */
    private BlogCrawler selectCrawler(String blogUrl) {
        List<BlogCrawler> crawlers = applicationContext.getBeansOfType(BlogCrawler.class)
                .values()
                .stream()
                .toList();
        
        // 특화된 크롤러 우선 선택
        for (BlogCrawler crawler : crawlers) {
            if (!crawler.getProviderName().equals("Default") && crawler.canHandle(blogUrl)) {
                return crawler;
            }
        }
        
        // 기본 크롤러 반환
        return crawlers.stream()
                .filter(crawler -> crawler.getProviderName().equals("Default"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("기본 크롤러를 찾을 수 없습니다."));
    }
    
    /**
     * 크롤링 통계 조회
     */
    public CrawlingStats getCrawlingStats() {
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        
        List<Corporation> allCorporations = crawlerCorporationRepository.findAllWithBlogLink();
        long totalCorporations = allCorporations.size();
        
        long totalNewArticles = 0;
        for (Corporation corp : allCorporations) {
            totalNewArticles += crawlerArticleRepository.countNewArticlesByCorporation(corp.getId(), since);
        }
        
        return CrawlingStats.builder()
                .totalCorporations(totalCorporations)
                .totalNewArticles(totalNewArticles)
                .lastCrawledAt(LocalDateTime.now())
                .build();
    }
}