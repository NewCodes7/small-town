package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.dto.RobotsTxtRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RobotsTxtParsingTest {
    
    private RobotsTxtService robotsTxtService;
    
    @BeforeEach
    void setUp() {
        robotsTxtService = new RobotsTxtService();
    }
    
    @Test
    void testMediumRobotsTxtParsing() {
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
            License: https://medium.com/license.xml
            """;
            
        // parseRobotsTxt 메서드를 리플렉션으로 호출
        RobotsTxtRule rule = (RobotsTxtRule) ReflectionTestUtils.invokeMethod(
            robotsTxtService, "parseRobotsTxt", mediumRobotsTxt);
        
        // User-Agent: * 규칙이 적용되어야 함
        assertThat(rule.getUserAgent()).isEqualTo("*");
        
        // Disallow 규칙들 확인 (일부만 체크)
        List<String> disallowedPaths = rule.getDisallowedPaths();
        assertThat(disallowedPaths).contains("/m/", "/me/", "/media/", "/trending");
        
        // Allow 규칙들 확인 (일부만 체크)
        List<String> allowedPaths = rule.getAllowedPaths();
        assertThat(allowedPaths).contains("/_/", "/_/api/users/*/meta");
        
        // Sitemap 확인
        assertThat(rule.getSitemapUrl()).isEqualTo("https://netflixtechblog.medium.com/sitemap/sitemap.xml");
        
        System.out.println("User-Agent: " + rule.getUserAgent());
        System.out.println("Disallowed paths: " + disallowedPaths.size());
        System.out.println("Allowed paths: " + allowedPaths.size());
        System.out.println("Sitemap: " + rule.getSitemapUrl());
    }
}