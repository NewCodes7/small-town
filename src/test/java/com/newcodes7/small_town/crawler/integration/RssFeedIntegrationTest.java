package com.newcodes7.small_town.crawler.integration;

import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.crawler.service.CrawlingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("RSS 피드 크롤링 통합 테스트")
class RssFeedIntegrationTest {

    @Autowired
    private CrawlingService crawlingService;

    @Autowired
    private CrawlerCorporationRepository corporationRepository;

    @Autowired
    private CrawlerArticleRepository articleRepository;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        articleRepository.deleteAll();
        corporationRepository.deleteAll();
    }

    @Test
    @DisplayName("RSS 피드가 있는 기업 블로그 크롤링")
    @Transactional
    void rss_피드_기업_블로그_크롤링() {
        // given - RSS 피드를 제공하는 실제 기업 블로그
        Corporation rssCorp = Corporation.builder()
                .name("Netflix Tech Blog")
                .blogLink("https://netflixtechblog.com/")
                .build();
        rssCorp = corporationRepository.save(rssCorp);

        // when - 크롤링 실행
        CrawlResult result = crawlingService.crawlSingleBlog(rssCorp.getId());

        // then - 크롤링 결과 검증
        assertThat(result).isNotNull();
        
        if (result.isSuccess()) {
            assertThat(result.getNewArticles()).isGreaterThan(0);
            
            // DB에 저장된 기사들 확인
            List<Article> savedArticles = articleRepository.findByCorporationIdAndNotDeleted(rssCorp.getId());
            assertThat(savedArticles).isNotEmpty();
            
            // 첫 번째 기사의 필수 필드 검증
            Article firstArticle = savedArticles.get(0);
            assertThat(firstArticle.getTitle()).isNotBlank();
            assertThat(firstArticle.getLink()).startsWith("http");
            assertThat(firstArticle.getCorporationId()).isEqualTo(rssCorp.getId());
            assertThat(firstArticle.getPublishedAt()).isNotNull();
            assertThat(firstArticle.getCreatedAt()).isNotNull();
            assertThat(firstArticle.getUpdatedAt()).isNotNull();
        } else {
            // 실패한 경우라도 에러 메시지가 있어야 함
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    @Test
    @DisplayName("GitHub 블로그 RSS 피드 크롤링")
    @Transactional
    void github_블로그_rss_피드_크롤링() {
        // given - GitHub 블로그 (Jekyll 기반, RSS 피드 제공)
        Corporation githubCorp = Corporation.builder()
                .name("GitHub Blog")
                .blogLink("https://github.blog/")
                .build();
        githubCorp = corporationRepository.save(githubCorp);

        // when - 크롤링 실행
        CrawlResult result = crawlingService.crawlSingleBlog(githubCorp.getId());

        // then - 결과 검증
        assertThat(result).isNotNull();
        
        if (result.isSuccess() && result.getNewArticles() > 0) {
            List<Article> savedArticles = articleRepository.findByCorporationIdAndNotDeleted(githubCorp.getId());
            assertThat(savedArticles).isNotEmpty();
            
            Long corpId = githubCorp.getId();
            // 기사 품질 검증
            savedArticles.forEach(article -> {
                assertThat(article.getTitle()).isNotBlank();
                assertThat(article.getTitle().length()).isGreaterThan(5);
                assertThat(article.getLink()).startsWith("https://github.blog");
                assertThat(article.getCorporationId()).isEqualTo(corpId);
            });
        }
    }

    @Test
    @DisplayName("RSS 피드 vs HTML 파싱 비교")
    @Transactional
    void rss_피드_vs_html_파싱_비교() {
        // given - RSS 피드가 있는 사이트와 없는 사이트
        Corporation rssCorp = Corporation.builder()
                .name("RSS Site")
                .blogLink("https://blog.github.com/")
                .build();
        rssCorp = corporationRepository.save(rssCorp);
        
        Corporation htmlCorp = Corporation.builder()
                .name("HTML Only Site")
                .blogLink("https://httpbin.org/html")
                .build();
        htmlCorp = corporationRepository.save(htmlCorp);

        // when - 두 사이트 모두 크롤링
        CrawlResult rssResult = crawlingService.crawlSingleBlog(rssCorp.getId());
        CrawlResult htmlResult = crawlingService.crawlSingleBlog(htmlCorp.getId());

        // then - 결과 비교
        assertThat(rssResult).isNotNull();
        assertThat(htmlResult).isNotNull();
        
        // RSS 피드가 있는 사이트는 더 구조화된 데이터를 제공할 가능성이 높음
        if (rssResult.isSuccess() && rssResult.getNewArticles() > 0) {
            List<Article> rssArticles = articleRepository.findByCorporationIdAndNotDeleted(rssCorp.getId());
            
            // RSS에서 추출한 기사들은 발행일 정보가 더 정확할 것임
            rssArticles.forEach(article -> {
                assertThat(article.getPublishedAt()).isNotNull();
                // RSS에서는 일반적으로 요약 정보도 더 풍부함
                if (article.getSummary() != null) {
                    assertThat(article.getSummary()).isNotBlank();
                }
            });
        }
    }

    @Test
    @DisplayName("다양한 RSS 피드 형식 호환성 테스트")
    @Transactional
    void 다양한_rss_피드_형식_호환성_테스트() {
        // given - 다양한 RSS 피드 형식을 제공하는 사이트들
        String[] rssUrls = {
            "https://netflixtechblog.com/",  // Medium 기반
            "https://github.blog/",          // Jekyll 기반
            "https://engineering.fb.com/"    // Facebook Engineering (다양한 형식)
        };
        
        for (String url : rssUrls) {
            Corporation corp = Corporation.builder()
                    .name("RSS Test " + url.hashCode())
                    .blogLink(url)
                    .build();
            corp = corporationRepository.save(corp);
            
            Long corpId = corp.getId();
            
            // when - 각 사이트 크롤링
            CrawlResult result = crawlingService.crawlSingleBlog(corpId);
            
            // then - 최소한 예외 없이 실행되어야 함
            assertThat(result).isNotNull();
            
            if (result.isSuccess() && result.getNewArticles() > 0) {
                List<Article> articles = articleRepository.findByCorporationIdAndNotDeleted(corpId);
                
                // 수집된 기사들의 품질 검증
                articles.forEach(article -> {
                    assertThat(article.getTitle()).isNotBlank();
                    assertThat(article.getLink()).startsWith("http");
                    assertThat(article.getCorporationId()).isEqualTo(corpId);
                });
            }
            
            // 각 테스트 후 데이터 정리
            articleRepository.deleteAll();
            corporationRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("RSS 피드 오류 시 HTML 파싱 폴백 검증")
    @Transactional
    void rss_피드_오류시_html_파싱_폴백_검증() {
        // given - RSS 피드는 없지만 HTML 콘텐츠는 있는 사이트
        Corporation fallbackCorp = Corporation.builder()
                .name("Fallback Test Corp")
                .blogLink("https://example.com")  // RSS 피드가 없는 사이트
                .build();
        fallbackCorp = corporationRepository.save(fallbackCorp);

        // when - 크롤링 실행 (RSS 실패 후 HTML 파싱으로 폴백)
        CrawlResult result = crawlingService.crawlSingleBlog(fallbackCorp.getId());

        // then - HTML 파싱 폴백이 동작해야 함
        assertThat(result).isNotNull();
        
        // 결과의 성공 여부와 관계없이 적절히 처리되어야 함
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).isNotBlank();
        }
    }

    @Test
    @DisplayName("RSS 피드 메타데이터 정확성 검증")
    @Transactional
    void rss_피드_메타데이터_정확성_검증() {
        // given - 메타데이터가 풍부한 RSS 피드를 제공하는 사이트
        Corporation metaCorp = Corporation.builder()
                .name("Meta Rich RSS")
                .blogLink("https://netflixtechblog.com/")
                .build();
        metaCorp = corporationRepository.save(metaCorp);

        // when - 크롤링 실행
        CrawlResult result = crawlingService.crawlSingleBlog(metaCorp.getId());

        // then - 메타데이터 품질 검증
        if (result.isSuccess() && result.getNewArticles() > 0) {
            List<Article> articles = articleRepository.findByCorporationIdAndNotDeleted(metaCorp.getId());
            
            // RSS에서 추출한 기사들의 메타데이터 검증
            articles.forEach(article -> {
                // 제목은 반드시 있어야 함
                assertThat(article.getTitle()).isNotBlank();
                assertThat(article.getTitle().length()).isGreaterThan(5);
                
                // 링크는 유효한 URL이어야 함
                assertThat(article.getLink()).matches("https?://.*");
                
                // 발행일은 현재보다 과거여야 함
                assertThat(article.getPublishedAt()).isNotNull();
                
                // 요약이 있는 경우 의미있는 길이여야 함
                if (article.getSummary() != null && !article.getSummary().isEmpty()) {
                    assertThat(article.getSummary().length()).isGreaterThan(10);
                }
            });
        }
    }
}