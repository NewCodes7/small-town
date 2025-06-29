package com.newcodes7.small_town.crawler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtPathMatchingTest {
    
    private RobotsTxtService robotsTxtService;
    
    @BeforeEach
    void setUp() {
        robotsTxtService = new RobotsTxtService();
    }
    
    @Test
    void testMediumPathAllowed() {
        String mediumRobotsTxt = """
            User-Agent: *
            Disallow: /m/
            Disallow: /me/
            Disallow: /@me$
            Disallow: /@me/
            Disallow: /*/edit$
            Disallow: /*/*/edit$
            Disallow: /media/
            Disallow: /p/*/share
            Disallow: /r/
            Disallow: /trending
            Disallow: /search?q$
            Disallow: /search?q=
            Disallow: /*/search?q=
            Disallow: /*/search/*?q=
            Disallow: /*/*source=
            Allow: /_/
            Allow: /_/api/users/*/meta
            Allow: /_/api/users/*/profile/stream
            Allow: /_/api/posts/*/responses
            Allow: /_/api/posts/*/responsesStream
            Allow: /_/api/posts/*/related
            Sitemap: https://netflixtechblog.medium.com/sitemap/sitemap.xml
            User-Agent: Applebot-Extended
            User-Agent: Bytespider
            User-Agent: ClaudeBot
            User-Agent: FacebookBot
            User-Agent: GoogleOther
            User-Agent: GPTBot
            Disallow: /
            Allow: /about
            Allow: /business
            Allow: /earn
            Allow: /gift
            Allow: /membership
            Allow: /partner-program
            Allow: /verified-authors
            """;
        
        // parseRobotsTxt와 isPathAllowed를 mock하여 테스트 
        ReflectionTestUtils.setField(robotsTxtService, "robotsTxtCache", new java.util.concurrent.ConcurrentHashMap<>());
        
        // 캐시에 미리 넣어서 테스트
        var rule = ReflectionTestUtils.invokeMethod(robotsTxtService, "parseRobotsTxt", mediumRobotsTxt);
        var cache = (java.util.concurrent.ConcurrentHashMap) ReflectionTestUtils.getField(robotsTxtService, "robotsTxtCache");
        cache.put("https://netflixtechblog.medium.com", rule);
        
        // 일반적인 블로그 글 경로들 - 허용되어야 함
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/")).isTrue();
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/some-article-123")).isTrue();
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/category/tech")).isTrue();
        
        // 금지된 경로들 - 차단되어야 함
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/m/")).isFalse();
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/me/")).isFalse();
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/trending")).isFalse();
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/media/")).isFalse();
        
        // 명시적으로 허용된 API 경로들 - 허용되어야 함
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/_/")).isTrue();
        assertThat(robotsTxtService.isPathAllowed("https://netflixtechblog.medium.com", "/_/api/users/123/meta")).isTrue();
        
        System.out.println("Medium robots.txt 테스트 통과!");
        System.out.println("일반 블로그 글: 허용");
        System.out.println("관리자/개인 페이지: 금지");
        System.out.println("API 엔드포인트: 허용");
    }
}