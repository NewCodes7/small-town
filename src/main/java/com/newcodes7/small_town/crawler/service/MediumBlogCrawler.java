package com.newcodes7.small_town.crawler.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class MediumBlogCrawler implements BlogCrawler {
    
    private final Random random = new Random();
    
    @Override
    public boolean canHandle(String blogUrl) {
        return blogUrl != null && blogUrl.contains("medium.com");
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        try {
            // Medium bot 감지 우회를 위한 추가 설정
            setupAntiDetection(driver);
            
            driver.get(corporation.getBlogLink() + "/archive");
            
            // 페이지 로딩 완료 확인 및 bot 감지 체크
            if (checkForBotDetection(driver)) {
                log.warn("Bot 감지 페이지 발견, 우회 시도 중...");
                handleBotDetection(driver);
            }
            
            // 인간처럼 페이지 스크롤링
            simulateHumanBehavior(driver);
            
            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);
            // 디버깅용
            Files.write(Paths.get("doc.html"), doc.html().getBytes(StandardCharsets.UTF_8));

            List<Element> timebucketElements = doc.select("div[class*='timebucket']");
            for (Element timebucket : timebucketElements) {
                String link = timebucket.selectFirst("a").attr("href");
                articles.addAll(crawlHtmlWithInfiniteScroll(driver, corporation, link));
                // 2초 ~ 8초 사이의 랜덤 딜레이 (더 긴 딜레이)
                int delay = 2000 + random.nextInt(6000);
                Thread.sleep(delay);
            }

            log.info("Medium HTML 크롤링 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
        } catch (Exception e) {
            log.error("Medium 크롤러 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        }
        
        return articles;
    }
    
    private List<Article> crawlHtmlWithInfiniteScroll(WebDriver driver, Corporation corporation, String link) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        driver.get(link);
        
        // Bot 감지 체크
        if (checkForBotDetection(driver)) {
            log.warn("Bot 감지 페이지 발견, 우회 시도 중...");
            handleBotDetection(driver);
        }
        
        // 인간처럼 페이지 행동 시뮬레이션
        simulateHumanBehavior(driver);

        String pageSource = driver.getPageSource();
        Document doc = Jsoup.parse(pageSource);

        // 디버깅용
        Files.write(Paths.get("doc.html"), doc.html().getBytes(StandardCharsets.UTF_8));

        Elements articleElements = doc.select("div.postArticle[data-post-id]");

        log.info("{} 발견된 Medium 아티클 요소 수: {}", link, articleElements.size());
        
        for (Element element : articleElements) {
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
    
    private Article parseArticleFromElement(Element element, Corporation corporation, WebDriver driver) {
        try {
            Element titleElement = element.selectFirst("h3");
            if (titleElement == null) {
                log.debug("제목 요소를 찾을 수 없습니다.");
                return null;
            }
            
            String title = titleElement.text().trim();
            if (title.isEmpty()) return null;
            
            String link = "";

            // 4번째로 나온 a 태그를 선택
            Element linkElement = element.select("a").stream()
                .filter(a -> a.hasAttr("href"))
                .skip(3) // 4번째 요소 선택
                .findFirst()
                .orElse(null);
            if (linkElement == null) {
                log.debug("링크 요소를 찾을 수 없습니다.");
                return null;
            }
            
            link = linkElement.attr("href");
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
                Element imgElement = element.selectFirst("img[class*='progressiveMedia-image']");
                String originalUrl = imgElement.attr("src");
                thumbnailImage = originalUrl.replaceAll("/resize:fill:\\d+:\\d+/", "/");
            } catch (Exception e) {
                log.debug("썸네일 이미지 찾기 실패: {}", e.getMessage());
            }
            
            // 발행일 찾기
            Element timeElement = element.selectFirst("time");
            LocalDateTime publishedAt = LocalDateTime.now(); // 기본값
            
            if (timeElement != null) {
                try {
                    String datetime = timeElement.attr("datetime");
                    if (datetime != null && !datetime.isEmpty()) {
                        ZonedDateTime zonedDateTime = ZonedDateTime.parse(datetime);
                        publishedAt = zonedDateTime.toLocalDateTime();
                    }
                } catch (Exception e) {
                    log.debug("datetime 파싱 실패: {}", e.getMessage());
                    // datetime 파싱이 실패하면 현재 시간 사용
                }
            }
            
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
    
    /**
     * Bot 감지 우회를 위한 추가 설정
     */
    private void setupAntiDetection(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        
        // navigator.webdriver 속성 제거
        js.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");
        
        // Chrome 자동화 관련 속성들 제거
        js.executeScript("Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]})");
        js.executeScript("Object.defineProperty(navigator, 'languages', {get: () => ['ko-KR', 'ko', 'en-US', 'en']})");
        
        // User-Agent를 더 자연스럽게 설정
        js.executeScript("Object.defineProperty(navigator, 'userAgent', {get: () => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'})");
    }
    
    /**
     * Bot 감지 페이지가 나타났는지 확인
     */
    private boolean checkForBotDetection(WebDriver driver) {
        String pageSource = driver.getPageSource().toLowerCase();
        String currentUrl = driver.getCurrentUrl().toLowerCase();
        
        return pageSource.contains("are you a robot") || 
               pageSource.contains("human verification") ||
               pageSource.contains("verify you are human") ||
               pageSource.contains("captcha") ||
               currentUrl.contains("robot") ||
               currentUrl.contains("verify");
    }
    
    /**
     * Bot 감지 페이지 우회 시도
     */
    private void handleBotDetection(WebDriver driver) throws InterruptedException {
        log.info("Bot 감지 페이지 우회 시도 중...");
        
        // 5-10초 대기 (인간처럼)
        Thread.sleep(5000 + random.nextInt(5000));
        
        // 페이지 새로고침 시도
        driver.navigate().refresh();
        Thread.sleep(3000 + random.nextInt(2000));
        
        // 여전히 bot 감지 페이지인지 확인
        if (checkForBotDetection(driver)) {
            log.warn("Bot 감지 페이지 우회 실패, 더 긴 대기 후 재시도...");
            Thread.sleep(10000 + random.nextInt(10000));
            driver.navigate().refresh();
        }
    }
    
    /**
     * 인간처럼 페이지에서 행동하는 시뮬레이션
     */
    private void simulateHumanBehavior(WebDriver driver) throws InterruptedException {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        
        // 페이지 로딩 대기 (2-4초)
        Thread.sleep(2000 + random.nextInt(2000));
        
        // 스크롤 시뮬레이션
        for (int i = 0; i < 3; i++) {
            int scrollAmount = 200 + random.nextInt(300);
            js.executeScript("window.scrollBy(0, " + scrollAmount + ")");
            Thread.sleep(500 + random.nextInt(1000));
        }
        
        // 페이지 상단으로 스크롤 백
        js.executeScript("window.scrollTo(0, 0)");
        Thread.sleep(1000 + random.nextInt(1000));
        
        // 마우스 이동 시뮬레이션 (JavaScript로)
        js.executeScript(
            "document.dispatchEvent(new MouseEvent('mousemove', {" +
            "clientX: " + (100 + random.nextInt(800)) + ", " +
            "clientY: " + (100 + random.nextInt(600)) + 
            "}));"
        );
    }
    
    @Override
    public String getProviderName() {
        return "Medium";
    }
}