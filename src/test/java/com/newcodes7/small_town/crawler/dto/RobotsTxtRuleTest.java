package com.newcodes7.small_town.crawler.dto;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RobotsTxtRule 단위 테스트
 *
 * 경로 허용/차단 판정 로직, 와일드카드 매칭, 구체성 기반 우선순위 검증
 */
class RobotsTxtRuleTest {

    @Test
    @DisplayName("isPathAllowed: null 경로 → false")
    void isPathAllowed_nullPath_returnsFalse() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .build();

        // then
        assertThat(rule.isPathAllowed(null)).isFalse();
    }

    @Test
    @DisplayName("isPathAllowed: 규칙 없음 → 기본 허용")
    void isPathAllowed_noRules_defaultAllow() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .allowedPaths(new ArrayList<>())
                .disallowedPaths(new ArrayList<>())
                .build();

        // then
        assertThat(rule.isPathAllowed("/any/path")).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: 전체 Disallow → 모든 경로 차단")
    void isPathAllowed_disallowAll_blocksAllPaths() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .disallowedPaths(List.of("/"))
                .allowedPaths(new ArrayList<>())
                .build();

        // then
        assertThat(rule.isPathAllowed("/")).isFalse();
        assertThat(rule.isPathAllowed("/blog")).isFalse();
        assertThat(rule.isPathAllowed("/api/test")).isFalse();
    }

    @Test
    @DisplayName("isPathAllowed: Allow가 Disallow보다 구체적 → 허용")
    void isPathAllowed_allowMoreSpecific_allowed() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .disallowedPaths(List.of("/blog"))
                .allowedPaths(List.of("/blog/public"))
                .build();

        // then
        assertThat(rule.isPathAllowed("/blog")).isFalse();
        assertThat(rule.isPathAllowed("/blog/public")).isTrue();
        assertThat(rule.isPathAllowed("/blog/public/post")).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: Disallow가 Allow보다 구체적 → 차단")
    void isPathAllowed_disallowMoreSpecific_blocked() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .allowedPaths(List.of("/api"))
                .disallowedPaths(List.of("/api/private"))
                .build();

        // then
        assertThat(rule.isPathAllowed("/api")).isTrue();
        assertThat(rule.isPathAllowed("/api/public")).isTrue();
        assertThat(rule.isPathAllowed("/api/private")).isFalse();
        assertThat(rule.isPathAllowed("/api/private/secret")).isFalse();
    }

    @Test
    @DisplayName("isPathAllowed: 디렉토리 패턴 (슬래시로 끝남)")
    void isPathAllowed_directoryPattern_matchesSubpaths() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .disallowedPaths(List.of("/private/"))
                .allowedPaths(new ArrayList<>())
                .build();

        // then
        assertThat(rule.isPathAllowed("/private")).isFalse();
        assertThat(rule.isPathAllowed("/private/")).isFalse();
        assertThat(rule.isPathAllowed("/private/data")).isFalse();
        assertThat(rule.isPathAllowed("/public")).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: 동일 길이의 Allow와 Disallow → Allow 우선")
    void isPathAllowed_sameLengthAllowAndDisallow_allowWins() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .allowedPaths(List.of("/blog"))
                .disallowedPaths(List.of("/blog"))
                .build();

        // then
        assertThat(rule.isPathAllowed("/blog")).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: 매칭되지 않는 경로 → 기본 허용")
    void isPathAllowed_noMatchingRule_defaultAllow() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .disallowedPaths(List.of("/admin"))
                .allowedPaths(new ArrayList<>())
                .build();

        // then
        assertThat(rule.isPathAllowed("/blog")).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: 빈 패턴 → 매칭 안됨")
    void isPathAllowed_emptyPattern_noMatch() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .disallowedPaths(List.of(""))
                .allowedPaths(new ArrayList<>())
                .build();

        // then
        assertThat(rule.isPathAllowed("/anything")).isTrue();
    }

    @Test
    @DisplayName("isPathAllowed: 접두사 매칭 - Disallow /api는 /api/v1도 차단")
    void isPathAllowed_prefixMatching_blocksSubpaths() {
        // given
        RobotsTxtRule rule = RobotsTxtRule.builder()
                .userAgent("*")
                .disallowedPaths(List.of("/api"))
                .allowedPaths(new ArrayList<>())
                .build();

        // then
        assertThat(rule.isPathAllowed("/api")).isFalse();
        assertThat(rule.isPathAllowed("/api/v1")).isFalse();
        assertThat(rule.isPathAllowed("/api/v2/users")).isFalse();
        assertThat(rule.isPathAllowed("/apiary")).isFalse(); // 구현이 startsWith 접두사 매칭이므로 "/apiary".startsWith("/api") == true → 차단
        assertThat(rule.isPathAllowed("/other")).isTrue();
        assertThat(rule.isPathAllowed("/application")).isTrue(); // "/application".startsWith("/api") == false → 허용
    }
}
