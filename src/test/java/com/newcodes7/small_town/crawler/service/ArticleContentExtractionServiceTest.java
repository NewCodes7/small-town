package com.newcodes7.small_town.crawler.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ContentExtractor;

import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.BlogType;
import com.newcodes7.small_town.global.entity.Corporation;

/**
 * ArticleContentExtractionService 테스트
 * 
 * Content 추출 서비스의 핵심 로직 검증:
 * - 단일 Article 본문 추출
 * - WebDriver 재사용 추출
 * - 배치 추출
 * - 예외 처리
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
class ArticleContentExtractionServiceTest {

    @Autowired
    private ArticleContentExtractionService contentExtractionService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    @MockBean
    private ContentExtractor contentExtractor;

    @MockBean
    private WebDriverConfig webDriverConfig;

    @MockBean
    private WebDriver mockDriver;

    @Test
    @DisplayName("단일 Article 본문 추출 - 성공")
    void extractContentForSingleArticle_Success() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "https://test.com/article");

        when(webDriverConfig.createWebDriver()).thenReturn(mockDriver);
        when(contentExtractor.extractCleanContent(eq(article.getLink()), any(WebDriver.class)))
            .thenReturn("This is the extracted content from the article.");

        // When
        Map<String, Object> result = contentExtractionService.extractContentForSingleArticle(article.getId());

        // Then
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("articleId")).isEqualTo(article.getId());
        assertThat(result.get("contentLength")).isEqualTo(47);
        assertThat(result.get("message")).asString().contains("본문 추출 성공");

        verify(webDriverConfig).createWebDriver();
        verify(webDriverConfig).forceCloseWebDriver(mockDriver);
        verify(contentExtractor).extractCleanContent(eq(article.getLink()), any(WebDriver.class));
    }

    @Test
    @DisplayName("단일 Article 본문 추출 - 존재하지 않는 Article")
    void extractContentForSingleArticle_ArticleNotFound() {
        // Given
        Long nonExistentId = 999999L;

        // When
        Map<String, Object> result = contentExtractionService.extractContentForSingleArticle(nonExistentId);

        // Then
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("message")).asString().contains("Article을 찾을 수 없습니다");

        verify(webDriverConfig, never()).createWebDriver();
    }

    @Test
    @DisplayName("단일 Article 본문 추출 - link가 비어있음")
    void extractContentForSingleArticle_EmptyLink() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "");

        // When
        Map<String, Object> result = contentExtractionService.extractContentForSingleArticle(article.getId());

        // Then
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("message")).asString().contains("link가 비어있습니다");

        verify(webDriverConfig, never()).createWebDriver();
    }

    @Test
    @DisplayName("단일 Article 본문 추출 - 추출된 내용이 빈 문자열")
    void extractContentForSingleArticle_EmptyContent() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "https://test.com/article");

        when(webDriverConfig.createWebDriver()).thenReturn(mockDriver);
        when(contentExtractor.extractCleanContent(eq(article.getLink()), any(WebDriver.class)))
            .thenReturn("");

        // When
        Map<String, Object> result = contentExtractionService.extractContentForSingleArticle(article.getId());

        // Then
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("message")).asString().contains("본문 추출 실패");

        verify(webDriverConfig).forceCloseWebDriver(mockDriver);
    }

    @Test
    @DisplayName("WebDriver 재사용하여 본문 추출 - 성공")
    void extractContentWithDriver_Success() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "https://test.com/article");

        when(contentExtractor.extractCleanContent(eq(article.getLink()), eq(mockDriver)))
            .thenReturn("Extracted content with reused driver.");

        // When
        String content = contentExtractionService.extractContent(article, mockDriver);

        // Then
        assertThat(content).isEqualTo("Extracted content with reused driver.");

        verify(contentExtractor).extractCleanContent(article.getLink(), mockDriver);
        verify(webDriverConfig, never()).createWebDriver(); // 새 driver 생성하지 않음
        verify(webDriverConfig, never()).forceCloseWebDriver(any()); // 종료하지 않음
    }

    @Test
    @DisplayName("WebDriver 재사용하여 본문 추출 - driver가 null이면 새로 생성")
    void extractContentWithDriver_NullDriver() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "https://test.com/article");

        when(webDriverConfig.createWebDriver()).thenReturn(mockDriver);
        when(contentExtractor.extractCleanContent(eq(article.getLink()), any(WebDriver.class)))
            .thenReturn("Content with new driver");

        // When
        String content = contentExtractionService.extractContent(article, null);

        // Then
        assertThat(content).isEqualTo("Content with new driver");

        verify(webDriverConfig).createWebDriver();
        verify(webDriverConfig).forceCloseWebDriver(mockDriver);
    }

    @Test
    @DisplayName("WebDriver 재사용하여 본문 추출 - link가 비어있음")
    void extractContentWithDriver_EmptyLink() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", null);

        // When
        String content = contentExtractionService.extractContent(article, mockDriver);

        // Then
        assertThat(content).isEmpty();

        verify(contentExtractor, never()).extractCleanContent(anyString(), any(WebDriver.class));
    }

    @Test
    @DisplayName("본문 추출 - 예외 발생 시 빈 문자열 반환")
    void extractContent_ExceptionHandling() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "https://test.com/article");

        when(webDriverConfig.createWebDriver()).thenReturn(mockDriver);
        when(contentExtractor.extractCleanContent(eq(article.getLink()), any(WebDriver.class)))
            .thenThrow(new RuntimeException("Extraction failed"));

        // When
        String content = contentExtractionService.extractContent(article);

        // Then
        assertThat(content).isEmpty();

        verify(webDriverConfig).forceCloseWebDriver(mockDriver); // driver는 종료되어야 함
    }

    @Test
    @DisplayName("WebDriver 자동 생성 및 본문 추출 - 성공")
    void extractContent_AutoDriver_Success() {
        // Given
        Corporation corp = createTestCorporation("Test Corp", "https://test.com");
        Article article = createTestArticle(corp, "Test Article", "https://test.com/article");

        when(webDriverConfig.createWebDriver()).thenReturn(mockDriver);
        when(contentExtractor.extractCleanContent(eq(article.getLink()), eq(mockDriver)))
            .thenReturn("Auto driver content");

        // When
        String content = contentExtractionService.extractContent(article);

        // Then
        assertThat(content).isEqualTo("Auto driver content");

        verify(webDriverConfig).createWebDriver();
        verify(webDriverConfig).forceCloseWebDriver(mockDriver);
    }

    // Helper methods
    private Corporation createTestCorporation(String name, String blogUrl) {
        Corporation corp = Corporation.builder()
                .name(name)
                .blogLink(blogUrl)
                .blogType(BlogType.DEFAULT)
                .build();
        return corporationRepository.save(corp);
    }

    private Article createTestArticle(Corporation corporation, String title, String link) {
        Article article = Article.builder()
                .corporation(corporation)
                .title(title)
                .link(link)
                .summary("Test description")
                .build();
        if (link == null) {
            return article;
        }
        return articleRepository.save(article);
    }
}
