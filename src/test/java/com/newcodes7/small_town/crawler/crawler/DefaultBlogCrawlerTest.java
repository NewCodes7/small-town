package com.newcodes7.small_town.crawler.crawler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.WebDriver;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.exception.ContentParsingException;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.utils.ArticleCreator;

/**
 * DefaultBlogCrawler 단위 테스트
 *
 * 크롤러 성공/실패 케이스, 이미지 업로드 처리, 본문 추출 경계값 검증
 */
@ExtendWith(MockitoExtension.class)
class DefaultBlogCrawlerTest {

    @Mock
    private S3ImageService s3ImageService;

    @Mock
    private ParsingSelectorRepository parsingSelectorRepository;

    @Mock
    private DefaultBlogPageNavigator pageNavigator;

    @Mock
    private WebDriver driver;

    @InjectMocks
    private DefaultBlogCrawler defaultBlogCrawler;

    private Corporation testCorporation() {
        return ArticleCreator.createCorporationWithId(1L);
    }

    private ParsingSelector defaultSelector() {
        return ParsingSelector.defaultSelector(1L);
    }

    // ==================== crawl ====================

    @Test
    @DisplayName("정상 크롤링 → pageNavigator에서 반환된 article 목록 그대로 반환")
    void crawl_정상크롤링() throws CrawlerException {
        Corporation corporation = testCorporation();
        List<Article> expected = ArticleCreator.createArticles(corporation, 3);

        when(parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId()))
                .thenReturn(defaultSelector());
        when(pageNavigator.crawlFirstPage(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class)))
                .thenReturn(expected);

        List<Article> result = defaultBlogCrawler.crawl(driver, corporation);

        assertThat(result).hasSize(3).isEqualTo(expected);
    }

    @Test
    @DisplayName("빈 결과 → 빈 리스트 반환")
    void crawl_빈결과() throws CrawlerException {
        Corporation corporation = testCorporation();

        when(parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId()))
                .thenReturn(defaultSelector());
        when(pageNavigator.crawlFirstPage(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class)))
                .thenReturn(List.of());

        List<Article> result = defaultBlogCrawler.crawl(driver, corporation);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("CrawlerException 발생 → 동일한 CrawlerException 재전파")
    void crawl_CrawlerException_재전파() throws CrawlerException {
        Corporation corporation = testCorporation();
        CrawlerException originalException = ContentParsingException.emptyContent(
                "https://test.com");

        when(parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId()))
                .thenReturn(defaultSelector());
        when(pageNavigator.crawlFirstPage(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class)))
                .thenThrow(originalException);

        assertThatThrownBy(() -> defaultBlogCrawler.crawl(driver, corporation))
                .isSameAs(originalException);
    }

    @Test
    @DisplayName("예상치 못한 예외 발생 → CrawlerException으로 래핑 후 전파")
    void crawl_예상치못한예외_래핑() throws CrawlerException {
        Corporation corporation = testCorporation();
        RuntimeException unexpected = new RuntimeException("WebDriver 연결 실패");

        when(parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId()))
                .thenReturn(defaultSelector());
        when(pageNavigator.crawlFirstPage(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class)))
                .thenThrow(unexpected);

        assertThatThrownBy(() -> defaultBlogCrawler.crawl(driver, corporation))
                .isInstanceOf(CrawlerException.class)
                .hasMessageContaining(corporation.getName());
    }

    @Test
    @DisplayName("getProviderName → 'Default' 반환")
    void getProviderName() {
        assertThat(defaultBlogCrawler.getProviderName()).isEqualTo("Default");
    }

    @Test
    @DisplayName("canHandle → 항상 true (deprecated)")
    void canHandle() {
        assertThat(defaultBlogCrawler.canHandle("https://anything.com")).isTrue();
    }

    // ==================== processImageUpload ====================

    @Test
    @DisplayName("http URL 썸네일 → S3 업로드 후 article 썸네일 교체")
    void processImageUpload_http_URL_S3업로드() {
        Corporation corporation = testCorporation();
        Article article = Article.builder()
                .id(1L).title("Test").link("https://example.com")
                .thumbnailImage("http://origin.com/image.jpg")
                .build();
        String s3Url = "https://s3.example.com/image.jpg";
        when(s3ImageService.uploadImageFromUrl(anyString(), anyString())).thenReturn(s3Url);

        defaultBlogCrawler.processImageUpload(article, corporation);

        assertThat(article.getThumbnailImage()).isEqualTo(s3Url);
        verify(s3ImageService).uploadImageFromUrl("http://origin.com/image.jpg", corporation.getName());
    }

    @Test
    @DisplayName("null 썸네일 → S3 업로드 미호출")
    void processImageUpload_null썸네일() {
        Corporation corporation = testCorporation();
        Article article = Article.builder()
                .id(1L).title("Test").link("https://example.com")
                .thumbnailImage(null)
                .build();

        defaultBlogCrawler.processImageUpload(article, corporation);

        verifyNoInteractions(s3ImageService);
    }

    @Test
    @DisplayName("빈 썸네일 → S3 업로드 미호출")
    void processImageUpload_빈썸네일() {
        Corporation corporation = testCorporation();
        Article article = Article.builder()
                .id(1L).title("Test").link("https://example.com")
                .thumbnailImage("")
                .build();

        defaultBlogCrawler.processImageUpload(article, corporation);

        verifyNoInteractions(s3ImageService);
    }

    @Test
    @DisplayName("http로 시작하지 않는 URL → S3 업로드 미호출")
    void processImageUpload_비http_URL() {
        Corporation corporation = testCorporation();
        Article article = Article.builder()
                .id(1L).title("Test").link("https://example.com")
                .thumbnailImage("data:image/png;base64,iVBORw0KGgo=")
                .build();

        defaultBlogCrawler.processImageUpload(article, corporation);

        verifyNoInteractions(s3ImageService);
    }

    @Test
    @DisplayName("S3 업로드 실패 → 예외 미전파, 원본 URL 유지")
    void processImageUpload_S3업로드실패_예외미전파() {
        Corporation corporation = testCorporation();
        String originalUrl = "https://origin.com/image.jpg";
        Article article = Article.builder()
                .id(1L).title("Test").link("https://example.com")
                .thumbnailImage(originalUrl)
                .build();
        when(s3ImageService.uploadImageFromUrl(anyString(), anyString()))
                .thenThrow(new RuntimeException("S3 연결 실패"));

        assertThatNoException().isThrownBy(
                () -> defaultBlogCrawler.processImageUpload(article, corporation));

        // 업로드 실패 시 원본 URL 그대로 유지
        assertThat(article.getThumbnailImage()).isEqualTo(originalUrl);
    }

    // ==================== extractArticleContent ====================

    @Test
    @DisplayName("null URL → 빈 문자열 반환")
    void extractArticleContent_null_URL() {
        String result = defaultBlogCrawler.extractArticleContent(null, driver);

        assertThat(result).isEmpty();
        verifyNoInteractions(driver);
    }

    @Test
    @DisplayName("빈 URL → 빈 문자열 반환")
    void extractArticleContent_빈_URL() {
        String result = defaultBlogCrawler.extractArticleContent("   ", driver);

        assertThat(result).isEmpty();
        verifyNoInteractions(driver);
    }

    // ==================== crawlAllPages ====================

    @Test
    @DisplayName("crawlAllPages → pageNavigator.crawlAllPages 위임")
    void crawlAllPages_위임() throws CrawlerException {
        Corporation corporation = testCorporation();
        List<Article> expected = ArticleCreator.createArticles(corporation, 5);

        when(parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId()))
                .thenReturn(defaultSelector());
        when(pageNavigator.crawlAllPages(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class)))
                .thenReturn(expected);

        List<Article> result = defaultBlogCrawler.crawlAllPages(driver, corporation);

        assertThat(result).hasSize(5).isEqualTo(expected);
    }
}
