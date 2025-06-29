package com.newcodes7.small_town.crawler.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class MediumCrawlerSimpleTest {
    
    @Autowired
    private MediumBlogCrawler mediumCrawler;
    
    @Test
    public void testCanHandleMediumUrl() {
        // Netflix Medium 블로그 URL 테스트
        String netflixUrl = "https://netflixtechblog.medium.com/";
        assertTrue(mediumCrawler.canHandle(netflixUrl), "Medium 크롤러가 Netflix Medium URL을 처리할 수 있어야 함");
        
        // 일반 Medium URL 테스트
        String generalMediumUrl = "https://medium.com/@someuser";
        assertTrue(mediumCrawler.canHandle(generalMediumUrl), "Medium 크롤러가 일반 Medium URL을 처리할 수 있어야 함");
        
        // Medium이 아닌 URL 테스트
        String nonMediumUrl = "https://example.com/blog";
        assertFalse(mediumCrawler.canHandle(nonMediumUrl), "Medium 크롤러가 Medium이 아닌 URL은 처리하지 않아야 함");
        
        // null URL 테스트
        assertFalse(mediumCrawler.canHandle(null), "Medium 크롤러가 null URL은 처리하지 않아야 함");
    }
    
    @Test
    public void testProviderName() {
        assertEquals("Medium", mediumCrawler.getProviderName(), "Medium 크롤러의 제공자 이름이 'Medium'이어야 함");
    }
}