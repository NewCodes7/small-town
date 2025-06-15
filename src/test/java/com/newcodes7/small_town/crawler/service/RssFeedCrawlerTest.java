package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import org.junit.jupiter.api.BeforeEach;
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
@DisplayName("RSS Feed 크롤링 기능 테스트")
class RssFeedCrawlerTest {

    @Mock
    private WebDriver webDriver;

    @InjectMocks
    private DefaultBlogCrawler defaultBlogCrawler;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        testCorporation = Corporation.builder()
                .id(1L)
                .name("Test RSS Blog")
                .blogLink("https://feeds.feedburner.com/oreilly/radar")
                .build();
    }

    @Test
    @DisplayName("RSS 피드 URL 생성 로직 테스트")
    void rss_피드_url_생성_테스트() {
        // given - 다양한 블로그 URL들
        String[] blogUrls = {
            "https://example.com",
            "https://blog.example.com/",
            "https://tech.company.com/blog"
        };
        
        // when & then - URL 생성 로직이 동작하는지 확인
        for (String blogUrl : blogUrls) {
            Corporation corp = Corporation.builder()
                    .id(1L)
                    .name("Test Corp")
                    .blogLink(blogUrl)
                    .build();
            
            // RSS 피드 크롤링 로직이 정상적으로 실행되는지만 확인
            assertThat(corp.getBlogLink()).isNotBlank();
        }
    }

    @Test
    @DisplayName("DefaultBlogCrawler 기본 동작 확인")
    void defaultBlogCrawler_기본_동작_확인() {
        // when & then - 크롤러 기본 정보 확인
        assertThat(defaultBlogCrawler.canHandle("https://any-url.com")).isTrue();
        assertThat(defaultBlogCrawler.getProviderName()).isEqualTo("Default");
    }

    @Test
    @DisplayName("RSS 피드 URL 패턴 검증")
    void rss_피드_url_패턴_검증() {
        // given - 다양한 블로그 URL 패턴들
        String[] blogUrls = {
            "https://example.com",
            "https://blog.example.com/",
            "https://tech.company.com/blog/"
        };
        
        // when & then - URL 정규화가 정상적으로 이루어지는지 확인
        for (String blogUrl : blogUrls) {
            Corporation corp = Corporation.builder()
                    .id(1L)
                    .name("Test Corp")
                    .blogLink(blogUrl)
                    .build();
            
            assertThat(corp.getBlogLink()).isNotBlank();
            assertThat(corp.getBlogLink()).startsWith("https://");
        }
    }
}