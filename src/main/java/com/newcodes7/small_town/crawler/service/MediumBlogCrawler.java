package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerTimeoutException;
import com.newcodes7.small_town.service.S3ImageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class MediumBlogCrawler implements BlogCrawler {
    
    private final Random random = new Random();
    private final S3ImageService s3ImageService;
    
    @Override
    public boolean canHandle(String blogUrl) {
        return blogUrl != null 
                && (blogUrl.contains("medium.com") 
                    || blogUrl.contains("netflixtechblog.com") 
                    || blogUrl.contains("yogiyo.co.kr")
                );
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException {
        List<Article> articles = new ArrayList<>();
        
        try {
            // Medium bot 감지 우회를 위한 추가 설정
            setupAntiDetection(driver);
            
            // driver.get(corporation.getBlogLink() + "/archive");
            
            // 페이지 로딩 완료 확인 및 bot 감지 체크
            // if (checkForBotDetection(driver)) {
            //     log.warn("Bot 감지 페이지 발견, 우회 시도 중...");
            //     handleBotDetection(driver);
            // }
            
            // // 인간처럼 페이지 스크롤링
            // simulateHumanBehavior(driver);
            
            // String pageSource = driver.getPageSource();
            // Document doc = Jsoup.parse(pageSource);
            // List<Element> timebucketElements = doc.select("div[class*='timebucket']");
            // for (Element timebucket : timebucketElements) {
            //     String link = timebucket.selectFirst("a").attr("href");
            //     articles.addAll(crawlHtmlWithInfiniteScroll(driver, corporation, link));
            //     // 2초 ~ 8초 사이의 랜덤 딜레이 (더 긴 딜레이)
            //     int delay = 2000 + random.nextInt(6000);
            //     Thread.sleep(delay);
            // }

            articles = crawlHtmlWithInfiniteScroll(driver, corporation, corporation.getBlogLink());


            log.info("Medium HTML 크롤링 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
        } catch (CrawlerException e) {
            log.error("Medium 크롤러 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Medium 크롤러 예상치 못한 오류 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error in MediumBlogCrawler for corporation: " + corporation.getName(), e) {};
        }
        
        return articles;
    }
    
    private List<Article> crawlHtmlWithInfiniteScroll(WebDriver driver, Corporation corporation, String link) throws CrawlerException {
        List<Article> articles = new ArrayList<>();
        
        try {
            driver.get(link);
            
            // 페이지 파일로 저장하기
            String pageSource2 = driver.getPageSource();
            String filePath = "src/main/resources/medium_page.html";
            Files.write(Paths.get(filePath), pageSource2.getBytes(StandardCharsets.UTF_8));
            log.info("Medium 페이지 저장 완료: {}", filePath);
            // // Bot 감지 체크
            // while (checkForBotDetection(driver)) {
            //     log.warn("Bot 감지 페이지 발견, 우회 시도 중...");
            //     handleBotDetection(driver);
            // }
            
            // 인간처럼 페이지 행동 시뮬레이션
            simulateHumanBehavior(driver);

            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);

            Elements articleElements = doc.select("article[data-testid='post-preview']");

            log.info("{} 발견된 Medium 아티클 요소 수: {}", link, articleElements.size());
            
            for (Element element : articleElements) {
                try {
                    Article article = parseArticleFromElement(element, corporation, driver);
                    if (article != null) {
                        if (articles.stream().anyMatch(a -> a.getTitle().equals(article.getTitle()))) {
                            articles.remove(articles.stream()
                                                    .filter(a -> a.getTitle().equals(article.getTitle()))
                                                    .findFirst()
                                                    .orElse(null));
                        }
                        articles.add(article);
                    }
                } catch (Exception e) {
                    log.warn("Medium 아티클 파싱 실패: {}", e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(link, 10);
        } catch (IOException e) {
            log.error("Medium 페이지 저장 실패: {}", e.getMessage());
            throw new CrawlerException("CRAWLER_IO_ERROR", "Failed to save Medium page source for corporation: " + corporation.getName(), e) {};
        }
        
        return articles;
    }
    
    private Article parseArticleFromElement(Element element, Corporation corporation, WebDriver driver) {
        try {
            Element titleElement = element.selectFirst("h2");
            if (titleElement == null) {
                log.debug("제목 요소를 찾을 수 없습니다.");
                return null;
            }
            
            String title = titleElement.text().trim();
            if (title.isEmpty()) return null;
            
            String link = "";

            // 4번째로 나온 a 태그를 선택
            Element linkElement = element.selectFirst("div[role='link']");
            if (linkElement == null) {
                log.debug("링크 요소를 찾을 수 없습니다.");
                return null;
            }
            
            link = linkElement.attr("data-href");
            if (link == null || link.isEmpty()) return null;
            
            if (!link.startsWith("http")) {
                if (link.startsWith("/")) {
                    link = "https://medium.com" + link;
                } else {
                    return null;
                }
            }
            
            String summary = "";
            
            String thumbnailImage = "";
            try {
                Element imgElement = element.selectFirst("img[class*='mq fi']");
                if (imgElement == null) {
                    imgElement = element.selectFirst("img[class*='mi fi']");
                }
                if (imgElement == null) {
                    imgElement = element.selectFirst("img[class*='ms fi']");
                }
                if (imgElement == null) {
                    imgElement = element.selectFirst("img[class*='mr fi']");
                }
                String originalUrl = imgElement.attr("src");
                thumbnailImage = originalUrl.replaceAll("/resize:fill:\\d+:\\d+/", "/");
            } catch (Exception e) {
                log.debug("썸네일 이미지 찾기 실패: {}", e.getMessage());
            }
            
            // 발행일 찾기
            Element timeElement = element.selectFirst("span[class*='y ez']");
            LocalDateTime publishedAt = parseDateText(timeElement.text().trim());
            
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
    
    @Override
    public void processImageUpload(Article article, Corporation corporation) {
        String originalImageUrl = article.getThumbnailImage();
        
        if (originalImageUrl != null && !originalImageUrl.isEmpty() && originalImageUrl.startsWith("http")) {
            try {
                String s3ImageUrl = s3ImageService.uploadImageFromUrl(originalImageUrl, corporation.getName());
                article.setThumbnailImage(s3ImageUrl);
                log.debug("이미지 S3 업로드 성공: {} -> {}", originalImageUrl, s3ImageUrl);
            } catch (Exception e) {
                log.warn("썸네일 이미지 업로드 실패: {} - {}", originalImageUrl, e.getMessage());
                // S3 업로드 실패 시 원본 URL 그대로 유지
            }
        }
    }

    private LocalDateTime parseDateText(String text) {
        LocalDateTime now = LocalDateTime.now();
        if (text == null || text.isEmpty()) {
            return now;
        }

        text = text.toLowerCase().trim();
        
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
                    return now;
                }
                
                return LocalDateTime.of(year, month, day, 0, 0);
            } catch (Exception e) {
                log.debug("Absolute date parsing failed: {}", text, e);
                return now;
            }
        }
        
        return now;
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
}