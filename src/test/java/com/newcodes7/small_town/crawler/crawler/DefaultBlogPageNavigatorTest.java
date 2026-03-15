package com.newcodes7.small_town.crawler.crawler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.test.util.ReflectionTestUtils;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

/**
 * DefaultBlogPageNavigator 단위 테스트
 * 페이지네이션 전략, URL 빌딩, 다음 페이지 확인 로직 검증
 */
@ExtendWith(MockitoExtension.class)
class DefaultBlogPageNavigatorTest {

    @Mock
    private DefaultBlogArticleParser articleParser;

    private DefaultBlogPageNavigator navigator;

    private Corporation corporation;

    @BeforeEach
    void setUp() {
        navigator = new DefaultBlogPageNavigator(articleParser);
        corporation = Corporation.builder()
                .name("테스트 기업")
                .blogLink("https://test-blog.com")
                .build();
        corporation.setId(1L);
    }

    // ===== buildPageUrl 테스트 =====

    @Test
    @DisplayName("buildPageUrl: URL_PARAMETER 타입, ?page={page} 패턴 → ?page=2 쿼리 파라미터 추가")
    void buildPageUrl_urlParameterWithQueryPattern_appendsPageParam() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .pageUrlPattern("?page={page}")
                .build();

        // when
        String result = ReflectionTestUtils.invokeMethod(navigator, "buildPageUrl",
                "https://test-blog.com", 2, selector);

        // then
        assertThat(result).isEqualTo("https://test-blog.com?page=2");
    }

    @Test
    @DisplayName("buildPageUrl: URL_PARAMETER 타입, /page/{page} 슬래시 패턴 → 경로 추가")
    void buildPageUrl_urlParameterWithSlashPattern_appendsPathSegment() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .pageUrlPattern("/page/{page}")
                .build();

        // when
        String result = ReflectionTestUtils.invokeMethod(navigator, "buildPageUrl",
                "https://test-blog.com", 3, selector);

        // then
        assertThat(result).isEqualTo("https://test-blog.com/page/3");
    }

    @Test
    @DisplayName("buildPageUrl: URL_PARAMETER 타입이지만 pattern이 null → 기본 page 파라미터 추가")
    void buildPageUrl_urlParameterWithNullPattern_usesDefaultPageParam() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .pageUrlPattern(null)
                .build();

        // when
        String result = ReflectionTestUtils.invokeMethod(navigator, "buildPageUrl",
                "https://test-blog.com", 4, selector);

        // then
        assertThat(result).isEqualTo("https://test-blog.com?page=4");
    }

    @Test
    @DisplayName("buildPageUrl: 기본 타입, 기존 쿼리스트링이 있는 URL → & 구분자로 page 추가")
    void buildPageUrl_urlWithExistingQueryString_appendsWithAmpersand() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .pageUrlPattern("?page={page}")
                .build();

        // when
        String result = ReflectionTestUtils.invokeMethod(navigator, "buildPageUrl",
                "https://test-blog.com?category=tech", 2, selector);

        // then
        assertThat(result).isEqualTo("https://test-blog.com?category=tech&page=2");
    }

    @Test
    @DisplayName("buildPageUrl: /page/N 패턴이 포함된 URL → 기존 page 경로 제거 후 새 패턴 적용")
    void buildPageUrl_urlWithExistingPagePath_normalizesBeforeBuilding() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .pageUrlPattern("?page={page}")
                .build();

        // when
        String result = ReflectionTestUtils.invokeMethod(navigator, "buildPageUrl",
                "https://test-blog.com/page/1", 2, selector);

        // then
        assertThat(result).isEqualTo("https://test-blog.com?page=2");
    }

    // ===== hasNextPage 테스트 =====

    @Test
    @DisplayName("hasNextPage: NEXT_BUTTON 타입, nextPageSelector 없음 → false 반환")
    void hasNextPage_nextButtonTypeWithNullSelector_returnsFalse() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("NEXT_BUTTON")
                .nextPageSelector(null)
                .build();
        WebDriver driver = mock(WebDriver.class);

        // when
        boolean result = ReflectionTestUtils.invokeMethod(navigator, "hasNextPage", driver, selector);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("hasNextPage: NEXT_BUTTON 타입, 다음 버튼 없는 HTML → false 반환")
    void hasNextPage_nextButtonTypeNoButtonInHtml_returnsFalse() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("NEXT_BUTTON")
                .nextPageSelector(".next-btn")
                .build();
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html><body><p>No next button</p></body></html>");

        // when
        boolean result = ReflectionTestUtils.invokeMethod(navigator, "hasNextPage", driver, selector);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("hasNextPage: NEXT_BUTTON 타입, 다음 버튼 존재 → true 반환")
    void hasNextPage_nextButtonTypeButtonPresent_returnsTrue() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("NEXT_BUTTON")
                .nextPageSelector(".next-btn")
                .build();
        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn(
                "<html><body><a class=\"next-btn\" href=\"/page/2\">다음</a></body></html>"
        );

        // when
        boolean result = ReflectionTestUtils.invokeMethod(navigator, "hasNextPage", driver, selector);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("hasNextPage: URL_PARAMETER 타입 → 항상 true 반환")
    void hasNextPage_urlParameterType_alwaysReturnsTrue() {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .build();
        WebDriver driver = mock(WebDriver.class);

        // when
        boolean result = ReflectionTestUtils.invokeMethod(navigator, "hasNextPage", driver, selector);

        // then
        assertThat(result).isTrue();
    }

    // ===== crawlAllPages 테스트 =====

    @Test
    @DisplayName("crawlAllPages: paginationType=NONE → crawlFirstPage 위임하여 결과 반환")
    void crawlAllPages_noneType_delegatesToCrawlFirstPage() throws Exception {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("NONE")
                .build();

        Article article = Article.builder()
                .title("테스트 글")
                .link("https://test-blog.com/article/1")
                .build();

        DefaultBlogPageNavigator spyNavigator = spy(navigator);
        doReturn(List.of(article)).when(spyNavigator)
                .crawlFirstPage(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class));

        WebDriver driver = mock(WebDriver.class);

        // when
        List<Article> result = spyNavigator.crawlAllPages(driver, corporation, selector);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("테스트 글");
        verify(spyNavigator).crawlFirstPage(driver, corporation, selector);
    }

    @Test
    @DisplayName("crawlAllPages: paginationType=null → crawlFirstPage 위임")
    void crawlAllPages_nullPaginationType_delegatesToCrawlFirstPage() throws Exception {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType(null)
                .build();

        DefaultBlogPageNavigator spyNavigator = spy(navigator);
        doReturn(List.of()).when(spyNavigator)
                .crawlFirstPage(any(WebDriver.class), any(Corporation.class), any(ParsingSelector.class));

        WebDriver driver = mock(WebDriver.class);

        // when
        List<Article> result = spyNavigator.crawlAllPages(driver, corporation, selector);

        // then
        assertThat(result).isEmpty();
        verify(spyNavigator).crawlFirstPage(driver, corporation, selector);
    }

    @Test
    @DisplayName("crawlAllPages: paginationType=URL_PARAMETER, 첫 페이지 빈 결과 → 빈 리스트 반환")
    void crawlAllPages_urlParameterEmptyFirstPage_returnsEmpty() throws Exception {
        // given
        ParsingSelector selector = ParsingSelector.builder()
                .paginationType("URL_PARAMETER")
                .pageUrlPattern("?page={page}")
                .build();

        // WebDriver with JavascriptExecutor support (for scroll simulation)
        WebDriver driver = mock(WebDriver.class,
                withSettings().extraInterfaces(JavascriptExecutor.class));
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Scroll simulation exits immediately (currentPos >= totalHeight)
        when(js.executeScript(anyString())).thenReturn(1000L);
        when(driver.getPageSource()).thenReturn("<html></html>");
        when(articleParser.parseArticlesFromPage(anyString(), any(Corporation.class), any(ParsingSelector.class)))
                .thenReturn(List.of());

        // when
        List<Article> result = navigator.crawlAllPages(driver, corporation, selector);

        // then
        assertThat(result).isEmpty();
    }
}
