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
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class DefaultBlogCrawler implements BlogCrawler {
    
    @Override
    public boolean canHandle(String blogUrl) {
        // 다른 크롤러가 처리하지 못하는 모든 블로그를 처리
        return true;
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        try {
            driver.get(corporation.getBlogLink());
            Thread.sleep(2000);
            
            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);
            
            // 다양한 CSS 선택자로 아티클 찾기
            Elements articleElements = doc.select(
                "article, .post, .entry, .blog-post, .item, " +
                "[class*='post'], [class*='article'], [class*='entry'], " +
                "[id*='post'], [id*='article'], [id*='entry']"
            );
            
            // RSS 피드 링크가 있는지 확인
            Element rssLink = doc.selectFirst("link[type='application/rss+xml'], link[type='application/atom+xml']");
            if (rssLink != null) {
                log.info("RSS 피드 발견: {}", rssLink.attr("href"));
                // RSS 피드 크롤링 로직 추가 가능
            }
            
            for (Element element : articleElements) {
                try {
                    Article article = parseArticle(element, corporation);
                    if (article != null) {
                        articles.add(article);
                    }
                } catch (Exception e) {
                    log.warn("기본 크롤러 개별 아티클 파싱 실패: {}", e.getMessage());
                }
            }
            
            log.info("기본 크롤러 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
            
        } catch (Exception e) {
            log.error("기본 크롤러 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        }
        
        return articles;
    }
    
    private Article parseArticle(Element element, Corporation corporation) {
        try {
            // 제목 찾기
            Element titleElement = element.selectFirst(
                "h1, h2, h3, h4, .title, .post-title, .entry-title, " +
                "[class*='title'], [class*='heading'], a[href]"
            );
            
            if (titleElement == null) return null;
            
            String title = titleElement.text().trim();
            if (title.isEmpty() || title.length() < 5) return null;
            
            // 링크 찾기
            String link = "";
            if (titleElement.tagName().equals("a")) {
                link = titleElement.attr("href");
            } else {
                Element linkElement = element.selectFirst("a[href]");
                if (linkElement != null) {
                    link = linkElement.attr("href");
                }
            }
            
            if (link.isEmpty()) return null;
            
            // 상대 경로를 절대 경로로 변환
            if (!link.startsWith("http")) {
                if (link.startsWith("/")) {
                    String baseUrl = corporation.getBlogLink();
                    if (baseUrl.endsWith("/")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    }
                    link = baseUrl + link;
                } else {
                    return null;
                }
            }
            
            // 요약 찾기
            String summary = "";
            Element summaryElement = element.selectFirst(
                ".summary, .excerpt, .description, .content, p, " +
                "[class*='summary'], [class*='excerpt'], [class*='desc']"
            );
            if (summaryElement != null) {
                summary = summaryElement.text().trim();
                if (summary.length() > 200) {
                    summary = summary.substring(0, 200) + "...";
                }
            }
            
            // 썸네일 이미지 찾기
            String thumbnailImage = "";
            Element imgElement = element.selectFirst("img");
            if (imgElement != null) {
                String imgSrc = imgElement.attr("src");
                if (!imgSrc.startsWith("http") && imgSrc.startsWith("/")) {
                    String baseUrl = corporation.getBlogLink();
                    if (baseUrl.endsWith("/")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    }
                    imgSrc = baseUrl + imgSrc;
                }
                thumbnailImage = imgSrc;
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
            log.warn("기본 크롤러 아티클 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public String getProviderName() {
        return "Default";
    }
}