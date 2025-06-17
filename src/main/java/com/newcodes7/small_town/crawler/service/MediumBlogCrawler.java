package com.newcodes7.small_town.crawler.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class MediumBlogCrawler implements BlogCrawler {
    
    @Override
    public boolean canHandle(String blogUrl) {
        return blogUrl != null && blogUrl.contains("medium.com");
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        try {
            articles = crawlHtmlWithInfiniteScroll(driver, corporation);
            log.info("Medium HTML 크롤링 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
            
        } catch (Exception e) {
            log.error("Medium 크롤러 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        }
        
        return articles;
    }
    
    private List<Article> crawlHtmlWithInfiniteScroll(WebDriver driver, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        driver.get(corporation.getBlogLink());
        // Thread.sleep(5000);
        
        // WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(10));
        // JavascriptExecutor js = (JavascriptExecutor) driver;
        
        // int maxScrolls = 5;
        // for (int i = 0; i < maxScrolls; i++) {
        //     js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
        //     Thread.sleep(3000);
            
        //     try {
        //         wait.until(driver1 -> {
        //             List<WebElement> currentArticles = driver1.findElements(By.cssSelector("article"));
        //             return currentArticles.size() > articles.size();
        //         });
        //     } catch (Exception e) {
        //         log.debug("더 이상 로드할 콘텐츠가 없음: {}", i);
        //         break;
        //     }
        // }
        
        List<WebElement> articleElements = driver.findElements(By.tagName("article"));
        log.info("발견된 Medium 아티클 요소 수: {}", articleElements.size());
        
        for (WebElement element : articleElements) {
            try {
                Article article = parseArticleFromElement(element, corporation, driver);
                if (article != null) {
                    articles.add(article);
                }
            } catch (Exception e) {
                log.warn("Medium 아티클 파싱 실패: {}", e.getMessage());
            }
        }
        
        return articles;
    }
    
    private Article parseArticleFromElement(WebElement element, Corporation corporation, WebDriver driver) {
        try {
            WebElement titleElement = null;
            try {
                titleElement = element.findElement(By.cssSelector("h2"));
            } catch (Exception e) {
                log.debug("h2 제목 찾기 실패: {}", e.getMessage());
                return null;
            }
            
            if (titleElement == null) return null;
            
            String title = titleElement.getText().trim();
            if (title.isEmpty()) return null;
            
            String link = "";
            try {
                WebElement linkElement = element.findElement(By.cssSelector("a[href]"));
                link = linkElement.getDomAttribute("href");
            } catch (Exception e) {
                log.debug("링크 찾기 실패: {}", e.getMessage());
                return null;
            }
            
            if (link == null || link.isEmpty()) return null;
            
            if (!link.startsWith("http")) {
                if (link.startsWith("/")) {
                    link = driver.getCurrentUrl() + link;
                } else {
                    return null;
                }
            }
            
            String summary = "";
            
            String thumbnailImage = "";
            try {
                List<WebElement> imgElements = element.findElements(By.cssSelector("img"));
                if (!imgElements.isEmpty()) {
                    int index = imgElements.size() >= 2 ? 1 : 0;
                    WebElement imgElement = imgElements.get(index);
                    String originalUrl = imgElement.getDomAttribute("src");
                    thumbnailImage = originalUrl.replaceAll("/resize:fill:\\d+:\\d+/", "/");
                } else {    
                    log.debug("이미지를 찾을 수 없습니다.");
                }
            } catch (Exception e) {
                log.debug("썸네일 이미지 찾기 실패: {}", e.getMessage());
            }
            
            // 발행일 찾기
            String publishedAtText = element.findElement(By.cssSelector("div.ac.r.nx")).getText().trim();
            LocalDateTime publishedAt = parseDateText(publishedAtText);
            
            return Article.builder()
                    .corporationId(corporation.getId())
                    .title(title)
                    .summary(summary)
                    .link(link)
                    .thumbnailImage(thumbnailImage)
                    .publishedAt(publishedAt)
                    .build();
                    
        } catch (Exception e) {
            log.warn("Medium 아티클 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
    
    private LocalDateTime parseDateText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        text = text.toLowerCase().trim();
        LocalDateTime now = LocalDateTime.now();
        
        // 미디엄에서 쓰이는 발행일 패턴 (e.g., "3d ago", "2h ago", "5m ago")
        Pattern simplePattern = Pattern.compile("(\\d+)([dhm])\\s*(?:ago)?");
        Matcher simpleMatcher = simplePattern.matcher(text);
        if (simpleMatcher.find()) {
            try {
                int value = Integer.parseInt(simpleMatcher.group(1));
                String unit = simpleMatcher.group(2);
                
                switch (unit) {
                    case "d":
                        return now.minusDays(value);
                    case "h":
                        return now.minusHours(value);
                    case "m":
                        return now.minusMinutes(value);
                }
            } catch (NumberFormatException e) {
                log.debug("Simple pattern parsing failed: {}", text);
            }
        }
                
        // Absolute date patterns (e.g., Oct 15, 2023)
        Pattern absolutePattern = Pattern.compile("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+(\\d{1,2}),?\\s+(\\d{4})");
        Matcher absoluteMatcher = absolutePattern.matcher(text);
        if (absoluteMatcher.find()) {
            try {
                String monthStr = absoluteMatcher.group(1);
                int day = Integer.parseInt(absoluteMatcher.group(2));
                int year = Integer.parseInt(absoluteMatcher.group(3));
                
                int month = getMonthNumber(monthStr);

                LocalDateTime parsedDate = LocalDateTime.of(year, month, day, 0, 0);
                ZonedDateTime koreanTime = parsedDate.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("Asia/Seoul"));
                LocalDateTime adjustedDate = koreanTime.toLocalDateTime();
                
                return adjustedDate;
            } catch (Exception e) {
                log.debug("Absolute date parsing failed: {}", text);
            }
        }

        // Absolute date patterns (e.g., Oct 15, OCT 15)
        absolutePattern = Pattern.compile(
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+(\\d{1,2})", 
            Pattern.CASE_INSENSITIVE
        );
        absoluteMatcher = absolutePattern.matcher(text.toLowerCase());
        if (absoluteMatcher.find()) {
            try {
                String monthStr = absoluteMatcher.group(1);
                int day = Integer.parseInt(absoluteMatcher.group(2));
                int month = getMonthNumber(monthStr);
                int year = LocalDateTime.now().getYear();
                
                if (day < 1 || day > 31) {
                    log.debug("Invalid day: {}", day);
                    return null;
                }
                
                return LocalDateTime.of(year, month, day, 0, 0);
            } catch (Exception e) {
                log.debug("Absolute date parsing failed: {}", text, e);
                return null;
            }
        }
        
        return null;
    }
    
    private int getMonthNumber(String monthStr) {
        switch (monthStr.toLowerCase()) {
            case "jan": return 1;
            case "feb": return 2;
            case "mar": return 3;
            case "apr": return 4;
            case "may": return 5;
            case "jun": return 6;
            case "jul": return 7;
            case "aug": return 8;
            case "sep": return 9;
            case "oct": return 10;
            case "nov": return 11;
            case "dec": return 12;
            default: return 1;
        }
    }
    
    @Override
    public String getProviderName() {
        return "Medium";
    }
}