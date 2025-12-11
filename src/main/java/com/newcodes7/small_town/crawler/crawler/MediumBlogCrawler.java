package com.newcodes7.small_town.crawler.crawler;

import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerTimeoutException;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.util.TimeUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class MediumBlogCrawler implements BlogCrawler {

    private final Random random = new Random();
    private final S3ImageService s3ImageService;
    private final ParsingSelectorRepository parsingSelectorRepository;
    private ParsingSelector parsingSelector;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public boolean canHandle(String blogUrl) {
        return blogUrl != null 
                && (blogUrl.contains("medium.com") 
                    || blogUrl.contains("netflixtechblog.com") 
                    || blogUrl.contains("yogiyo.co.kr")
                    || blogUrl.contains("gccompany.co.kr")
                    || blogUrl.contains("techblog.lotteon.com")
                );
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException {
        List<Article> articles = new ArrayList<>();
        
        try {
            // Medium bot 감지 우회를 위한 추가 설정
            setupAntiDetection(driver);

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
    
    private List<Article> crawlHtmlWithInfiniteScroll(WebDriver driver, Corporation corporation, String link) throws CrawlerException, IOException {
        List<Article> articles = new ArrayList<>();

        try {
            driver.get(link);

            // 인간처럼 페이지 행동 시뮬레이션
            simulateHumanBehavior(driver);

            // JavaScript 실행 완료 대기
            waitForJavaScriptToLoad(driver);

            // ✅ window.__APOLLO_STATE__에서 데이터 추출 (Medium의 내부 데이터 구조)
            JavascriptExecutor js = (JavascriptExecutor) driver;
            String apolloStateJson = (String) js.executeScript(
                "return JSON.stringify(window.__APOLLO_STATE__ || {});"
            );

            if (apolloStateJson == null || apolloStateJson.equals("{}")) {
                log.warn("APOLLO_STATE를 찾을 수 없습니다: {}", link);
                return articles;
            }

            log.info("APOLLO_STATE JSON 추출 완료: {} bytes", apolloStateJson.length());

            // JSON 파싱 및 Article 추출
            articles = parseArticlesFromApolloState(apolloStateJson, corporation);

            log.info("{} - APOLLO_STATE에서 추출한 아티클 수: {}", link, articles.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(link, 10);
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
                Element imgElement = element.select("img").get(1);
                String originalUrl = imgElement.attr("src");
                thumbnailImage = originalUrl.replaceAll("/resize:fill:\\d+:\\d+/", "/");
            } catch (Exception e) {
                log.debug("썸네일 이미지 찾기 실패: {}", e.getMessage());
            }
            
            // 발행일 찾기
            LocalDateTime publishedAt;
            String timeText = null;

            // URL에 @가 포함되어 있으면 span 요소에서 "Aug 22", "Jul 11", "Jun 13" 같은 날짜 추출
            if (corporation.getBlogLink().contains("@")) {
                Elements spanElements = element.select("span");
                Pattern datePattern = Pattern.compile("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}$", Pattern.CASE_INSENSITIVE);

                for (Element span : spanElements) {
                    String spanText = span.text().trim();
                    Matcher dateMatcher = datePattern.matcher(spanText);
                    if (dateMatcher.matches()) {
                        timeText = spanText;
                        log.debug("날짜 span 요소 발견: {}", timeText);
                        break;
                    }
                }
            } else {
                // @ 없는 일반 Medium 블로그는 "Added" 텍스트 패턴 사용
                Element addedElement = element.selectFirst(":contains(Added)");
                if (addedElement != null) {
                    String fullText = addedElement.text(); // "Added 3d ago"
                    log.debug("Added 요소 발견: {}", fullText);

                    // "Added " 부분을 제거하고 시간 부분만 추출
                    Pattern addedPattern = Pattern.compile("Added\\s+(.+)");
                    Matcher addedMatcher = addedPattern.matcher(fullText);
                    if (addedMatcher.find()) {
                        timeText = addedMatcher.group(1); // "3d ago"
                        log.debug("추출된 시간 텍스트: {}", timeText);
                    }
                }
            }

            if (timeText != null) {
                publishedAt = parseDateText(timeText.trim());
                log.debug("파싱된 발행일: {}", publishedAt);
            } else {
                log.debug("시간 요소를 찾을 수 없어 현재 시간으로 설정합니다.");
                publishedAt = TimeUtil.nowInSeoul();
            }
            
            return Article.builder()
                    .corporation(corporation)
                    .title(title)
                    .summary(summary)
                    .link(link)
                    .thumbnailImage(thumbnailImage)
                    .publishedAt(publishedAt)
                    .viewCount(0)
                    .likeCount(0)
                    .build();
                    
        } catch (Exception e) {
            log.warn("Medium 아티클 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * JavaScript 실행 완료 대기
     * document.readyState가 'complete'이고, 모든 Ajax 요청이 완료될 때까지 대기
     */
    private void waitForJavaScriptToLoad(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(30));

        // 1. document.readyState가 'complete'가 될 때까지 대기
        wait.until(webDriver -> js.executeScript("return document.readyState").equals("complete"));
        log.debug("document.readyState = complete");

        // 2. jQuery가 있으면 jQuery.active가 0이 될 때까지 대기 (Ajax 요청 완료)
        try {
            Boolean jQueryActive = (Boolean) js.executeScript(
                "return typeof jQuery !== 'undefined' && jQuery.active === 0"
            );
            if (jQueryActive != null && !jQueryActive) {
                wait.until(webDriver ->
                    (Boolean) js.executeScript("return jQuery.active === 0")
                );
                log.debug("jQuery.active = 0");
            }
        } catch (Exception e) {
            // jQuery가 없으면 무시
            log.debug("jQuery 없음 또는 확인 실패");
        }

        // 3. 추가 렌더링 대기 (React 렌더링 완료를 위해)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("JavaScript 실행 완료 대기 종료");
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
        Thread.sleep(10000 + random.nextInt(2000));
        
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
        LocalDateTime now = TimeUtil.nowInSeoul();
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
        Pattern absolutePattern = Pattern.compile("(jan|feb|mar|apr|may|좋으|jul|aug|sep|oct|nov|dec)\\s+(\\d{1,2}),?\\s+(\\d{4})");
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
                
                return TimeUtil.dateWithSeoulTime(year, month, day);
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

    /**
     * 특정 article의 본문 추출
     * article link에 직접 접속하여 body 태그의 텍스트를 추출
     *
     * @param articleUrl article의 URL
     * @param driver WebDriver 인스턴스
     * @return 본문 텍스트 (실패 시 빈 문자열)
     */
    public String extractArticleContent(String articleUrl, WebDriver driver) {
        if (articleUrl == null || articleUrl.trim().isEmpty()) {
            log.warn("본문 추출 실패: URL이 비어있음");
            return "";
        }

        try {
            log.debug("본문 추출 시작: {}", articleUrl);

            // article 페이지로 이동
            driver.get(articleUrl);

            // ✅ WebDriverWait를 사용하여 body 요소가 렌더링될 때까지 명시적으로 대기
            WebDriverWait wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(20));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));

            // JavaScript 실행 완료 대기
            waitForJavaScriptToLoad(driver);

            // ✅ JavaScript로 현재 렌더링된 DOM 가져오기
            JavascriptExecutor js = (JavascriptExecutor) driver;
            String currentHtml = (String) js.executeScript("return document.documentElement.outerHTML;");
            Document doc = Jsoup.parse(currentHtml);

            // body 태그에서 텍스트 추출
            Element body = doc.selectFirst("body");
            if (body == null) {
                log.warn("본문 추출 실패: body 태그를 찾을 수 없음 - {}", articleUrl);
                return "";
            }

            String content = body.text();
            log.debug("본문 추출 완료: {} (길이: {}자)", articleUrl, content.length());

            return content;

        } catch (Exception e) {
            log.error("본문 추출 실패: {} - {}", articleUrl, e.getMessage(), e);
            return "";
        }
    }

    /**
     * 모든 페이지 크롤링 (Admin 전용)
     */
    @Override
    public List<Article> crawlAllPages(WebDriver driver, Corporation corporation) throws CrawlerException {
        parsingSelector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());

        // Medium은 기본적으로 무한스크롤 방식 사용
        // 페이지네이션 타입이 null, NONE, INFINITE_SCROLL이면 무한스크롤 크롤링
        if (parsingSelector.getPaginationType() == null
            || "NONE".equals(parsingSelector.getPaginationType())
            || "INFINITE_SCROLL".equals(parsingSelector.getPaginationType())) {
            log.info("페이지네이션 타입이 {} - 무한스크롤 크롤링: {}",
                    parsingSelector.getPaginationType(), corporation.getName());
            return crawlWithInfiniteScroll(driver, corporation);
        }

        List<Article> allArticles = new ArrayList<>();
        int currentPage = 1;
        Integer maxPages = parsingSelector.getMaxPages();
        String paginationType = parsingSelector.getPaginationType();

        log.info("전체 페이지 크롤링 시작 - 기업: {}, 타입: {}, 최대 페이지: {}",
                corporation.getName(), paginationType, maxPages != null ? maxPages : "무제한");

        try {
            // Medium bot 감지 우회를 위한 추가 설정
            setupAntiDetection(driver);

            while (true) {
                // 페이지 URL 생성 및 이동
                String pageUrl = buildPageUrl(corporation.getBlogLink(), currentPage);
                log.info("페이지 {} 크롤링 시작: {}", currentPage, pageUrl);

                List<Article> pageArticles = crawlHtmlWithInfiniteScroll(driver, corporation, pageUrl);
                log.debug("발견된 article 수: {}", pageArticles.size());

                if (pageArticles.isEmpty()) {
                    log.info("페이지 {}에서 글을 찾지 못함 - 크롤링 종료", currentPage);
                    break;
                }

                allArticles.addAll(pageArticles);
                log.info("페이지 {} 크롤링 완료: {}개 글 수집 (총 {}개)", currentPage, pageArticles.size(), allArticles.size());

                // 최대 페이지 체크
                if (maxPages != null && currentPage >= maxPages) {
                    log.info("최대 페이지 {}에 도달 - 크롤링 종료", maxPages);
                    break;
                }

                // 다음 페이지 존재 여부 확인
                if (!hasNextPage(driver, paginationType)) {
                    log.info("다음 페이지가 없음 - 크롤링 종료");
                    break;
                }

                currentPage++;
                Thread.sleep(2000 + random.nextInt(2000)); // 페이지 간 딜레이 (2-4초)
            }

            log.info("전체 페이지 크롤링 완료 - 기업: {}, 총 페이지: {}, 총 글: {}개",
                    corporation.getName(), currentPage, allArticles.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrawlerException("CRAWLER_INTERRUPTED", "Crawling interrupted", e) {};
        } catch (CrawlerException e) {
            throw e;
        } catch (Exception e) {
            log.error("전체 페이지 크롤링 중 오류 - 기업: {}", corporation.getName(), e);
            throw new CrawlerException("CRAWLER_ERROR", "Error during multi-page crawling", e) {};
        }

        return allArticles;
    }

    /**
     * 페이지 URL 생성
     */
    private String buildPageUrl(String baseUrl, int pageNumber) {
        // baseUrl에서 /page/숫자 패턴 제거 (정규화)
        String normalizedBaseUrl = baseUrl.replaceAll("\\/page\\/\\d+", "");

        String paginationType = parsingSelector.getPaginationType();
        String pattern = parsingSelector.getPageUrlPattern();

        if ("URL_PARAMETER".equals(paginationType) && pattern != null) {
            String pageParam = pattern.replace("{page}", String.valueOf(pageNumber));
            // URL에 이미 쿼리 파라미터가 있는지 확인
            if (pattern.startsWith("?")) {
                // 쿼리 파라미터 형식
                if (normalizedBaseUrl.contains("?")) {
                    return normalizedBaseUrl + "&" + pageParam.substring(1);
                } else {
                    return normalizedBaseUrl + pageParam;
                }
            } else if (pattern.startsWith("/")) {
                // 경로 형식
                return normalizedBaseUrl + pattern.replace("{page}", String.valueOf(pageNumber));
            } else {
                // 기본적으로 쿼리 파라미터로 추가
                return normalizedBaseUrl + (normalizedBaseUrl.contains("?") ? "&" : "?") + pageParam;
            }
        }

        // 기본값: 쿼리 파라미터로 추가
        return normalizedBaseUrl + (normalizedBaseUrl.contains("?") ? "&" : "?") + "page=" + pageNumber;
    }

    /**
     * 다음 페이지 존재 여부 확인
     */
    private boolean hasNextPage(WebDriver driver, String paginationType) {
        if ("NEXT_BUTTON".equals(paginationType)) {
            try {
                String nextPageSelector = parsingSelector.getNextPageSelector();
                if (nextPageSelector != null && !nextPageSelector.trim().isEmpty()) {
                    // ✅ JavaScript로 현재 렌더링된 DOM 가져오기
                    JavascriptExecutor js = (JavascriptExecutor) driver;
                    String currentHtml = (String) js.executeScript("return document.documentElement.outerHTML;");
                    Document doc = Jsoup.parse(currentHtml);
                    Elements nextButtons = doc.select(nextPageSelector);
                    return !nextButtons.isEmpty();
                }
            } catch (Exception e) {
                log.warn("다음 페이지 버튼 확인 실패: {}", e.getMessage());
            }
            return false;
        }

        // URL_PARAMETER는 항상 true (빈 페이지로 감지)
        return true;
    }

    /**
     * 무한스크롤 크롤링 (페이지 끝까지)
     * 하이브리드 방식: 첫 로드는 Apollo State, 스크롤 후에는 DOM 파싱
     */
    private List<Article> crawlWithInfiniteScroll(WebDriver driver, Corporation corporation) throws CrawlerException {
        java.util.Set<String> collectedLinks = new java.util.HashSet<>();
        List<Article> allArticles = new ArrayList<>();

        try {
            // Medium bot 감지 우회
            setupAntiDetection(driver);

            driver.get(corporation.getBlogLink());

            // 인간처럼 행동 (페이지 로딩 대기 포함)
            simulateHumanBehavior(driver);

            // JavaScript 실행 완료 대기
            waitForJavaScriptToLoad(driver);

            JavascriptExecutor js = (JavascriptExecutor) driver;

            // ✅ 첫 로드: APOLLO_STATE에서 데이터 추출
            String apolloStateJson = (String) js.executeScript(
                "return JSON.stringify(window.__APOLLO_STATE__ || {});"
            );

            if (apolloStateJson != null && !apolloStateJson.equals("{}")) {
                log.info("첫 로드 - APOLLO_STATE JSON 추출 완료: {} bytes", apolloStateJson.length());
                List<Article> initialArticles = parseArticlesFromApolloState(apolloStateJson, corporation);

                // 링크 중복 방지를 위해 Set에 추가
                for (Article article : initialArticles) {
                    if (article.getLink() != null && !collectedLinks.contains(article.getLink())) {
                        collectedLinks.add(article.getLink());
                        allArticles.add(article);
                    }
                }

                log.info("첫 로드 완료 - APOLLO_STATE에서 {}개 수집", initialArticles.size());
            }

            int maxScrollAttempts = 50; // 최대 스크롤 횟수 (안전장치)
            int noNewArticlesCount = 0;  // 새 article이 없는 연속 횟수
            int scrollCount = 0;

            log.info("Medium 무한스크롤 크롤링 시작 - 기업: {}", corporation.getName());

            while (scrollCount < maxScrollAttempts) {
                // 페이지 끝까지 스크롤
                scrollToBottom(driver);

                // 새 콘텐츠 로딩 대기 (스크롤 후 Medium이 새 글을 로드하는 시간)
                Thread.sleep(3000 + random.nextInt(2000)); // 3-5초 대기

                // 스크롤 전 수집된 글 개수
                int beforeCount = collectedLinks.size();

                // ✅ 스크롤 후: DOM에서 새로운 article 요소 파싱
                String currentHtml = (String) js.executeScript("return document.documentElement.outerHTML;");
                Document doc = Jsoup.parse(currentHtml);
                Elements articleElements = doc.select("article");

                log.debug("스크롤 {}: DOM에서 발견된 article 요소 수: {}", scrollCount + 1, articleElements.size());

                for (Element element : articleElements) {
                    try {
                        Article article = parseArticleFromElement(element, corporation, driver);
                        if (article != null && article.getLink() != null && !collectedLinks.contains(article.getLink())) {
                            collectedLinks.add(article.getLink());
                            allArticles.add(article);
                        }
                    } catch (Exception e) {
                        log.debug("Article 파싱 실패: {}", e.getMessage());
                    }
                }

                // 스크롤 후 수집된 글 개수
                int afterCount = collectedLinks.size();

                // 새로운 article이 추가되었는지 확인
                if (afterCount == beforeCount) {
                    noNewArticlesCount++;
                    log.debug("스크롤 {}: 새로운 article 없음 ({}/3)", scrollCount + 1, noNewArticlesCount);
                    if (noNewArticlesCount >= 3) {
                        log.info("연속 3번 새 article 없음 - 크롤링 종료");
                        break;
                    }
                } else {
                    int newCount = afterCount - beforeCount;
                    noNewArticlesCount = 0;
                    log.info("스크롤 {}: 새로운 article {}개 발견 (총 {}개)", scrollCount + 1, newCount, afterCount);
                }

                scrollCount++;
            }

            if (scrollCount >= maxScrollAttempts) {
                log.warn("최대 스크롤 횟수 {}회 도달 - 크롤링 종료", maxScrollAttempts);
            }

            log.info("Medium 무한스크롤 크롤링 완료 - 기업: {}, 총 스크롤: {}회, 총 글: {}개",
                    corporation.getName(), scrollCount, allArticles.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(corporation.getBlogLink(), 10);
        } catch (Exception e) {
            log.error("Medium 무한스크롤 크롤링 중 오류 - 기업: {}", corporation.getName(), e);
            throw new CrawlerException("CRAWLER_ERROR", "Error during Medium infinite scroll crawling", e) {};
        }

        return allArticles;
    }

    /**
     * 페이지 끝까지 스크롤 (점진적 스크롤 + 로딩 대기)
     */
    private void scrollToBottom(WebDriver driver) throws InterruptedException {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // 현재 스크롤 높이 저장
        Long lastHeight = (Long) js.executeScript("return document.body.scrollHeight");

        // 점진적 스크롤 (여러 단계로 나눠서 스크롤)
        // Medium의 lazy loading을 트리거하기 위해 천천히 스크롤
        for (int i = 0; i < 5; i++) {
            // 현재 높이의 일부씩 스크롤 (20%씩)
            js.executeScript("window.scrollBy(0, document.body.scrollHeight / 5)");
            Thread.sleep(300 + random.nextInt(200)); // 300-500ms 대기
        }

        // 완전히 끝까지 스크롤
        js.executeScript("window.scrollTo(0, document.body.scrollHeight)");

        // 새 콘텐츠 로딩 대기 (높이 변화 감지)
        int waitAttempts = 0;
        int maxWaitAttempts = 10; // 최대 5초 대기 (10 * 500ms)

        while (waitAttempts < maxWaitAttempts) {
            Thread.sleep(500);
            Long newHeight = (Long) js.executeScript("return document.body.scrollHeight");

            // 높이가 변했으면 새 콘텐츠가 로드된 것
            if (!newHeight.equals(lastHeight)) {
                log.debug("스크롤 높이 변화 감지: {} -> {}", lastHeight, newHeight);
                lastHeight = newHeight;

                // 추가 콘텐츠가 더 로드될 수 있으므로 조금 더 대기
                Thread.sleep(1000);
                break;
            }

            waitAttempts++;
        }

        if (waitAttempts >= maxWaitAttempts) {
            log.debug("스크롤 높이 변화 없음 - 더 이상 로드할 콘텐츠가 없을 수 있음");
        }
    }

    /**
     * 현재 페이지에서 article 파싱 (중복 제거)
     * JavaScript로 현재 렌더링된 DOM을 가져와서 파싱
     */
    private List<Article> parseArticlesFromCurrentPage(WebDriver driver, Corporation corporation, java.util.Set<String> collectedLinks) {
        List<Article> newArticles = new ArrayList<>();

        // ✅ 핵심: JavaScript로 현재 렌더링된 DOM의 HTML을 가져옴
        // driver.getPageSource()는 초기 HTML만 반환하지만,
        // document.documentElement.outerHTML은 현재 렌더링된 DOM을 반환
        JavascriptExecutor js = (JavascriptExecutor) driver;
        String currentHtml = (String) js.executeScript("return document.documentElement.outerHTML;");

        Document doc = Jsoup.parse(currentHtml);

        // Medium article 셀렉터
        Elements articleElements = doc.select("article[data-testid='post-preview']");

        log.info("발견된 Medium 아티클 요소 수: {}", articleElements.size());

        for (Element element : articleElements) {
            try {
                Article article = parseArticleFromElement(element, corporation, driver);
                if (article != null && article.getLink() != null && !collectedLinks.contains(article.getLink())) {
                    collectedLinks.add(article.getLink());
                    newArticles.add(article);
                    log.debug("새 article 추가: {}", article.getTitle());
                } else if (article != null && article.getLink() != null) {
                    log.debug("중복 article 스킵: {}", article.getLink());
                } else if (article != null) {
                    log.debug("링크가 없는 article 스킵");
                } else {
                    log.debug("파싱 결과가 null인 article");
                }
            } catch (Exception e) {
                log.warn("Medium 아티클 파싱 실패: {}", e.getMessage());
            }
        }

        log.info("현재 페이지에서 추가된 새 article 수: {}", newArticles.size());

        return newArticles;
    }

    /**
     * HTML 파일 저장 (디버깅용)
     */
    private void saveHtmlToFile(String html, String corporationName, String methodName) {
        try {
            // debug_html 디렉토리 생성
            Path debugDir = Path.of("debug_html");
            if (!Files.exists(debugDir)) {
                Files.createDirectories(debugDir);
            }

            // 파일명: medium_{corporationName}_{methodName}_{timestamp}.html
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String sanitizedName = corporationName.replaceAll("[^a-zA-Z0-9가-힣]", "_");
            String fileName = String.format("medium_%s_%s_%s.html", sanitizedName, methodName, timestamp);
            Path filePath = debugDir.resolve(fileName);

            // HTML 저장
            Files.writeString(filePath, html);
            log.info("Medium HTML 저장 완료: {}", filePath);

        } catch (Exception e) {
            log.warn("Medium HTML 저장 실패: {}", e.getMessage());
        }
    }

    /**
     * window.__APOLLO_STATE__에서 Article 목록 파싱
     */
    private List<Article> parseArticlesFromApolloState(String apolloStateJson, Corporation corporation) {
        return parseArticlesFromApolloStateWithDedup(apolloStateJson, corporation, null);
    }

    /**
     * window.__APOLLO_STATE__에서 Article 목록 파싱 (중복 제거 지원)
     * @param apolloStateJson Apollo State JSON
     * @param corporation 기업 정보
     * @param collectedPostIds 이미 수집한 Post ID Set (null이면 중복 체크 안 함)
     * @return 새로 파싱된 Article 목록
     */
    private List<Article> parseArticlesFromApolloStateWithDedup(
            String apolloStateJson,
            Corporation corporation,
            java.util.Set<String> collectedPostIds) {
        List<Article> articles = new ArrayList<>();

        try {
            // JSON 파싱
            JsonNode root = objectMapper.readTree(apolloStateJson);

            // Publication ID 찾기 (ROOT_QUERY에서)
            JsonNode rootQuery = root.get("ROOT_QUERY");
            if (rootQuery == null) {
                log.warn("ROOT_QUERY를 찾을 수 없습니다");
                return articles;
            }

            // Publication 참조 찾기
            String publicationRef = null;
            java.util.Iterator<String> publicationKeys = rootQuery.fieldNames();
            while (publicationKeys.hasNext()) {
                String key = publicationKeys.next();
                if (key.contains("publicationByRef") || key.contains("collectionByDomainOrSlug")) {
                    JsonNode refNode = rootQuery.get(key).get("__ref");
                    if (refNode != null) {
                        publicationRef = refNode.asText();
                        break;
                    }
                }
            }

            if (publicationRef == null) {
                log.warn("Publication 참조를 찾을 수 없습니다");
                return articles;
            }

            log.debug("Publication 참조 찾음: {}", publicationRef);

            // Publication 객체에서 posts 찾기
            JsonNode publication = root.get(publicationRef);
            if (publication == null) {
                log.warn("Publication 객체를 찾을 수 없습니다: {}", publicationRef);
                return articles;
            }

            // publicationPostsConnection 찾기
            JsonNode postsConnection = null;
            java.util.Iterator<String> publicationFieldNames = publication.fieldNames();
            while (publicationFieldNames.hasNext()) {
                String key = publicationFieldNames.next();
                if (key.contains("publicationPostsConnection")) {
                    postsConnection = publication.get(key);
                    break;
                }
            }

            if (postsConnection == null) {
                log.warn("publicationPostsConnection을 찾을 수 없습니다");
                return articles;
            }

            // edges 배열에서 Post 추출
            JsonNode edges = postsConnection.get("edges");
            if (edges == null || !edges.isArray()) {
                log.warn("edges 배열을 찾을 수 없습니다");
                return articles;
            }

            log.debug("Post edges 찾음: {}개", edges.size());

            // 각 edge에서 Post 참조 추출
            for (JsonNode edge : edges) {
                try {
                    JsonNode nodeRef = edge.get("node").get("__ref");
                    if (nodeRef == null) continue;

                    String postRef = nodeRef.asText();

                    // 중복 체크 (collectedPostIds가 null이 아닌 경우)
                    if (collectedPostIds != null) {
                        // Post ID 추출 (예: "Post:abc123" -> "abc123")
                        String postId = postRef.replace("Post:", "");
                        if (collectedPostIds.contains(postId)) {
                            log.debug("중복 Post 스킵: {}", postId);
                            continue;
                        }
                        collectedPostIds.add(postId);
                    }

                    JsonNode postNode = root.get(postRef);
                    if (postNode == null) continue;

                    Article article = parseArticleFromApolloPost(postNode, root, corporation);
                    if (article != null) {
                        articles.add(article);
                        log.debug("Article 파싱 완료: {}", article.getTitle());
                    }
                } catch (Exception e) {
                    log.warn("Post 파싱 실패: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("APOLLO_STATE 파싱 실패: {}", e.getMessage(), e);
        }

        return articles;
    }

    /**
     * Apollo State의 Post 노드에서 Article 생성
     */
    private Article parseArticleFromApolloPost(JsonNode postNode, JsonNode root, Corporation corporation) {
        try {
            // 제목
            String title = postNode.get("title").asText();
            if (title == null || title.isEmpty()) return null;

            // URL
            String link = "";
            JsonNode mediumUrlNode = postNode.get("mediumUrl");
            if (mediumUrlNode != null) {
                link = mediumUrlNode.asText();
            } else {
                // uniqueSlug로 URL 생성
                JsonNode uniqueSlugNode = postNode.get("uniqueSlug");
                if (uniqueSlugNode != null) {
                    link = "https://medium.com/" + uniqueSlugNode.asText();
                }
            }

            if (link.isEmpty()) return null;

            // 썸네일 이미지
            String thumbnailImage = "";
            JsonNode previewImageRef = postNode.get("previewImage");
            if (previewImageRef != null) {
                JsonNode imageRefNode = previewImageRef.get("__ref");
                if (imageRefNode != null) {
                    String imageRef = imageRefNode.asText();
                    JsonNode imageNode = root.get(imageRef);
                    if (imageNode != null) {
                        String imageId = imageNode.get("id").asText();
                        // Medium 이미지 URL 생성
                        thumbnailImage = "https://miro.medium.com/v2/resize:fit:1200/" + imageId;
                    }
                }
            }

            // 발행일
            LocalDateTime publishedAt;
            JsonNode firstPublishedAtNode = postNode.get("firstPublishedAt");
            if (firstPublishedAtNode != null) {
                long timestamp = firstPublishedAtNode.asLong();
                // milliseconds timestamp를 LocalDateTime으로 변환
                publishedAt = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestamp),
                    ZoneId.of("Asia/Seoul")
                );
            } else {
                publishedAt = TimeUtil.nowInSeoul();
            }

            // 요약 (extendedPreviewContent의 subtitle)
            String summary = "";
            JsonNode extendedPreviewNode = postNode.get("extendedPreviewContent");
            if (extendedPreviewNode != null) {
                JsonNode subtitleNode = extendedPreviewNode.get("subtitle");
                if (subtitleNode != null) {
                    summary = subtitleNode.asText();
                }
            }

            return Article.builder()
                    .corporation(corporation)
                    .title(title)
                    .summary(summary)
                    .link(link)
                    .thumbnailImage(thumbnailImage)
                    .publishedAt(publishedAt)
                    .viewCount(0)
                    .likeCount(0)
                    .build();

        } catch (Exception e) {
            log.warn("Apollo Post 파싱 오류: {}", e.getMessage());
            return null;
        }
    }
}