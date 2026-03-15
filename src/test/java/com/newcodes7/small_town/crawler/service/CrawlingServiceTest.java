package com.newcodes7.small_town.crawler.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.WebDriver;
import org.springframework.context.ApplicationContext;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleTermService;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.crawler.BlogCrawler;
import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.exception.CorporationCrawlingException;
import com.newcodes7.small_town.crawler.integration.robotstxt.RobotsTxtService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.embedding.service.ChunkEmbeddingBatchService;
import com.newcodes7.small_town.embedding.service.RepresentativeChunkService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.service.BatchQueryService;

@ExtendWith(MockitoExtension.class)
class CrawlingServiceTest {

    @Mock
    private CrawlerCorporationRepository crawlerCorporationRepository;

    @Mock
    private CrawlerArticleRepository crawlerArticleRepository;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private RobotsTxtService robotsTxtService;

    @Mock
    private WebDriverConfig webDriverConfig;

    @Mock
    private ArticlePersistenceService articlePersistenceService;

    @Mock
    private ArticleContentExtractionService articleContentExtractionService;

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private CrawlingRunService crawlingRunService;

    @Mock
    private ArticleTermService articleTermService;

    @Mock
    private ChunkEmbeddingBatchService chunkEmbeddingBatchService;

    @Mock
    private RepresentativeChunkService representativeChunkService;

    @Mock
    private BatchQueryService batchQueryService;

    private CrawlingService crawlingService;

    @BeforeEach
    void setUp() {
        crawlingService = new CrawlingService(
                crawlerCorporationRepository,
                crawlerArticleRepository,
                applicationContext,
                robotsTxtService,
                webDriverConfig,
                articlePersistenceService,
                articleContentExtractionService,
                articleRepository,
                crawlingRunService,
                articleTermService,
                chunkEmbeddingBatchService,
                representativeChunkService,
                batchQueryService
        );
    }

    @Test
    @DisplayName("crawlAllBlogs: 신규 글 존재 시 BM25/자동완성 인덱스는 1회만 갱신")
    void crawlAllBlogs_RefreshIndexOnceWhenNewArticles() throws Exception {
        Corporation corp1 = Corporation.builder()
                .name("corp1")
                .blogLink("https://corp1.com/blog")
                .build();
        corp1.setId(1L);
        Corporation corp2 = Corporation.builder()
                .name("corp2")
                .blogLink("https://corp2.com/blog")
                .build();
        corp2.setId(2L);

        when(crawlerCorporationRepository.findAllWithBlogLink()).thenReturn(List.of(corp1, corp2));
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp1);
        when(crawlerCorporationRepository.findByIdAndNotDeleted(2L)).thenReturn(corp2);

        BlogCrawler blogCrawler = mock(BlogCrawler.class);
        when(applicationContext.getBeansOfType(BlogCrawler.class)).thenReturn(Map.of("default", blogCrawler));
        when(blogCrawler.getProviderName()).thenReturn("Default");
        when(blogCrawler.extractBaseUrl(anyString())).thenReturn("https://base");
        when(robotsTxtService.isPathAllowed(anyString(), anyString())).thenReturn(true);

        AtomicInteger seq = new AtomicInteger(0);
        when(blogCrawler.crawlWithRobotsCheck(any(), any(), any())).thenAnswer(invocation -> {
            Corporation corp = invocation.getArgument(1);
            int idx = seq.incrementAndGet();
            Article article = Article.builder()
                    .title("title-" + idx)
                    .link("https://example.com/" + idx)
                    .corporation(corp)
                    .build();
            article.setId((long) idx);
            return List.of(article);
        });

        when(crawlerArticleRepository.findExistingLinksByLinksIn(any())).thenReturn(List.of());
        when(crawlerArticleRepository.findExistingTitlesByTitlesInAndCorporationId(any(), anyLong())).thenReturn(List.of());

        WebDriver driver = mock(WebDriver.class);
        when(webDriverConfig.createWebDriver()).thenReturn(driver);

        doNothing().when(articlePersistenceService).saveArticleWithAnalysis(any(), any(), any());
        when(articleContentExtractionService.extractContent(any(), any())).thenReturn("content");
        doNothing().when(articleContentExtractionService).updateArticleContent(anyLong(), anyString());
        when(articleTermService.extractAndSaveTermsForArticle(any())).thenReturn(1);
        when(chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(any())).thenReturn(1);
        when(representativeChunkService.selectRepresentativeChunk(anyLong())).thenReturn(1L);
        doAnswer(invocation -> {
            Runnable query = invocation.getArgument(0);
            query.run();
            return null;
        }).when(batchQueryService).executeWithNoTimeout(any(Runnable.class));

        crawlingService.crawlAllBlogs();

        verify(articleRepository, times(1)).refreshArticleSearchIndex();
        verify(articleRepository, times(1)).refreshTermAutocompleteIndex();
    }

    @Test
    @DisplayName("crawlSingleBlog: 본문 실패 시에도 title 기반 term 분석 수행")
    void crawlSingleBlog_ExtractsTitleTermsWhenContentEmpty() throws Exception {
        Corporation corp = Corporation.builder()
                .name("corp")
                .blogLink("https://corp.com/blog")
                .build();
        corp.setId(1L);

        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

        BlogCrawler blogCrawler = mock(BlogCrawler.class);
        when(applicationContext.getBeansOfType(BlogCrawler.class)).thenReturn(Map.of("default", blogCrawler));
        when(blogCrawler.getProviderName()).thenReturn("Default");
        when(blogCrawler.extractBaseUrl(anyString())).thenReturn("https://base");
        when(robotsTxtService.isPathAllowed(anyString(), anyString())).thenReturn(true);

        Article article = Article.builder()
                .title("title")
                .link("https://example.com/1")
                .corporation(corp)
                .build();
        article.setId(10L);
        when(blogCrawler.crawlWithRobotsCheck(any(), any(), any())).thenReturn(List.of(article));

        when(crawlerArticleRepository.findExistingLinksByLinksIn(any())).thenReturn(List.of());
        when(crawlerArticleRepository.findExistingTitlesByTitlesInAndCorporationId(any(), anyLong())).thenReturn(List.of());

        doNothing().when(articlePersistenceService).saveArticleWithAnalysis(any(), any(), any());
        when(articleContentExtractionService.extractContent(any(), any())).thenReturn("");
        when(articleTermService.extractAndSaveTermsForArticle(any())).thenReturn(1);

        WebDriver driver = mock(WebDriver.class);

        crawlingService.crawlSingleBlog(1L, driver);

        verify(articleTermService, times(1)).extractAndSaveTermsForArticle(any());
        verify(chunkEmbeddingBatchService, never()).generateChunkEmbeddingsForArticle(any());
        verify(representativeChunkService, never()).selectRepresentativeChunk(anyLong());
        // crawlSingleBlog는 인덱스 갱신을 하지 않음 (crawlAllBlogs에서 1회 처리)
        verify(articleRepository, never()).refreshArticleSearchIndex();
        verify(articleRepository, never()).refreshTermAutocompleteIndex();
    }

    @Test
    @DisplayName("crawlSingleBlog: robots.txt 차단 시 크롤링 건너뛰고 빈 결과 반환")
    void crawlSingleBlog_RobotsTxtBlocked_returnsEmptyResult() throws Exception {
        // given
        Corporation corp = Corporation.builder()
                .name("blocked-corp")
                .blogLink("https://blocked.com/blog")
                .build();
        corp.setId(1L);

        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

        BlogCrawler blogCrawler = mock(BlogCrawler.class);
        when(applicationContext.getBeansOfType(BlogCrawler.class)).thenReturn(Map.of("default", blogCrawler));
        when(blogCrawler.getProviderName()).thenReturn("Default");
        when(blogCrawler.extractBaseUrl(anyString())).thenReturn("https://blocked.com");
        // robots.txt에 의해 크롤링 차단
        when(robotsTxtService.isPathAllowed("https://blocked.com", "/")).thenReturn(false);

        WebDriver driver = mock(WebDriver.class);

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L, driver);

        // then: 크롤링 건너뛰므로 article 수집 없음
        assertThat(result.getArticles()).isEmpty();
        verify(blogCrawler, never()).crawlWithRobotsCheck(any(), any(), any());
        verify(articlePersistenceService, never()).saveArticleWithAnalysis(any(), any(), any());
    }

    @Test
    @DisplayName("crawlSingleBlog: 중복 링크 존재 시 저장하지 않음")
    void crawlSingleBlog_DuplicateLink_skipsArticle() throws Exception {
        // given
        Corporation corp = Corporation.builder()
                .name("corp")
                .blogLink("https://corp.com/blog")
                .build();
        corp.setId(1L);

        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

        BlogCrawler blogCrawler = mock(BlogCrawler.class);
        when(applicationContext.getBeansOfType(BlogCrawler.class)).thenReturn(Map.of("default", blogCrawler));
        when(blogCrawler.getProviderName()).thenReturn("Default");
        when(blogCrawler.extractBaseUrl(anyString())).thenReturn("https://base");
        when(robotsTxtService.isPathAllowed(anyString(), anyString())).thenReturn(true);

        Article article = Article.builder()
                .title("title")
                .link("https://example.com/existing")
                .corporation(corp)
                .build();
        article.setId(10L);
        when(blogCrawler.crawlWithRobotsCheck(any(), any(), any())).thenReturn(List.of(article));

        // 이미 존재하는 링크
        when(crawlerArticleRepository.findExistingLinksByLinksIn(any())).thenReturn(List.of("https://example.com/existing"));
        when(crawlerArticleRepository.findExistingTitlesByTitlesInAndCorporationId(any(), anyLong())).thenReturn(List.of());

        WebDriver driver = mock(WebDriver.class);

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L, driver);

        // then: 중복 링크이므로 저장하지 않음
        assertThat(result.getArticles()).isEmpty();
        verify(articlePersistenceService, never()).saveArticleWithAnalysis(any(), any(), any());
    }

    @Test
    @DisplayName("crawlSingleBlog: 중복 제목 존재 시 저장하지 않음")
    void crawlSingleBlog_DuplicateTitle_skipsArticle() throws Exception {
        // given
        Corporation corp = Corporation.builder()
                .name("corp")
                .blogLink("https://corp.com/blog")
                .build();
        corp.setId(1L);

        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

        BlogCrawler blogCrawler = mock(BlogCrawler.class);
        when(applicationContext.getBeansOfType(BlogCrawler.class)).thenReturn(Map.of("default", blogCrawler));
        when(blogCrawler.getProviderName()).thenReturn("Default");
        when(blogCrawler.extractBaseUrl(anyString())).thenReturn("https://base");
        when(robotsTxtService.isPathAllowed(anyString(), anyString())).thenReturn(true);

        Article article = Article.builder()
                .title("Duplicate Title")
                .link("https://example.com/new")
                .corporation(corp)
                .build();
        article.setId(10L);
        when(blogCrawler.crawlWithRobotsCheck(any(), any(), any())).thenReturn(List.of(article));

        when(crawlerArticleRepository.findExistingLinksByLinksIn(any())).thenReturn(List.of());
        // 이미 존재하는 제목
        when(crawlerArticleRepository.findExistingTitlesByTitlesInAndCorporationId(any(), anyLong())).thenReturn(List.of("Duplicate Title"));

        WebDriver driver = mock(WebDriver.class);

        // when
        CrawlResult result = crawlingService.crawlSingleBlog(1L, driver);

        // then: 중복 제목이므로 저장하지 않음
        assertThat(result.getArticles()).isEmpty();
        verify(articlePersistenceService, never()).saveArticleWithAnalysis(any(), any(), any());
    }

    @Test
    @DisplayName("crawlSingleBlog: Corporation 없음 → CorporationCrawlingException")
    void crawlSingleBlog_CorporationNotFound_throwsException() {
        // given
        when(crawlerCorporationRepository.findByIdAndNotDeleted(999L)).thenReturn(null);

        WebDriver driver = mock(WebDriver.class);

        // when / then
        assertThatThrownBy(() -> crawlingService.crawlSingleBlog(999L, driver))
                .isInstanceOf(CorporationCrawlingException.class);
    }

    @Test
    @DisplayName("crawlSingleBlog: blogLink 없음 → CorporationCrawlingException")
    void crawlSingleBlog_NoBlogLink_throwsException() {
        // given
        Corporation corp = Corporation.builder()
                .name("no-blog-corp")
                .build();
        corp.setId(1L);
        when(crawlerCorporationRepository.findByIdAndNotDeleted(1L)).thenReturn(corp);

        WebDriver driver = mock(WebDriver.class);

        // when / then
        assertThatThrownBy(() -> crawlingService.crawlSingleBlog(1L, driver))
                .isInstanceOf(CorporationCrawlingException.class);
    }
}
