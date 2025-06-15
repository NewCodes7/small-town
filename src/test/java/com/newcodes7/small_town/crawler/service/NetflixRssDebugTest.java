package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.WebDriver;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Netflix RSS 피드 디버그 테스트")
class NetflixRssDebugTest {

    @Mock
    private WebDriver webDriver;

    @InjectMocks
    private DefaultBlogCrawler defaultBlogCrawler;

    @Test
    @DisplayName("Netflix 기술 블로그 RSS 피드 디버그")
    void netflix_rss_피드_디버그() throws Exception {
        // given - Netflix 기술 블로그 URL들
        String[] netflixUrls = {
            "https://netflixtechblog.com/",
            "https://netflixtechblog.medium.com/",
            "https://medium.com/netflix-techblog"
        };
        
        for (String blogUrl : netflixUrls) {
            Corporation corp = Corporation.builder()
                    .id(1L)
                    .name("Netflix Tech Blog")
                    .blogLink(blogUrl)
                    .build();
            
            System.out.println("\n=== 테스트 URL: " + blogUrl + " ===");
            
            try {
                // when - 크롤링 실행
                List<Article> articles = defaultBlogCrawler.crawl(webDriver, corp);
                
                // then - 결과 출력
                System.out.println("크롤링 결과: " + articles.size() + "개 기사");
                
                if (!articles.isEmpty()) {
                    System.out.println("첫 번째 기사:");
                    Article first = articles.get(0);
                    System.out.println("  제목: " + first.getTitle());
                    System.out.println("  링크: " + first.getLink());
                    System.out.println("  요약: " + (first.getSummary() != null ? first.getSummary().substring(0, Math.min(100, first.getSummary().length())) + "..." : "없음"));
                    System.out.println("  발행일: " + first.getPublishedAt());
                }
                
                assertThat(articles).isNotNull();
                
            } catch (Exception e) {
                System.out.println("크롤링 실패: " + e.getMessage());
                // 실패해도 테스트는 계속 진행
            }
        }
    }

    @Test
    @DisplayName("Medium RSS 피드 직접 테스트")
    void medium_rss_피드_직접_테스트() {
        // given - Medium 기반 Netflix 블로그의 알려진 RSS URL들
        String[] possibleRssUrls = {
            "https://netflixtechblog.medium.com/feed",
            "https://medium.com/netflix-techblog/feed",
            "https://netflixtechblog.com/feed",
            "https://netflixtechblog.com/rss"
        };
        
        Corporation corp = Corporation.builder()
                .id(1L)
                .name("Netflix")
                .blogLink("https://netflixtechblog.medium.com/")
                .build();
        
        DefaultBlogCrawler crawler = new DefaultBlogCrawler();
        
        for (String rssUrl : possibleRssUrls) {
            System.out.println("\n=== RSS URL 테스트: " + rssUrl + " ===");
            
            try {
                // RSS 피드 직접 파싱 시도 (reflection을 통해 private 메서드 호출)
                java.lang.reflect.Method parseFeedMethod = DefaultBlogCrawler.class.getDeclaredMethod("parseFeed", String.class, Corporation.class);
                parseFeedMethod.setAccessible(true);
                
                @SuppressWarnings("unchecked")
                List<Article> articles = (List<Article>) parseFeedMethod.invoke(crawler, rssUrl, corp);
                
                System.out.println("RSS 파싱 성공: " + articles.size() + "개 기사");
                
                if (!articles.isEmpty()) {
                    Article first = articles.get(0);
                    System.out.println("  제목: " + first.getTitle());
                    System.out.println("  링크: " + first.getLink());
                }
                
            } catch (Exception e) {
                System.out.println("RSS 파싱 실패: " + e.getMessage());
                if (e.getCause() != null) {
                    System.out.println("  원인: " + e.getCause().getMessage());
                }
                e.printStackTrace();
            }
        }
    }
}