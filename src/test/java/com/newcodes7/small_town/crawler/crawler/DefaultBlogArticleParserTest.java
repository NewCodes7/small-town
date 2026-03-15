package com.newcodes7.small_town.crawler.crawler;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

/**
 * DefaultBlogArticleParser 단위 테스트
 *
 * WebDriver 없이 HTML 문자열 / 파일 픽스처만으로 Jsoup 파싱 로직 전반을 검증.
 * - parseArticlesFromPage: toss_blog.html 픽스처 + 인라인 HTML
 * - 날짜 포맷: yyyy.M.dd / yy.MM.dd / yyyy년 MM월 dd일 / ISO8601 / 영문(MMM d yyyy 등)
 * - 링크 파싱: 절대URL / 상대URL(baseUrl 결합) / devocean onclick 패턴
 * - 썸네일: toss 특수로직 / ktcloud CSS / nhncloud CSS / gangnamunni srcset / 기본 src
 * - 엣지 케이스: title/link 없음, publish=NONE, 잘못된 날짜 텍스트, 혼합 유효/무효 항목
 */
public class DefaultBlogArticleParserTest {

    private DefaultBlogArticleParser parser;

    @BeforeEach
    void setUp() {
        parser = new DefaultBlogArticleParser();
    }

    // ──────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────

    private Corporation corporation(String blogLink) {
        return Corporation.builder()
                .id(1L)
                .name("TestCorp")
                .blogLink(blogLink)
                .build();
    }

    private ParsingSelector selector(String baseUrl, String article, String title,
            String link, String thumbnail, String publish, String publishFormat) {
        return ParsingSelector.builder()
                .baseUrl(baseUrl)
                .article(article)
                .title(title)
                .link(link)
                // null이면 "img"로 폴백 — element.selectFirst(null)은 NPE를 던져 아티클이 skip됨
                .thumbnail(thumbnail != null ? thumbnail : "img")
                .publish(publish)
                .publishFormat(publishFormat)
                .publishType("OUTER")
                .build();
    }

    private String loadHtml(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(is).as("리소스 파일 없음: " + resourcePath).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("HTML 파일 로드 실패: " + resourcePath, e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. toss_blog.html 픽스처를 사용한 실제 블로그 파싱
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toss_blog.html 픽스처 파싱")
    class TossBlogFixture {

        private ParsingSelector tossSelector() {
            return selector(
                    "https://toss.tech",
                    "a[class*='css-1qr3mg1'], a[class*='e1sck7qg4']",
                    "span[class*='typography--h6']",
                    "a[href]",
                    "img[alt*='thumbnail']",
                    "span[class*='typography--small']",
                    "yyyy.M.dd");
        }

        @Test
        @DisplayName("토스 블로그 HTML에서 20개 아티클 파싱")
        void parseTossBlog_returns20Articles() {
            String html = loadHtml("toss_blog.html");
            Corporation corp = corporation("https://toss.tech");

            List<Article> articles = parser.parseArticlesFromPage(html, corp, tossSelector());

            assertThat(articles).hasSize(20);
        }

        @Test
        @DisplayName("토스 블로그 아티클 - title/link/thumbnail/publishedAt 모두 채워짐")
        void parseTossBlog_articleFieldsPopulated() {
            String html = loadHtml("toss_blog.html");
            Corporation corp = corporation("https://toss.tech");

            List<Article> articles = parser.parseArticlesFromPage(html, corp, tossSelector());

            articles.forEach(a -> {
                assertThat(a.getTitle()).isNotBlank();
                assertThat(a.getLink()).isNotBlank().startsWith("http");
                assertThat(a.getThumbnailImage()).isNotBlank();
                assertThat(a.getPublishedAt()).isNotNull().isBefore(LocalDateTime.now().plusDays(1));
            });
        }

        @Test
        @DisplayName("토스 블로그 아티클 - corporation 참조가 설정됨")
        void parseTossBlog_corporationSet() {
            String html = loadHtml("toss_blog.html");
            Corporation corp = corporation("https://toss.tech");

            List<Article> articles = parser.parseArticlesFromPage(html, corp, tossSelector());

            articles.forEach(a -> assertThat(a.getCorporation()).isEqualTo(corp));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. 날짜 포맷별 파싱
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("날짜 포맷 파싱")
    class DateFormatParsing {

        private String articleHtml(String dateText) {
            return "<html><body>" +
                    "<div class='post'>" +
                    "  <a class='post-link' href='https://blog.example.com/article/1'>" +
                    "    <span class='post-title'>테스트 제목</span>" +
                    "    <img class='post-thumb' src='https://example.com/thumb.jpg'>" +
                    "    <span class='post-date'>" + dateText + "</span>" +
                    "  </a>" +
                    "</div>" +
                    "</body></html>";
        }

        private ParsingSelector dateSelector(String publishFormat) {
            return selector("https://blog.example.com",
                    "div.post", "span.post-title", "a.post-link",
                    "img.post-thumb", "span.post-date", publishFormat);
        }

        @Test
        @DisplayName("yyyy.M.dd 형식 파싱 (예: 2024.3.15)")
        void parseDate_yyyyMdd() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("2024.3.15"),
                    corporation("https://blog.example.com"),
                    dateSelector("yyyy.M.dd"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(15);
        }

        @Test
        @DisplayName("yyyy.MM.dd 형식 파싱 (예: 2024.03.15)")
        void parseDate_yyyyMMdd() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("2024.03.15"),
                    corporation("https://blog.example.com"),
                    dateSelector("yyyy.MM.dd"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(15);
        }

        @Test
        @DisplayName("yyyy-MM-dd 형식 파싱 (예: 2024-03-15)")
        void parseDate_yyyyDashMMDashdd() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("2024-03-15"),
                    corporation("https://blog.example.com"),
                    dateSelector("yyyy-MM-dd"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(15);
        }

        @Test
        @DisplayName("yy.MM.dd 형식 파싱 (예: 24.03.15)")
        void parseDate_yyMMdd() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("24.03.15"),
                    corporation("https://blog.example.com"),
                    dateSelector("yy.MM.dd"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(15);
        }

        @Test
        @DisplayName("yyyy년 MM월 dd일 형식 파싱")
        void parseDate_koreanFull() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("2024년 03월 15일"),
                    corporation("https://blog.example.com"),
                    dateSelector("yyyy년 MM월 dd일"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(15);
        }

        @Test
        @DisplayName("yyyy년 M월 d일 형식 파싱")
        void parseDate_koreanShort() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("2024년 3월 5일"),
                    corporation("https://blog.example.com"),
                    dateSelector("yyyy년 M월 d일"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(5);
        }

        @Test
        @DisplayName("ISO8601 형식 파싱 - datetime 속성")
        void parseDate_iso8601_datetimeAttr() {
            String html = "<html><body>" +
                    "<div class='post'>" +
                    "  <a class='post-link' href='https://blog.example.com/article/1'>" +
                    "    <span class='post-title'>테스트 제목</span>" +
                    "    <img class='post-thumb' src='https://example.com/thumb.jpg'>" +
                    "    <time class='post-date' datetime='2024-03-15T09:00:00+09:00'>2024.03.15</time>" +
                    "  </a>" +
                    "</div>" +
                    "</body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.post-title", "a.post-link",
                    "img.post-thumb", "time.post-date", "ISO8601");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(15);
        }

        @Test
        @DisplayName("영문 날짜 - MMM d, yyyy (예: Mar 5, 2024)")
        void parseDate_englishMMMdYyyy() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("Mar 5, 2024"),
                    corporation("https://blog.example.com"),
                    dateSelector("MMM d, yyyy"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(5);
        }

        @Test
        @DisplayName("영문 날짜 - MMMM d, yyyy (예: March 5, 2024)")
        void parseDate_englishFullMonthName() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("March 5, 2024"),
                    corporation("https://blog.example.com"),
                    dateSelector("MMMM d, yyyy"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(5);
        }

        @Test
        @DisplayName("영문 날짜 - dd MMM yyyy (예: 05 Mar 2024)")
        void parseDate_ddMMMYyyy() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("05 Mar 2024"),
                    corporation("https://blog.example.com"),
                    dateSelector("dd MMM yyyy"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasYear(2024).hasMonthValue(3).hasDayOfMonth(5);
        }

        @Test
        @DisplayName("영문 날짜 - dd MMM (예: 05 Mar, 연도 생략)")
        void parseDate_ddMMM() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("05 Mar"),
                    corporation("https://blog.example.com"),
                    dateSelector("dd MMM"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasMonthValue(3).hasDayOfMonth(5);
        }

        @Test
        @DisplayName("영문 날짜 - MMM dd (예: Mar 05, 연도 생략)")
        void parseDate_MMMdd() {
            List<Article> articles = parser.parseArticlesFromPage(
                    articleHtml("Mar 05"),
                    corporation("https://blog.example.com"),
                    dateSelector("MMM dd"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt())
                    .hasMonthValue(3).hasDayOfMonth(5);
        }

        @Test
        @DisplayName("publish=NONE → LocalDateTime.now() 사용")
        void parseDate_none() {
            List<Article> articles = parser.parseArticlesFromPage(
                    "<html><body>" +
                            "<div class='post'>" +
                            "  <a class='post-link' href='https://blog.example.com/article/1'>" +
                            "    <span class='post-title'>제목</span>" +
                            "    <img class='post-thumb' src='https://example.com/thumb.jpg'>" +
                            "  </a>" +
                            "</div>" +
                            "</body></html>",
                    corporation("https://blog.example.com"),
                    selector("https://blog.example.com", "div.post", "span.post-title",
                            "a.post-link", "img.post-thumb", "NONE", "yyyy.MM.dd"));

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("날짜 요소가 없으면 해당 아티클 파싱 실패 후 건너뜀")
        void parseDate_elementMissing_skipsArticle() {
            // publish 셀렉터가 있지만 HTML에 해당 요소 없음
            String html = "<html><body>" +
                    "<div class='post'>" +
                    "  <a class='post-link' href='https://blog.example.com/article/1'>" +
                    "    <span class='post-title'>제목</span>" +
                    "  </a>" +
                    "</div>" +
                    "</body></html>";

            List<Article> articles = parser.parseArticlesFromPage(
                    html, corporation("https://blog.example.com"),
                    dateSelector("yyyy.MM.dd"));

            // 날짜 요소를 찾지 못하면 예외 → 해당 항목 skip → 빈 리스트
            assertThat(articles).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. 링크 파싱
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("링크 파싱")
    class LinkParsing {

        @Test
        @DisplayName("절대 URL(http) 그대로 사용")
        void parseLink_absoluteUrl() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/article/1'>" +
                    "<span class='title'>제목</span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getLink()).isEqualTo("https://blog.example.com/article/1");
        }

        @Test
        @DisplayName("상대 URL(/) + baseUrl 결합")
        void parseLink_relativeUrl_combinedWithBaseUrl() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='/tech/article/1'>" +
                    "<span class='title'>제목</span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getLink()).isEqualTo("https://blog.example.com/tech/article/1");
        }

        @Test
        @DisplayName("상대 URL, baseUrl 끝에 / 있어도 중복 슬래시 없음")
        void parseLink_relativeUrl_baseUrlWithTrailingSlash() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='/article/1'>" +
                    "<span class='title'>제목</span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com/",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getLink()).isEqualTo("https://blog.example.com/article/1");
        }

        @Test
        @DisplayName("href가 비어있으면 해당 아티클 skip")
        void parseLink_emptyHref_skipsArticle() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href=''>" +
                    "<span class='title'>제목</span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("링크 요소가 없으면 해당 아티클 skip")
        void parseLink_noLinkElement_skipsArticle() {
            String html = "<html><body><div class='post'>" +
                    "<span class='title'>제목</span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("SK devocean - onclick 패턴에서 링크 추출")
        void parseLink_devocean_onclickPattern() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' onclick=\"goDetail(this,'12345')\" data-board-type='TECH'>" +
                    "<span class='title'>데보션 기술글</span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://devocean.sk.com/blog/view.do",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://devocean.sk.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getLink())
                    .isEqualTo("https://devocean.sk.com/blog/view.do?ID=12345&boardType=TECH");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. 썸네일 파싱
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("썸네일 파싱")
    class ThumbnailParsing {

        private String thumbHtml(String imgHtml) {
            return "<html><body><div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/article/1'>" +
                    "<span class='title'>제목</span>" +
                    imgHtml +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
        }

        @Test
        @DisplayName("기본 img src 추출")
        void parseThumbnail_defaultSrc() {
            String html = thumbHtml("<img class='thumb' src='https://example.com/image.jpg'>");
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEqualTo("https://example.com/image.jpg");
        }

        @Test
        @DisplayName("// 시작 이미지 URL → https: 붙임")
        void parseThumbnail_protocolRelativeUrl() {
            String html = thumbHtml("<img class='thumb' src='//example.com/image.jpg'>");
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEqualTo("https://example.com/image.jpg");
        }

        @Test
        @DisplayName("상대 경로 이미지 URL → blogLink 기준으로 해석")
        void parseThumbnail_relativeUrl() {
            String html = thumbHtml("<img class='thumb' src='/images/thumb.jpg'>");
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(
                    html, corporation("https://blog.example.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).contains("example.com");
        }

        @Test
        @DisplayName("ktcloud - data-tiara-image 속성에서 URL 추출")
        void parseThumbnail_ktcloud_dataTiaraImage() {
            String html = thumbHtml(
                    "<img class='thumb' data-tiara-image='https://ktcloud.com/image.jpg' src=''>");
            ParsingSelector sel = selector("https://cloud.kt.com/ktcloud/",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://cloud.kt.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEqualTo("https://ktcloud.com/image.jpg");
        }

        @Test
        @DisplayName("ktcloud - style background-image에서 URL 추출")
        void parseThumbnail_ktcloud_cssBackground() {
            String html = thumbHtml(
                    "<img class='thumb' style=\"background-image: url('https://ktcloud.com/bg.jpg')\">");
            ParsingSelector sel = selector("https://cloud.kt.com/ktcloud/blog",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://cloud.kt.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEqualTo("https://ktcloud.com/bg.jpg");
        }

        @Test
        @DisplayName("nhncloud - style background-image에서 URL 추출")
        void parseThumbnail_nhncloud_cssBackground() {
            String html = thumbHtml(
                    "<img class='thumb' style=\"background-image: url('https://nhncloud.com/img.png')\">");
            ParsingSelector sel = selector("https://meetup.nhncloud.com",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://meetup.nhncloud.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEqualTo("https://nhncloud.com/img.png");
        }

        @Test
        @DisplayName("gangnamunni - srcset 속성에서 URL 추출")
        void parseThumbnail_gangnamunni_srcset() {
            String html = thumbHtml(
                    "<img class='thumb' srcset='https://gangnamunni.com/img.jpg 2x'>");
            ParsingSelector sel = selector("https://blog.gangnamunni.com",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.gangnamunni.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEqualTo("https://gangnamunni.com/img.jpg 2x");
        }

        @Test
        @DisplayName("thumbnail 요소 없으면 빈 문자열")
        void parseThumbnail_notFound_returnsEmpty() {
            String html = thumbHtml(""); // 썸네일 없음
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", "img.thumb", "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles.get(0).getThumbnailImage()).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. 엣지 케이스
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCases {

        @Test
        @DisplayName("title 요소 없으면 해당 아티클 skip")
        void noTitle_skipsArticle() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/1'>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("title 텍스트 비어있으면 해당 아티클 skip")
        void emptyTitle_skipsArticle() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/1'>" +
                    "<span class='title'>   </span>" +
                    "<span class='date'>2024.01.01</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("빈 pageSource → 빈 리스트 반환")
        void emptyPageSource_returnsEmpty() {
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage("", corporation("https://blog.example.com"), sel);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("article 셀렉터 매칭 없으면 빈 리스트")
        void noMatchingArticles_returnsEmpty() {
            String html = "<html><body><p>내용 없음</p></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("유효/무효 아티클 혼합 - 유효한 것만 수집")
        void mixedValidInvalidArticles_returnsOnlyValid() {
            // 첫 번째: 날짜 없음(무효), 두 번째: 정상
            String html = "<html><body>" +
                    "<div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/1'>" +
                    "<span class='title'>글1</span>" +
                    // 날짜 없음
                    "</a></div>" +
                    "<div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/2'>" +
                    "<span class='title'>글2</span>" +
                    "<span class='date'>2024.03.15</span>" +
                    "</a></div>" +
                    "</body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getTitle()).isEqualTo("글2");
        }

        @Test
        @DisplayName("미래 날짜는 LocalDateTime.now()로 대체")
        void futureDateCappedToNow() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/1'>" +
                    "<span class='title'>미래 글</span>" +
                    "<span class='date'>2099.12.31</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getPublishedAt()).isBefore(LocalDateTime.now().plusMinutes(1));
        }

        @Test
        @DisplayName("파싱된 아티클 기본값 확인 - viewCount/likeCount = 0, content = 빈 문자열")
        void parsedArticle_defaultValues() {
            String html = "<html><body><div class='post'>" +
                    "<a class='post-link' href='https://blog.example.com/1'>" +
                    "<span class='title'>기본값 테스트</span>" +
                    "<span class='date'>2024.01.15</span>" +
                    "</a></div></body></html>";
            ParsingSelector sel = selector("https://blog.example.com",
                    "div.post", "span.title", "a.post-link", null, "span.date", "yyyy.MM.dd");

            List<Article> articles = parser.parseArticlesFromPage(html, corporation("https://blog.example.com"), sel);

            assertThat(articles).hasSize(1);
            Article a = articles.get(0);
            assertThat(a.getViewCount()).isEqualTo(0);
            assertThat(a.getLikeCount()).isEqualTo(0);
            assertThat(a.getContent()).isEmpty();
        }
    }
}
