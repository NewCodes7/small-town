package com.newcodes7.small_town.crawler.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerNotFoundException;
import com.newcodes7.small_town.crawler.exception.WebDriverException;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlingService {
    
    private final CrawlerCorporationRepository crawlerCorporationRepository;
    private final CrawlerArticleRepository crawlerArticleRepository;
    private final ApplicationContext applicationContext;
    private final RobotsTxtService robotsTxtService;
    private final WebDriverConfig webDriverConfig;

    /**
     * 모든 기업 블로그 크롤링 (동기 처리)
     */
    public List<CrawlResult> crawlAllBlogs() {
        List<Corporation> corporations = crawlerCorporationRepository.findAllWithBlogLink();
        log.info("크롤링 시작 - 대상 기업: {}개", corporations.size());
        
        List<CrawlResult> results = new ArrayList<>();
        
        // 순차적으로 크롤링 실행
        for (Corporation corporation : corporations) {
            WebDriver driver = null;
            try {
                driver = webDriverConfig.createWebDriver();
                CrawlResult result = crawlSingleBlog(corporation.getId(), driver);
                results.add(result);
            } catch (Exception e) {
                log.error("기업 ID {} 크롤링 중 오류 발생: {}", corporation.getId(), e.getMessage(), e);
                results.add(CrawlResult.failure(corporation, "크롤링 실행 실패: " + e.getMessage()));
            } finally {
                webDriverConfig.forceCloseWebDriver(driver);
            }
        }
        
        log.info("전체 크롤링 완료 - 처리된 기업: {}개", results.size());
        return results;
    }
    
    /**
     * 특정 기업 블로그 크롤링
     */
    @Transactional
    public CrawlResult crawlSingleBlog(Long corporationId, WebDriver driver) {
        Corporation corporation = crawlerCorporationRepository.findByIdAndNotDeleted(corporationId);
        if (corporation == null) {
            throw new CorporationCrawlingException(corporationId);
        }
        
        if (corporation.getBlogLink() == null || corporation.getBlogLink().trim().isEmpty()) {
            throw new CorporationCrawlingException(corporationId, "empty or null blog URL");
        }
        
        // REFACTOR: WebDriver 생성하고 종료시키는 로직이 crawlAllBlogs와 중복되는 것 해결 
        boolean isDriverProvided = (driver != null);
        if (!isDriverProvided) {
            driver = webDriverConfig.createWebDriver();
        }
        try {
            // 적절한 크롤러 선택
            BlogCrawler crawler = selectCrawler(corporation.getBlogLink());
            log.info("크롤링 시작 - 기업: {}, 크롤러: {}", corporation.getName(), crawler.getProviderName());
            
            // robots.txt 확인 및 크롤링 실행
            String baseUrl = crawler.extractBaseUrl(corporation.getBlogLink());
            log.info("robots.txt 확인 - 기업: {}, 블로그URL: {}, 베이스URL: {}", corporation.getName(), corporation.getBlogLink(), baseUrl);
            
            boolean isAllowed = robotsTxtService.isPathAllowed(baseUrl, "/");
            log.info("robots.txt 확인 결과 - 기업: {}, 허용 여부: {}", corporation.getName(), isAllowed);
            
            if (!isAllowed) {
                log.warn("robots.txt에 의해 크롤링이 금지됨 - 기업: {}, 블로그URL: {}, 베이스URL: {}", corporation.getName(), corporation.getBlogLink(), baseUrl);
                return CrawlResult.failure(corporation, "robots.txt에 의해 크롤링 금지됨");
            }
            
            log.info("robots.txt 확인 완료 - 크롤링 허용됨");
            List<Article> crawledArticles = crawler.crawlWithRobotsCheck(driver, corporation, robotsTxtService);
            
            // 중복 제거 및 저장
            int newArticlesCount = 0;
            List<Article> savedArticles = new ArrayList<>();
            
            for (Article article : crawledArticles) {
                if (!crawlerArticleRepository.findByLinkAndDeletedAtIsNull(article.getLink()).isPresent()) {
                    // 중복이 아닌 경우에만 이미지 업로드 처리
                    crawler.processImageUpload(article, corporation);
                    Article savedArticle = crawlerArticleRepository.save(article);
                    savedArticles.add(savedArticle);
                    newArticlesCount++;
                } else {
                    log.debug("중복 게시글 스킵: {}", article.getLink());
                }
            }
            
            log.info("크롤링 완료 - 기업: {}, 전체: {}개, 신규: {}개", 
                corporation.getName(), crawledArticles.size(), newArticlesCount);
            
            return CrawlResult.success(corporation, savedArticles, newArticlesCount);
            
        } catch (CrawlerException e) {
            log.error("크롤링 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("예상치 못한 크롤링 오류 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error during crawling for corporation: " + corporation.getName(), e) {};
        } finally {
            if (!isDriverProvided) {
                webDriverConfig.forceCloseWebDriver(driver);
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
                .orElseThrow(() -> new CrawlerNotFoundException(blogUrl));
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