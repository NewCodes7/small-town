package com.newcodes7.small_town.crawler.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import org.openqa.selenium.WebDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootTest
@ActiveProfiles("test")
public class MediumCrawlerTest {
    
    private static final Logger log = LoggerFactory.getLogger(MediumCrawlerTest.class);
    
    @Autowired
    private MediumBlogCrawler mediumCrawler;
    
    @Autowired
    private WebDriverConfig webDriverConfig;
    
    @Test
    public void testNetflixMediumCrawling() throws Exception {
        // Netflix 테스트 기업 설정
        Corporation netflix = Corporation.builder()
                .id(1L)
                .name("Netflix Technology Blog")
                .blogLink("https://netflixtechblog.medium.com/")
                .build();
        
        WebDriver driver = null;
        try {
            driver = webDriverConfig.webDriver();
            
            // Medium 크롤러가 Netflix 블로그를 처리할 수 있는지 확인
            boolean canHandle = mediumCrawler.canHandle(netflix.getBlogLink());
            log.info("Medium 크롤러가 Netflix 블로그를 처리할 수 있는가: {}", canHandle);
            
            if (canHandle) {
                // 크롤링 실행
                List<Article> articles = mediumCrawler.crawl(driver, netflix);
                
                log.info("=== 크롤링 결과 ===");
                log.info("수집된 아티클 수: {}", articles.size());
                
                for (int i = 0; i < Math.min(5, articles.size()); i++) {
                    Article article = articles.get(i);
                    log.info("아티클 {}: 제목={}, 링크={}, 발행일={}", 
                            i+1, article.getTitle(), article.getLink(), article.getPublishedAt());
                    if (article.getSummary() != null && !article.getSummary().isEmpty()) {
                        log.info("  요약: {}", article.getSummary().substring(0, Math.min(100, article.getSummary().length())));
                    }
                }
                
                // 검증
                if (articles.size() > 0) {
                    log.info("✅ Netflix Medium 크롤링 성공!");
                } else {
                    log.warn("⚠️ 아티클을 가져오지 못했습니다.");
                }
            }
            
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }
}