package com.newcodes7.small_town.crawler.service;

import java.util.List;

import org.openqa.selenium.WebDriver;

import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

public interface BlogCrawler {
    boolean canHandle(String blogUrl);
    List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException;
    String getProviderName();

    /**
     * 모든 페이지 크롤링 (Admin 전용)
     * 기본 구현은 첫 페이지만 크롤링 (하위 호환성)
     * @param driver WebDriver 인스턴스
     * @param corporation 기업 정보
     * @return 크롤링된 글 목록
     * @throws CrawlerException 크롤링 중 오류 발생 시
     */
    default List<Article> crawlAllPages(WebDriver driver, Corporation corporation) throws CrawlerException {
        return crawl(driver, corporation);
    }
    
    /**
     * 이미지 업로드 처리 (중복이 아닌 경우에만 호출)
     * @param article 이미지 업로드할 아티클
     * @param corporation 기업 정보
     */
    default void processImageUpload(Article article, Corporation corporation) {
        // 기본 구현은 아무것도 하지 않음 (이미지 업로드 없음)
    }
    
    /**
     * robots.txt 검사 후 크롤링
     * @param driver WebDriver 인스턴스
     * @param corporation 기업 정보
     * @param robotsTxtService robots.txt 서비스
     * @return 크롤링된 글 목록
     * @throws CrawlerException 크롤링 중 오류 발생 시
     */
    default List<Article> crawlWithRobotsCheck(WebDriver driver, Corporation corporation, RobotsTxtService robotsTxtService) throws CrawlerException {
        // robots.txt 확인
        String baseUrl = extractBaseUrl(corporation.getBlogLink());
        
        // 크롤링 지연 적용
        Long crawlDelay = robotsTxtService.getCrawlDelay(baseUrl);
        if (crawlDelay != null && crawlDelay > 0) {
            try {
                Thread.sleep(crawlDelay * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("크롤링 지연 중 인터럽트 발생", e);
            }
        }
        
        return crawl(driver, corporation);
    }
    
    /**
     * URL에서 베이스 URL 추출
     */
    default String extractBaseUrl(String url) {
        if (url == null) return null;
        
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            return parsedUrl.getProtocol() + "://" + parsedUrl.getHost() + 
                   (parsedUrl.getPort() != -1 && parsedUrl.getPort() != 80 && parsedUrl.getPort() != 443 
                    ? ":" + parsedUrl.getPort() : "");
        } catch (java.net.MalformedURLException e) {
            return url;
        }
    }
}