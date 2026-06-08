package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.term.service.RelatedContentKeywordService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.WebDriver;
import org.springframework.test.util.ReflectionTestUtils;

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

    @BeforeEach
    void setUp() {
        // 테스트에서 Thread.sleep 대기를 제거하여 빠른 실행
        ReflectionTestUtils.setField(contentExtractor, "pageLoadWaitMs", 0);
    }

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

    // === 추가 edge case ===

    @Test
    @DisplayName("본문 추출 - 잘못된 HTML 구조여도 빈 문자열 반환 (예외 없음)")
    void extractCleanContent_MalformedHtml_returnsEmpty() {
        // given: 유효하지 않은 HTML (p 태그 미닫힘, body 없음 등)
        String url = "https://test.com/malformed";
        String html = "<html><head><title>Broken</head><div><p>unclosed para<p>another";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of());

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then: Jsoup이 자동으로 보정하므로 예외 없이 결과 반환
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("본문 추출 - 관련 글 키워드 대소문자 무시 매칭")
    void extractCleanContent_RelatedContentCaseInsensitive() throws Exception {
        // given
        String url = "https://test.com/case-insensitive";
        StringBuilder longContent = new StringBuilder();
        longContent.append("<html><body><article>");
        for (int i = 0; i < 20; i++) {
            longContent.append("<p>Content paragraph ").append(i).append(" with sufficient text to pass threshold.</p>");
        }
        longContent.append("<p>RELATED POSTS</p><p>More reading suggestions</p>");
        longContent.append("</article></body></html>");

        when(driver.getPageSource()).thenReturn(longContent.toString());
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of("related posts"));

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then: 대소문자 무시로 "RELATED POSTS" 제거
        assertThat(result).doesNotContainIgnoringCase("RELATED POSTS");
    }

    @Test
    @DisplayName("본문 추출 - 복수 관련 글 키워드 중 가장 먼저 나타나는 것으로 제거")
    void extractCleanContent_MultipleRelatedKeywords_earliestRemoved() throws Exception {
        // given
        String url = "https://test.com/multiple-keywords";
        StringBuilder longContent = new StringBuilder();
        longContent.append("<html><body><article>");
        for (int i = 0; i < 15; i++) {
            longContent.append("<p>Main content paragraph ").append(i).append(" here. Enough text to exceed half.</p>");
        }
        longContent.append("<p>추천 글</p>");
        longContent.append("<p>More content after first keyword</p>");
        longContent.append("<p>관련 콘텐츠</p>");
        longContent.append("</article></body></html>");

        when(driver.getPageSource()).thenReturn(longContent.toString());
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of("관련 콘텐츠", "추천 글"));

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then: "추천 글"이 먼저 나오므로 그 이후 전부 제거
        assertThat(result).doesNotContain("추천 글");
        assertThat(result).doesNotContain("관련 콘텐츠");
    }

    @Test
    @DisplayName("본문 추출 - 빈 블록 요소는 결과에 포함되지 않음")
    void extractCleanContent_EmptyBlockElements_filtered() throws Exception {
        // given
        String url = "https://test.com/empty-blocks";
        String html = "<html><body><article>" +
                "<p>실제 내용</p>" +
                "<p></p>" +
                "<p>   </p>" +
                "<p>두 번째 내용</p>" +
                "</article></body></html>";
        when(driver.getPageSource()).thenReturn(html);
        when(relatedContentKeywordService.getAllKeywordStrings()).thenReturn(List.of());

        // when
        String result = contentExtractor.extractCleanContent(url, driver);

        // then: 빈 문단이 결과에 빈 줄로 포함되지 않음
        assertThat(result).contains("실제 내용");
        assertThat(result).contains("두 번째 내용");
        assertThat(result).doesNotContain("\n\n\n"); // 3개 이상의 연속 줄바꿈 없음
    }
}
