package com.newcodes7.small_town.crawler.integration.robotstxt;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.newcodes7.small_town.crawler.dto.RobotsTxtRule;

import static org.mockito.Mockito.*;

/**
 * RobotsTxtService 단위 테스트
 *
 * 비활성화 플래그, 캐싱, parseRobotsTxt, normalizeBaseUrl,
 * findRulesForUserAgent 등 핵심 분기를 검증
 */
@ExtendWith(MockitoExtension.class)
class RobotsTxtServiceTest {

    @Spy
    private RobotsTxtService service;

    @BeforeEach
    void setUp() {
        // 기본적으로 robots 기능 활성화
        ReflectionTestUtils.setField(service, "robotsEnabled", true);
    }

    // ===== robotsEnabled=false 테스트 =====

    @Test
    @DisplayName("getRobotsTxtRules: robots 비활성화 → 기본 허용 규칙 반환")
    void getRobotsTxtRules_robotsDisabled_returnsDefaultAllow() {
        // given
        ReflectionTestUtils.setField(service, "robotsEnabled", false);

        // when
        RobotsTxtRule rule = service.getRobotsTxtRules("https://example.com");

        // then: 기본 허용이므로 모든 경로 허용
        assertThat(rule).isNotNull();
        assertThat(rule.getUserAgent()).isEqualTo("*");
        assertThat(rule.isPathAllowed("/anything")).isTrue();
    }

    // ===== null/빈 baseUrl 테스트 =====

    @Test
    @DisplayName("getRobotsTxtRules: null baseUrl → 기본 허용")
    void getRobotsTxtRules_nullBaseUrl_returnsDefaultAllow() {
        RobotsTxtRule rule = service.getRobotsTxtRules(null);
        assertThat(rule.isPathAllowed("/")).isTrue();
    }

    @Test
    @DisplayName("getRobotsTxtRules: 빈 baseUrl → 기본 허용")
    void getRobotsTxtRules_emptyBaseUrl_returnsDefaultAllow() {
        RobotsTxtRule rule = service.getRobotsTxtRules("");
        assertThat(rule.isPathAllowed("/")).isTrue();
    }

    // ===== parseRobotsTxt 테스트 =====

    @Test
    @DisplayName("parseRobotsTxt: Disallow 규칙 파싱")
    void parseRobotsTxt_disallowRules() {
        // given
        String content = "User-agent: *\n" +
                "Disallow: /private\n" +
                "Disallow: /admin\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then
        assertThat(rule.isPathAllowed("/public")).isTrue();
        assertThat(rule.isPathAllowed("/private")).isFalse();
        assertThat(rule.isPathAllowed("/admin")).isFalse();
    }

    @Test
    @DisplayName("parseRobotsTxt: Allow + Disallow 규칙 조합")
    void parseRobotsTxt_allowAndDisallow() {
        // given
        String content = "User-agent: *\n" +
                "Disallow: /api/\n" +
                "Allow: /api/public/\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then: Allow가 더 구체적이므로 /api/public/ 허용
        assertThat(rule.isPathAllowed("/api/public/docs")).isTrue();
        assertThat(rule.isPathAllowed("/api/internal")).isFalse();
    }

    @Test
    @DisplayName("parseRobotsTxt: SmallTownBot 전용 규칙 우선 적용")
    void parseRobotsTxt_specificUserAgent() {
        // given
        String content = "User-agent: *\n" +
                "Disallow: /\n" +
                "\n" +
                "User-agent: SmallTownBot\n" +
                "Disallow: /private-only\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then: SmallTownBot 규칙 적용 → /private-only만 차단
        assertThat(rule.getUserAgent()).isEqualTo("SmallTownBot");
        assertThat(rule.isPathAllowed("/public")).isTrue();
        assertThat(rule.isPathAllowed("/private-only")).isFalse();
    }

    @Test
    @DisplayName("parseRobotsTxt: User-agent가 없으면 기본 허용")
    void parseRobotsTxt_noUserAgent_defaultAllow() {
        // given
        String content = "# This is a comment\nSitemap: https://example.com/sitemap.xml\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then: 적용할 규칙 없으면 기본 허용
        assertThat(rule.isPathAllowed("/")).isTrue();
    }

    @Test
    @DisplayName("parseRobotsTxt: Crawl-delay 파싱")
    void parseRobotsTxt_crawlDelay() {
        // given
        String content = "User-agent: *\n" +
                "Crawl-delay: 5\n" +
                "Disallow: /slow\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then
        assertThat(rule.getCrawlDelay()).isEqualTo(5L);
    }

    @Test
    @DisplayName("parseRobotsTxt: Crawl-delay 잘못된 값 → 무시")
    void parseRobotsTxt_invalidCrawlDelay_ignored() {
        // given
        String content = "User-agent: *\n" +
                "Crawl-delay: abc\n" +
                "Disallow: /blocked\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then
        assertThat(rule.getCrawlDelay()).isNull();
        assertThat(rule.isPathAllowed("/blocked")).isFalse();
    }

    @Test
    @DisplayName("parseRobotsTxt: 주석과 빈 줄은 무시")
    void parseRobotsTxt_commentsAndEmptyLines() {
        // given
        String content = "# This is a comment\n" +
                "\n" +
                "User-agent: *\n" +
                "# Another comment\n" +
                "Disallow: /secret\n" +
                "\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then
        assertThat(rule.isPathAllowed("/public")).isTrue();
        assertThat(rule.isPathAllowed("/secret")).isFalse();
    }

    @Test
    @DisplayName("parseRobotsTxt: Sitemap 전역 설정")
    void parseRobotsTxt_sitemapUrl() {
        // given
        String content = "User-agent: *\n" +
                "Disallow: /private\n" +
                "Sitemap: https://example.com/sitemap.xml\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then
        assertThat(rule.getSitemapUrl()).isEqualTo("https://example.com/sitemap.xml");
    }

    @Test
    @DisplayName("parseRobotsTxt: Disallow 빈 값은 무시됨")
    void parseRobotsTxt_emptyDisallowValue_ignored() {
        // given
        String content = "User-agent: *\n" +
                "Disallow: \n" +
                "Disallow: /blocked\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then: Disallow가 비어있으면 무시, /blocked만 차단
        assertThat(rule.isPathAllowed("/")).isTrue();
        assertThat(rule.isPathAllowed("/blocked")).isFalse();
    }

    @Test
    @DisplayName("parseRobotsTxt: 여러 User-Agent 블록 중 * 규칙 적용")
    void parseRobotsTxt_multipleUserAgents_fallbackToWildcard() {
        // given
        String content = "User-agent: Googlebot\n" +
                "Disallow: /google-only\n" +
                "\n" +
                "User-agent: *\n" +
                "Disallow: /all-bots\n";

        // when
        RobotsTxtRule rule = invokeParseRobotsTxt(content);

        // then: SmallTownBot 규칙 없으므로 * 규칙 적용
        assertThat(rule.getUserAgent()).isEqualTo("*");
        assertThat(rule.isPathAllowed("/google-only")).isTrue(); // * 규칙에 포함 안 됨
        assertThat(rule.isPathAllowed("/all-bots")).isFalse();
    }

    // ===== normalizeBaseUrl 테스트 =====

    @Test
    @DisplayName("normalizeBaseUrl: 기본 HTTPS URL 정규화")
    void normalizeBaseUrl_standardUrl() {
        String result = invokeNormalizeBaseUrl("https://example.com/blog");
        assertThat(result).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("normalizeBaseUrl: 포트 80/443 생략")
    void normalizeBaseUrl_defaultPorts_omitted() {
        assertThat(invokeNormalizeBaseUrl("http://example.com:80/path"))
                .isEqualTo("http://example.com");
        assertThat(invokeNormalizeBaseUrl("https://example.com:443/path"))
                .isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("normalizeBaseUrl: 커스텀 포트 유지")
    void normalizeBaseUrl_customPort_preserved() {
        assertThat(invokeNormalizeBaseUrl("http://example.com:8080/path"))
                .isEqualTo("http://example.com:8080");
    }

    @Test
    @DisplayName("normalizeBaseUrl: 잘못된 URL → 원본 반환")
    void normalizeBaseUrl_malformedUrl_returnsOriginal() {
        assertThat(invokeNormalizeBaseUrl("not-a-url"))
                .isEqualTo("not-a-url");
    }

    // ===== getCrawlDelay 테스트 =====

    @Test
    @DisplayName("getCrawlDelay: crawl-delay 없으면 null 반환")
    void getCrawlDelay_noCrawlDelay_returnsNull() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .allowedPaths(new ArrayList<>())
                .disallowedPaths(new ArrayList<>())
                .build();
        doReturn(rule).when(service).getRobotsTxtRules("https://example.com");

        // when
        Long delay = service.getCrawlDelay("https://example.com");

        // then
        assertThat(delay).isNull();
    }

    @Test
    @DisplayName("getCrawlDelay: crawl-delay 있으면 값 반환")
    void getCrawlDelay_withCrawlDelay_returnsValue() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .allowedPaths(new ArrayList<>())
                .disallowedPaths(new ArrayList<>())
                .crawlDelay(10L)
                .build();
        doReturn(rule).when(service).getRobotsTxtRules("https://example.com");

        // when
        Long delay = service.getCrawlDelay("https://example.com");

        // then
        assertThat(delay).isEqualTo(10L);
    }

    // ===== 유틸리티 메서드 =====

    private RobotsTxtRule invokeParseRobotsTxt(String content) {
        return ReflectionTestUtils.invokeMethod(service, "parseRobotsTxt", content);
    }

    private String invokeNormalizeBaseUrl(String url) {
        return ReflectionTestUtils.invokeMethod(service, "normalizeBaseUrl", url);
    }
}
