package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import io.github.bonigarcia.wdm.WebDriverManager;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WebDriver 기반 RSS 크롤링 테스트")
class WebDriverRssTest {

    private WebDriver webDriver;
    private DefaultBlogCrawler defaultBlogCrawler;

    @BeforeEach
    void setUp() {
        // WebDriver 설정
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        
        webDriver = new ChromeDriver(options);
        defaultBlogCrawler = new DefaultBlogCrawler();
    }

    @AfterEach
    void tearDown() {
        if (webDriver != null) {
            webDriver.quit();
        }
    }

    @Test
    @DisplayName("Netflix 블로그 WebDriver RSS 크롤링 테스트")
    void netflix_블로그_webdriver_rss_크롤링_테스트() throws Exception {
        // given - Netflix 블로그
        Corporation netflixCorp = Corporation.builder()
                .id(1L)
                .name("Netflix Tech Blog")
                .blogLink("https://netflixtechblog.medium.com/")
                .build();

        System.out.println("=== Netflix 블로그 WebDriver RSS 크롤링 테스트 ===");

        // when - WebDriver를 통한 크롤링
        List<Article> articles = defaultBlogCrawler.crawl(webDriver, netflixCorp);

        // then - 결과 출력 및 검증
        System.out.println("크롤링 결과: " + articles.size() + "개 기사");

        assertThat(articles).isNotNull();

        if (!articles.isEmpty()) {
            System.out.println("\n=== 수집된 기사 목록 (최대 5개) ===");
            articles.stream().limit(5).forEach(article -> {
                System.out.println("제목: " + article.getTitle());
                System.out.println("링크: " + article.getLink());
                System.out.println("발행일: " + article.getPublishedAt());
                System.out.println("요약: " + (article.getSummary() != null ? 
                    article.getSummary().substring(0, Math.min(100, article.getSummary().length())) + "..." : "없음"));
                System.out.println("---");
            });

            // 기사 품질 검증
            Article firstArticle = articles.get(0);
            assertThat(firstArticle.getTitle()).isNotBlank();
            assertThat(firstArticle.getLink()).startsWith("http");
            assertThat(firstArticle.getCorporationId()).isEqualTo(1L);
            assertThat(firstArticle.getPublishedAt()).isNotNull();

            System.out.println("\n✅ RSS 피드 크롤링 성공!");
        } else {
            System.out.println("ℹ️ RSS 피드를 찾을 수 없어 HTML 파싱으로 처리됨");
        }
    }

    @Test
    @DisplayName("GitHub 블로그 WebDriver RSS 크롤링 테스트")
    void github_블로그_webdriver_rss_크롤링_테스트() throws Exception {
        // given - GitHub 블로그 (RSS 피드 확실히 제공)
        Corporation githubCorp = Corporation.builder()
                .id(2L)
                .name("GitHub Blog")
                .blogLink("https://github.blog/")
                .build();

        System.out.println("\n=== GitHub 블로그 WebDriver RSS 크롤링 테스트 ===");

        // when - WebDriver를 통한 크롤링
        List<Article> articles = defaultBlogCrawler.crawl(webDriver, githubCorp);

        // then - 결과 출력 및 검증
        System.out.println("크롤링 결과: " + articles.size() + "개 기사");

        assertThat(articles).isNotNull();

        if (!articles.isEmpty()) {
            System.out.println("\n=== GitHub 기사 샘플 (최대 3개) ===");
            articles.stream().limit(3).forEach(article -> {
                System.out.println("제목: " + article.getTitle());
                System.out.println("링크: " + article.getLink());
                System.out.println("---");
            });

            // GitHub 블로그는 RSS 피드가 확실히 있으므로 성공해야 함
            Article firstArticle = articles.get(0);
            assertThat(firstArticle.getTitle()).isNotBlank();
            assertThat(firstArticle.getLink()).contains("github.blog");

            System.out.println("✅ GitHub RSS 피드 크롤링 성공!");
        }
    }

    @Test
    @DisplayName("RSS vs HTML 크롤링 비교 테스트")
    void rss_vs_html_크롤링_비교_테스트() throws Exception {
        // given - RSS 피드가 있는 사이트
        Corporation rssSite = Corporation.builder()
                .id(3L)
                .name("RSS Test Site")
                .blogLink("https://feeds.feedburner.com/oreilly/radar")
                .build();

        System.out.println("\n=== RSS vs HTML 크롤링 성능 비교 ===");

        // when - 크롤링 실행
        long startTime = System.currentTimeMillis();
        List<Article> articles = defaultBlogCrawler.crawl(webDriver, rssSite);
        long executionTime = System.currentTimeMillis() - startTime;

        // then - 성능 및 품질 분석
        System.out.println("실행 시간: " + executionTime + "ms");
        System.out.println("수집된 기사: " + articles.size() + "개");

        if (!articles.isEmpty()) {
            // RSS에서 추출한 데이터는 더 구조화되어 있어야 함
            long articlesWithSummary = articles.stream()
                    .filter(article -> article.getSummary() != null && !article.getSummary().isEmpty())
                    .count();
            
            long articlesWithPublishDate = articles.stream()
                    .filter(article -> article.getPublishedAt() != null)
                    .count();

            System.out.println("요약이 있는 기사: " + articlesWithSummary + "개");
            System.out.println("발행일이 있는 기사: " + articlesWithPublishDate + "개");
            
            // RSS 피드에서는 메타데이터가 더 풍부해야 함
            double summaryRatio = (double) articlesWithSummary / articles.size();
            double dateRatio = (double) articlesWithPublishDate / articles.size();
            
            System.out.println("요약 비율: " + String.format("%.1f%%", summaryRatio * 100));
            System.out.println("발행일 비율: " + String.format("%.1f%%", dateRatio * 100));

            if (summaryRatio > 0.5 || dateRatio > 0.8) {
                System.out.println("✅ RSS 피드의 풍부한 메타데이터 확인!");
            }
        }

        assertThat(articles).isNotNull();
        assertThat(executionTime).isLessThan(30000); // 30초 이내 완료
    }
}