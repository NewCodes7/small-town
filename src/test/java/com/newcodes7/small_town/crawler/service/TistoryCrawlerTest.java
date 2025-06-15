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
@DisplayName("TistoryCrawler 단위 테스트")
class TistoryCrawlerTest {

    @Mock
    private WebDriver webDriver;

    @InjectMocks
    private TistoryCrawler tistoryCrawler;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        testCorporation = Corporation.builder()
                .id(1L)
                .name("티스토리 블로그")
                .blogLink("https://test.tistory.com")
                .build();
    }

    @Test
    @DisplayName("티스토리 URL만 처리 가능")
    void canHandle_티스토리_URL_처리() {
        assertThat(tistoryCrawler.canHandle("https://test.tistory.com")).isTrue();
        assertThat(tistoryCrawler.canHandle("http://blog.tistory.com")).isTrue();
        assertThat(tistoryCrawler.canHandle("https://sub.tistory.com/path")).isTrue();
        
        assertThat(tistoryCrawler.canHandle("https://blog.naver.com")).isFalse();
        assertThat(tistoryCrawler.canHandle("https://medium.com")).isFalse();
        assertThat(tistoryCrawler.canHandle(null)).isFalse();
        assertThat(tistoryCrawler.canHandle("")).isFalse();
    }

    @Test
    @DisplayName("프로바이더 이름 Tistory 반환")
    void getProviderName_Tistory_반환() {
        assertThat(tistoryCrawler.getProviderName()).isEqualTo("Tistory");
    }

    @Test
    @DisplayName("티스토리 기본 구조에서 아티클 크롤링")
    void crawl_티스토리_기본_구조_파싱() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createTistoryBasicHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        verify(webDriver).get("https://test.tistory.com");
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getTitle()).isEqualTo("티스토리 첫 번째 포스트");
        assertThat(result.get(0).getLink()).isEqualTo("https://test.tistory.com/entry/first-post");
        assertThat(result.get(0).getSummary()).isEqualTo("티스토리 포스트의 요약입니다.");
        assertThat(result.get(0).getCorporationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("티스토리 다양한 CSS 클래스 처리")
    void crawl_티스토리_다양한_클래스_처리() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createTistoryVariousClassHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result).extracting(Article::getTitle)
                .contains("아이템 포스트", "포스트 아이템", "엔트리 콘텐츠");
    }

    @Test
    @DisplayName("상대 경로 링크를 절대 경로로 변환")
    void crawl_상대경로_절대경로_변환() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createTistoryRelativePathHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLink()).isEqualTo("https://test.tistory.com/entry/relative-path");
    }

    @Test
    @DisplayName("썸네일 이미지 추출")
    void crawl_썸네일_이미지_추출() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createTistoryImageHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getThumbnailImage()).isNotEmpty();
    }

    @Test
    @DisplayName("요약 텍스트 200자 제한")
    void crawl_요약_길이_제한() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createTistoryLongSummaryHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSummary()).hasSize(203); // 200 + "..."
        assertThat(result.get(0).getSummary()).endsWith("...");
    }

    @Test
    @DisplayName("잘못된 HTML에서도 안전하게 처리")
    void crawl_잘못된_HTML_안전_처리() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createMalformedHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("WebDriver 예외 발생 시 예외 전파")
    void crawl_WebDriver_예외_전파() throws Exception {
        // given
        doThrow(new RuntimeException("티스토리 접근 오류")).when(webDriver).get(anyString());
        
        // when & then
        assertThatThrownBy(() -> tistoryCrawler.crawl(webDriver, testCorporation))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("티스토리 접근 오류");
    }

    @Test
    @DisplayName("제목이 없는 경우 필터링")
    void crawl_제목_없는_경우_필터링() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createNoTitleHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("링크가 없는 경우 필터링")
    void crawl_링크_없는_경우_필터링() throws Exception {
        // given
        when(webDriver.getPageSource()).thenReturn(createNoLinkHtml());
        
        // when
        List<Article> result = tistoryCrawler.crawl(webDriver, testCorporation);

        // then
        assertThat(result).isEmpty();
    }

    // HTML 템플릿 메서드들
    private String createTistoryBasicHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2><a href="/entry/first-post">티스토리 첫 번째 포스트</a></h2>
                    <p class="summary">티스토리 포스트의 요약입니다.</p>
                </article>
                <div class="item_post">
                    <h3><a href="/entry/second-post">티스토리 두 번째 포스트</a></h3>
                    <div class="description">두 번째 포스트 설명입니다.</div>
                </div>
            </body>
            </html>
            """;
    }

    private String createTistoryVariousClassHtml() {
        return """
            <html>
            <body>
                <div class="item_post">
                    <h2><a href="/entry/1">아이템 포스트</a></h2>
                </div>
                <div class="post-item">
                    <h3 class="post-title"><a href="/entry/2">포스트 아이템</a></h3>
                </div>
                <div class="entry-content">
                    <h4><a href="/entry/3">엔트리 콘텐츠</a></h4>
                </div>
            </body>
            </html>
            """;
    }

    private String createTistoryRelativePathHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2><a href="/entry/relative-path">상대 경로 포스트</a></h2>
                </article>
            </body>
            </html>
            """;
    }

    private String createTistoryImageHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2><a href="/entry/with-image">이미지 포스트</a></h2>
                    <img src="https://example.com/image.jpg" alt="썸네일">
                </article>
            </body>
            </html>
            """;
    }

    private String createTistoryLongSummaryHtml() {
        String longText = "가".repeat(250);
        return String.format("""
            <html>
            <body>
                <article>
                    <h2><a href="/entry/long">긴 요약 포스트</a></h2>
                    <p class="summary">%s</p>
                </article>
            </body>
            </html>
            """, longText);
    }

    private String createMalformedHtml() {
        return """
            <html>
            <body>
                <div class="item_post">
                    <h2></h2>
                    <a></a>
                </div>
            </body>
            </html>
            """;
    }

    private String createNoTitleHtml() {
        return """
            <html>
            <body>
                <article>
                    <div class="metadata">날짜: 2024-01-01</div>
                </article>
            </body>
            </html>
            """;
    }

    private String createNoLinkHtml() {
        return """
            <html>
            <body>
                <article>
                    <h2>제목만 있음</h2>
                </article>
            </body>
            </html>
            """;
    }
}