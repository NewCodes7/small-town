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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultBlogCrawler 단위 테스트")
class DefaultBlogCrawlerTest {

    @Mock
    private WebDriver webDriver;

    @InjectMocks
    private DefaultBlogCrawler defaultBlogCrawler;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        testCorporation = Corporation.builder()
                .id(1L)
                .name("테스트 기업")
                .blogLink("https://test-blog.com")
                .build();
    }

    @Test
    @DisplayName("모든 블로그 URL 처리 가능")
    void canHandle_모든_URL_처리() {
        assertThat(defaultBlogCrawler.canHandle("https://any-blog.com")).isTrue();
        assertThat(defaultBlogCrawler.canHandle("http://example.com")).isTrue();
    }

    @Test
    @DisplayName("프로바이더 이름 Default 반환")
    void getProviderName_Default_반환() {
        assertThat(defaultBlogCrawler.getProviderName()).isEqualTo("Default");
    }

    @Test
    @DisplayName("기본 HTML 구조에서 아티클 크롤링")
    void crawl_기본_HTML_파싱() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createBasicHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        verify(webDriver).get("https://test-blog.com"); 
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTitle()).isEqualTo("첫 번째 포스트");
        assertThat(result.get(0).getLink()).isEqualTo("https://test-blog.com/article/123");
        assertThat(result.get(0).getSummary()).isEqualTo("첫 번째 포스트의 요약입니다.");
        assertThat(result.get(0).getCorporationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("다양한 CSS 클래스명 처리")
    void crawl_다양한_CSS_클래스명_처리() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createVariousClassHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result).extracting(Article::getTitle)
                .contains("블로그 포스트", "엔트리 제목", "아이템 제목");
    }

    @Test
    @DisplayName("상대 경로 링크를 절대 경로로 변환")
    void crawl_상대경로_절대경로_변환() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createRelativePathHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLink()).isEqualTo("https://test-blog.com/article/relative");
    }

    @Test
    @DisplayName("썸네일 이미지 추출")
    void crawl_썸네일_이미지_추출() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createImageHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getThumbnailImage()).isEqualTo("https://test-blog.com/images/thumb.jpg");
    }

    @Test
    @DisplayName("잘못된 HTML에서도 안전하게 처리")
    void crawl_잘못된_HTML_안전_처리() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createMalformedHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("WebDriver 예외 발생 시 예외 전파")
    void crawl_WebDriver_예외_전파() throws Exception {
        // given
        doThrow(new RuntimeException("WebDriver 오류")).when(webDriver).get(anyString());
        
        // when & then
        assertThatThrownBy(() -> defaultBlogCrawler.crawl(webDriver, testCorporation))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("WebDriver 오류");
    }

    @Test
    @DisplayName("제목이 너무 짧은 경우 필터링")
    void crawl_짧은_제목_필터링() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createShortTitleHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("요약 텍스트 길이 제한")
    void crawl_요약_길이_제한() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createLongSummaryHtml());
        
        // when
        List<Article> result = defaultBlogCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSummary()).hasSize(203); // 200 + "..."
        assertThat(result.get(0).getSummary()).endsWith("...");
    }

    // HTML 템플릿 메서드들
    private String createBasicHtml() {
        return """
            <html>
            <body>
                <div class="post">
                    <h2><a href="/article/123">첫 번째 포스트</a></h2>
                    <p class="excerpt">첫 번째 포스트의 요약입니다.</p>
                </div>
                <article>
                    <h3><a href="/article/456">두 번째 포스트</a></h3>
                    <div class="content">두 번째 포스트의 내용입니다.</div>
                </article>
            </body>
            </html>
            """;
    }

    private String createVariousClassHtml() {
        return """
            <html>
            <body>
                <div class="blog-post">
                    <h2><a href="/post/1">블로그 포스트</a></h2>
                </div>
                <div class="entry">
                    <h3 class="entry-title"><a href="/entry/2">엔트리 제목</a></h3>
                </div>
                <div class="item">
                    <h4><a href="/item/3">아이템 제목</a></h4>
                </div>
            </body>
            </html>
            """;
    }

    private String createRelativePathHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2><a href="/article/relative">상대 경로 포스트</a></h2>
                </article>
            </body>
            </html>
            """;
    }

    private String createImageHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2><a href="/article/with-image">이미지 포스트</a></h2>
                    <img src="/images/thumb.jpg" alt="썸네일">
                </article>
            </body>
            </html>
            """;
    }

    private String createMalformedHtml() {
        return """
            <html>
            <body>
                <div class="post">
                    <h2></h2>
                    <a></a>
                </div>
            </body>
            </html>
            """;
    }

    private String createShortTitleHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2><a href="/article/short">짧음</a></h2>
                </article>
            </body>
            </html>
            """;
    }

    private String createLongSummaryHtml() {
        String longText = "가".repeat(250);
        return String.format("""
            <html>
            <body>
                <article>
                    <h2><a href="/article/long">긴 요약 포스트</a></h2>
                    <p class="summary">%s</p>
                </article>
            </body>
            </html>
            """, longText);
    }
}