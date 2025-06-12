package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.service.BlogCrawler;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class TistoryCrawler implements BlogCrawler {
    
    @Override
    public boolean canHandle(String blogUrl) {
        return blogUrl != null && blogUrl.contains("tistory.com");
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        try {
            driver.get(corporation.getBlogLink());
            Thread.sleep(2000); // 페이지 로딩 대기
            
            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);
            
            Elements articleElements = doc.select("article, .item_post, .post-item, .entry-content");
            
            for (Element element : articleElements) {
                try {
                    Article article = parseArticle(element, corporation);
                    if (article != null) {
                        articles.add(article);
                    }
                } catch (Exception e) {
                    log.warn("티스토리 블로그 개별 아티클 파싱 실패: {}", e.getMessage());
                }
            }
            
            log.info("티스토리 크롤링 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
            
        } catch (Exception e) {
            log.error("티스토리 크롤링 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        }
        
        return articles;
    }
    
    private Article parseArticle(Element element, Corporation corporation) {
        try {
            Element titleElement = element.selectFirst("h1, h2, h3, .title, .post-title, a[href]");
            if (titleElement == null) return null;
            
            String title = titleElement.text().trim();
            if (title.isEmpty()) return null;
            
            String link = titleElement.attr("href");
            if (link.isEmpty()) {
                Element linkElement = element.selectFirst("a[href]");
                if (linkElement != null) {
                    link = linkElement.attr("href");
                }
            }
            
            if (!link.startsWith("http")) {
                if (link.startsWith("/")) {
                    link = corporation.getBlogLink() + link;
                } else {
                    return null;
                }
            }
            
            String summary = "";
            Element summaryElement = element.selectFirst(".summary, .excerpt, .description, p");
            if (summaryElement != null) {
                summary = summaryElement.text().trim();
                if (summary.length() > 200) {
                    summary = summary.substring(0, 200) + "...";
                }
            }
            
            String thumbnailImage = "";
            Element imgElement = element.selectFirst("img");
            if (imgElement != null) {
                thumbnailImage = imgElement.attr("src");
            }
            
            return Article.builder()
                    .corporationId(corporation.getId())
                    .title(title)
                    .summary(summary)
                    .link(link)
                    .thumbnailImage(thumbnailImage)
                    .publishedAt(LocalDateTime.now())
                    .build();
                    
        } catch (Exception e) {
            log.warn("티스토리 아티클 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public String getProviderName() {
        return "Tistory";
    }
}