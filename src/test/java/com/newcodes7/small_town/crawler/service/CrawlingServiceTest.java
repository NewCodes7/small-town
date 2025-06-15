package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.config.CrawlerProperties;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.dto.CrawlingStats;
import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CrawlingService 단위 테스트")
class CrawlingServiceTest {

    @Mock
    private CrawlerCorporationRepository crawlerCorporationRepository;

    @Mock
    private CrawlerArticleRepository crawlerArticleRepository;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private CrawlerProperties crawlerProperties;

    @Mock
    private WebDriver webDriver;

    @Mock
    private BlogCrawler mockCrawler;

    @InjectMocks
    private CrawlingService crawlingService;

    private Corporation testCorporation;
    private List<Article> mockArticles;

    @BeforeEach
    void setUp() {
        testCorporation = Corporation.builder()
                .id(1L)
                .name("테스트 기업")
                .blogLink("https://test-blog.com")
                .build();

        mockArticles = createMockArticles();
    }

    @Test
    @DisplayName("크롤링이 비활성화된 경우 빈 리스트 반환")
    void crawlAllBlogs_크롤링_비활성화() {
        // given
        when(crawlerProperties.isEnabled()).thenReturn(false);

        // when
        List<CrawlResult> result = crawlingService.crawlAllBlogs();

        // then
        assertThat(result).isEmpty();
        verify(crawlerCorporationRepository, never()).findAllWithBlogLink();
    }

    @Test
    @DisplayName("전체 블로그 크롤링 성공") 
    void crawlAllBlogs_성공() throws Exception {
        // given
        when(crawlerProperties.isEnabled()).thenReturn(true);
        List<Corporation> corporations = Arrays.asList(testCorporation);
        when(crawlerCorporationRepository.findAllWithBlogLink()).thenReturn(corporations);
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(testCorporation);
        when(applicationContext.getBean(WebDriver.class)).thenReturn(webDriver);
        when(applicationContext.getBeansOfType(BlogCrawler.class))
                .thenReturn(Map.of("mockCrawler", mockCrawler));
        when(mockCrawler.canHandle(anyString())).thenReturn(true);
        when(mockCrawler.getProviderName()).thenReturn("MockCrawler");
        when(mockCrawler.crawl(any(WebDriver.class), any(Corporation.class))).thenReturn(mockArticles);
        when(crawlerArticleRepository.findByLinkAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(crawlerArticleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        List<CrawlResult> results = crawlingService.crawlAllBlogs();

        // then
        assertThat(results).hasSize(1);
        CrawlResult result = results.get(0);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCorporation().getName()).isEqualTo("테스트 기업");
        assertThat(result.getNewArticles()).isEqualTo(2);
        verify(crawlerArticleRepository, times(2)).save(any(Article.class));
    }

    @Test
    @DisplayName("단일 기업 크롤링 성공")
    void crawlSingleBlog_성공() throws Exception {
        // given
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(testCorporation);
        when(applicationContext.getBean(WebDriver.class)).thenReturn(webDriver);
        when(applicationContext.getBeansOfType(BlogCrawler.class))
                .thenReturn(Map.of("mockCrawler", mockCrawler));
        when(mockCrawler.canHandle(anyString())).thenReturn(true);
        when(mockCrawler.getProviderName()).thenReturn("MockCrawler");
        when(mockCrawler.crawl(any(WebDriver.class), any(Corporation.class))).thenReturn(mockArticles);
        when(crawlerArticleRepository.findByLinkAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(crawlerArticleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCorporation().getName()).isEqualTo("테스트 기업");
        assertThat(result.getNewArticles()).isEqualTo(2);
        verify(crawlerArticleRepository, times(2)).save(any(Article.class));
    }

    @Test
    @DisplayName("존재하지 않는 기업 크롤링 시 실패")
    void crawlSingleBlog_존재하지_않는_기업() {
        // given
        when(crawlerCorporationRepository.findByIdAndNotDeleted(999L)).thenReturn(null);

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(999L);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("기업을 찾을 수 없습니다");
        verify(crawlerArticleRepository, never()).save(any());
    }

    @Test
    @DisplayName("크롤링 중 예외 발생 시 실패 처리")
    void crawlSingleBlog_크롤링_예외() throws Exception {
        // given
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(testCorporation);
        when(applicationContext.getBean(WebDriver.class)).thenReturn(webDriver);
        when(applicationContext.getBeansOfType(BlogCrawler.class))
                .thenReturn(Map.of("mockCrawler", mockCrawler));
        when(mockCrawler.canHandle(anyString())).thenReturn(true);
        when(mockCrawler.getProviderName()).thenReturn("MockCrawler");
        when(mockCrawler.crawl(any(WebDriver.class), any(Corporation.class)))
                .thenThrow(new RuntimeException("크롤링 오류"));

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("크롤링 오류");
        verify(crawlerArticleRepository, never()).save(any());
    }

    @Test
    @DisplayName("중복 기사 저장 방지")
    void crawlSingleBlog_중복_기사_방지() throws Exception {
        // given
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(testCorporation);
        when(applicationContext.getBean(WebDriver.class)).thenReturn(webDriver);
        when(applicationContext.getBeansOfType(BlogCrawler.class))
                .thenReturn(Map.of("mockCrawler", mockCrawler));
        when(mockCrawler.canHandle(anyString())).thenReturn(true);
        when(mockCrawler.getProviderName()).thenReturn("MockCrawler");
        when(mockCrawler.crawl(any(WebDriver.class), any(Corporation.class))).thenReturn(mockArticles);
        
        // 첫 번째 기사는 이미 존재, 두 번째는 새로운 기사
        when(crawlerArticleRepository.findByLinkAndDeletedAtIsNull("https://test.com/article1"))
                .thenReturn(Optional.of(new Article()));
        when(crawlerArticleRepository.findByLinkAndDeletedAtIsNull("https://test.com/article2"))
                .thenReturn(Optional.empty());
        when(crawlerArticleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L);

        // then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getNewArticles()).isEqualTo(1); // 새로운 기사 1개만
        verify(crawlerArticleRepository, times(1)).save(any(Article.class));
    }

    @Test
    @DisplayName("크롤러 선택 - 특화 크롤러 우선")
    void selectCrawler_특화_크롤러_우선() throws Exception {
        // given
        BlogCrawler defaultCrawler = mock(BlogCrawler.class);
        BlogCrawler tistoryCrawler = mock(BlogCrawler.class);
        
        when(tistoryCrawler.getProviderName()).thenReturn("Tistory");
        when(tistoryCrawler.canHandle("https://test.tistory.com")).thenReturn(true);
        when(tistoryCrawler.crawl(any(), any())).thenReturn(mockArticles);
        when(defaultCrawler.getProviderName()).thenReturn("Default");

        Corporation tistoryCorperation = Corporation.builder()
                .id(2L)
                .name("티스토리 기업")
                .blogLink("https://test.tistory.com")
                .build();

        when(crawlerCorporationRepository.findByIdAndNotDeleted(2L)).thenReturn(tistoryCorperation);
        when(applicationContext.getBean(WebDriver.class)).thenReturn(webDriver);
        when(applicationContext.getBeansOfType(BlogCrawler.class))
                .thenReturn(Map.of("defaultCrawler", defaultCrawler, "tistoryCrawler", tistoryCrawler));
        when(crawlerArticleRepository.findByLinkAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(crawlerArticleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(2L);

        // then
        assertThat(result.isSuccess()).isTrue();
        verify(tistoryCrawler).crawl(any(), any()); // 티스토리 크롤러가 사용됨
        verify(defaultCrawler, never()).crawl(any(), any()); // 기본 크롤러는 사용되지 않음
    }

    @Test
    @DisplayName("크롤링 통계 조회")
    void getCrawlingStats() {
        // given
        List<Corporation> corporations = Arrays.asList(testCorporation);
        when(crawlerCorporationRepository.findAllWithBlogLink()).thenReturn(corporations);
        when(crawlerArticleRepository.countNewArticlesByCorporation(eq(1L), any(LocalDateTime.class)))
                .thenReturn(10L);

        // when
        CrawlingStats stats = crawlingService.getCrawlingStats();

        // then
        assertThat(stats.getTotalCorporations()).isEqualTo(1);
        assertThat(stats.getTotalNewArticles()).isEqualTo(10);
        assertThat(stats.getLastCrawledAt()).isNotNull();
    }

    @Test
    @DisplayName("크롤링 통계 조회 - 기업이 없는 경우")
    void getCrawlingStats_기업_없음() {
        // given
        when(crawlerCorporationRepository.findAllWithBlogLink()).thenReturn(List.of());

        // when
        CrawlingStats stats = crawlingService.getCrawlingStats();

        // then
        assertThat(stats.getTotalCorporations()).isEqualTo(0);
        assertThat(stats.getTotalNewArticles()).isEqualTo(0);
        assertThat(stats.getLastCrawledAt()).isNotNull();
    }

    @Test
    @DisplayName("WebDriver 생성 실패 시 예외 처리")
    void crawlSingleBlog_WebDriver_생성_실패() {
        // given
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(testCorporation);
        when(applicationContext.getBean(WebDriver.class)).thenThrow(new RuntimeException("WebDriver 생성 실패"));

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L);

        // then
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("WebDriver 생성 실패");
    }

    // 테스트 헬퍼 메서드들
    private List<Article> createMockArticles() {
        return Arrays.asList(
                Article.builder()
                        .corporationId(1L)
                        .title("테스트 기사 1")
                        .link("https://test.com/article1")
                        .summary("테스트 요약 1")
                        .publishedAt(LocalDateTime.now())
                        .build(),
                Article.builder()
                        .corporationId(1L)
                        .title("테스트 기사 2")
                        .link("https://test.com/article2")
                        .summary("테스트 요약 2")
                        .publishedAt(LocalDateTime.now())
                        .build()
        );
    }

    private Article createArticleWithDate(LocalDateTime dateTime) {
        return Article.builder()
                .corporationId(1L)
                .title("날짜 테스트 기사")
                .link("https://test.com/date-article")
                .summary("날짜 테스트")
                .publishedAt(dateTime)
                .createdAt(dateTime)
                .build();
    }
}