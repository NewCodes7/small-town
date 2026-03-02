package com.newcodes7.small_town.crawler.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.newcodes7.small_town.crawler.integration.robotstxt.RobotsTxtService;
import com.newcodes7.small_town.crawler.persistence.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerCorporationRepository;
import com.newcodes7.small_town.embedding.service.ChunkEmbeddingBatchService;
import com.newcodes7.small_town.embedding.service.RepresentativeChunkService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

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
                representativeChunkService
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

        when(crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(crawlerArticleRepository.existsByTitleAndCorporationIdAndDeletedAtIsNull(anyString(), anyLong())).thenReturn(false);

        WebDriver driver = mock(WebDriver.class);
        when(webDriverConfig.createWebDriver()).thenReturn(driver);

        doNothing().when(articlePersistenceService).saveArticleWithAnalysis(any(), any(), any());
        when(articleContentExtractionService.extractContent(any(), any())).thenReturn("content");
        doNothing().when(articleContentExtractionService).updateArticleContent(anyLong(), anyString());
        when(articleTermService.extractAndSaveTermsForArticle(any())).thenReturn(1);
        when(chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(any())).thenReturn(1);
        when(representativeChunkService.selectRepresentativeChunk(anyLong())).thenReturn(1L);

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

        when(crawlerArticleRepository.findFirstByLinkAndDeletedAtIsNull(anyString())).thenReturn(Optional.empty());
        when(crawlerArticleRepository.existsByTitleAndCorporationIdAndDeletedAtIsNull(anyString(), anyLong())).thenReturn(false);

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
}
