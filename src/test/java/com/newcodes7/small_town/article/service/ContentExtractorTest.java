package com.newcodes7.small_town.article.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.WebDriver;

/**
 * ContentExtractor 단위 테스트
 * WebDriver 의존은 Mockito로 격리, Readability4J 변환 로직 검증
 */
@ExtendWith(MockitoExtension.class)
public class ContentExtractorTest {

    @Mock
    private RelatedContentKeywordService relatedContentKeywordService;

    @InjectMocks
    private ContentExtractor contentExtractor;

    @Mock
    private WebDriver driver;

    // --- extractCleanContent ---

    @Test
    @DisplayName("본문 추출 - 정상 HTML 페이지")
    void extractCleanContent_Success() throws Exception {
        // given
        String url = "https://test.com/article";
        String html = "<html><head><title>Test</title></head>" +
                "<body><article><p>첫 번째 문단입니다.</p><p>두 번째 문단입니다.</p></article></body></html>";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of());

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).isNotBlank();
        verify(driver).get(url);
    }

    @Test
    @DisplayName("본문 추출 - null 페이지 소스")
    void extractCleanContent_NullPageSource() {
        // given
        String url = "https://test.com/null-page";
        when(driver.getPageSource()).thenReturn(null);

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("본문 추출 - 빈 페이지 소스")
    void extractCleanContent_EmptyPageSource() {
        // given
        String url = "https://test.com/empty-page";
        when(driver.getPageSource()).thenReturn("");

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("본문 추출 - 공백만 있는 페이지 소스")
    void extractCleanContent_BlankPageSource() {
        // given
        String url = "https://test.com/blank-page";
        when(driver.getPageSource()).thenReturn("   ");

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("본문 추출 - WebDriver 예외 발생")
    void extractCleanContent_WebDriverException() {
        // given
        String url = "https://test.com/error-page";
        doThrow(new RuntimeException("WebDriver error")).when(driver).get(url);

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("본문 추출 - 관련 글 섹션 제거")
    void extractCleanContent_RemovesRelatedContent() throws Exception {
        // given
        String url = "https://test.com/article-with-related";
        // 50% 이후에 관련 키워드가 나오도록 충분한 본문 생성
        StringBuilder longContent = new StringBuilder();
        longContent.append("<html><body><article>");
        for (int i = 0; i < 20; i++) {
            longContent.append("<p>본문 내용 문단 ").append(i).append("입니다. 이것은 테스트 컨텐츠입니다.</p>");
        }
        longContent.append("<p>추천 콘텐츠</p><p>다른 글도 읽어보세요</p>");
        longContent.append("</article></body></html>");

        when(driver.getPageSource()).thenReturn(longContent.toString());
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of("추천 콘텐츠"));

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).doesNotContain("추천 콘텐츠");
    }

    @Test
    @DisplayName("본문 추출 - 관련 글 키워드가 50% 이전에 있으면 제거하지 않음")
    void extractCleanContent_RelatedContentBeforeThreshold() throws Exception {
        // given
        String url = "https://test.com/article-related-early";
        // 키워드가 처음에 나오도록 구성
        String html = "<html><body><article>" +
                "<p>추천 콘텐츠란 무엇인가에 대한 글입니다.</p>" +
                "<p>더 많은 내용이 여기에 있습니다.</p>" +
                "</article></body></html>";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of("추천 콘텐츠"));

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        // 50% 이전이므로 제거되지 않아야 함
        assertThat(result).contains("추천 콘텐츠");
    }

    @Test
    @DisplayName("본문 추출 - 관련 글 키워드 빈 목록")
    void extractCleanContent_EmptyKeywordList() throws Exception {
        // given
        String url = "https://test.com/article-no-keywords";
        String html = "<html><body><article><p>본문입니다.</p></article></body></html>";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of());

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).isNotBlank();
    }

    @Test
    @DisplayName("본문 추출 - 다양한 HTML 블록 요소 처리")
    void extractCleanContent_MultipleBlockElements() throws Exception {
        // given
        String url = "https://test.com/article-blocks";
        String html = "<html><body><article>" +
                "<h1>제목</h1>" +
                "<p>문단1</p>" +
                "<h2>소제목</h2>" +
                "<p>문단2</p>" +
                "<ul><li>항목1</li><li>항목2</li></ul>" +
                "<blockquote>인용문</blockquote>" +
                "<pre>코드블록</pre>" +
                "</article></body></html>";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of());

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).contains("제목");
        assertThat(result).contains("문단1");
        assertThat(result).contains("문단2");
    }

    @Test
    @DisplayName("본문 추출 - 문단 간 이중 줄바꿈 구분")
    void extractCleanContent_ParagraphSeparation() throws Exception {
        // given
        String url = "https://test.com/article-paragraphs";
        String html = "<html><body><article>" +
                "<p>첫 번째 문단</p><p>두 번째 문단</p><p>세 번째 문단</p>" +
                "</article></body></html>";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of());

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then
        assertThat(result).contains("\n\n");
    }
}
