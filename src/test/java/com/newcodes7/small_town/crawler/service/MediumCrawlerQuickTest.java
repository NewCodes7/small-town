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
public class MediumCrawlerQuickTest {
    
    private static final Logger log = LoggerFactory.getLogger(MediumCrawlerQuickTest.class);
    
    @Autowired
    private MediumBlogCrawler mediumCrawler;
    
    @Autowired
    private WebDriverConfig webDriverConfig;
    
    @Test
    public void testNetflixMediumCrawlingQuick() throws Exception {
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
                // 크롤링 실행 (최대 3개 아티클만)
                List<Article> articles = mediumCrawler.crawl(driver, netflix);
                
                log.info("=== 크롤링 결과 ===");
                log.info("수집된 아티클 수: {}", articles.size());
                
                // 처음 3개 아티클만 출력
                for (int i = 0; i < Math.min(3, articles.size()); i++) {
                    Article article = articles.get(i);
                    log.info("아티클 {}: 제목={}", 
                            i+1, article.getTitle());
                    log.info("  링크={}", article.getLink());
                    log.info("  발행일={}", article.getPublishedAt());
                    
                    // publishedAt이 null이 아닌지 확인
                    if (article.getPublishedAt() != null) {
                        log.info("  ✅ 발행일 파싱 성공!");
                    } else {
                        log.warn("  ❌ 발행일 파싱 실패");
                    }
                }
                
                // 검증
                if (articles.size() > 0) {
                    log.info("✅ Netflix Medium 크롤링 성공!");
                    
                    // 모든 아티클의 발행일이 null이 아닌지 확인
                    long validDates = articles.stream()
                        .filter(a -> a.getPublishedAt() != null)
                        .count();
                    log.info("유효한 발행일을 가진 아티클: {}/{}", validDates, articles.size());
                    
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