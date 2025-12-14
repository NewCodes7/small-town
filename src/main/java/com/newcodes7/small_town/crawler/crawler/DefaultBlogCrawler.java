package com.newcodes7.small_town.crawler.crawler;

import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerTimeoutException;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.util.TimeUtil;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultBlogCrawler implements BlogCrawler {
    
    private final S3ImageService s3ImageService;
    private final ParsingSelectorRepository parsingSelectorRepository;
    private ParsingSelector parsingSelector;
    private final Random random = new Random();

    @Override
    public boolean canHandle(String blogUrl) {
        // 다른 크롤러가 처리하지 못하는 모든 블로그를 처리
        return true;
    }
    
    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException {
        List<Article> articles = new ArrayList<>();
        parsingSelector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());

        try {
            articles = crawlHtmlContent(driver, corporation);
            log.info("기본 크롤러 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
        } catch (CrawlerException e) {
            log.error("기본 크롤러 실패 - 기업: {}, 블로그: {}, 오류 타입: {}, 오류: {}",
                    corporation.getName(), corporation.getBlogLink(), e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("기본 크롤러 예상치 못한 오류 - 기업: {}, 블로그: {}, 오류 타입: {}, 오류 메시지: {}, 상세 정보: {}",
                    corporation.getName(),
                    corporation.getBlogLink(),
                    e.getClass().getName(),
                    e.getMessage(),
                    e.getCause() != null ? "원인: " + e.getCause().getMessage() : "원인 없음",
                    e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error in DefaultBlogCrawler for corporation: " + corporation.getName(), e) {};
        }

        return articles;
    }

    /**
     * HTML 콘텐츠 크롤링 (기존 로직)
     */
    private List<Article> crawlHtmlContent(WebDriver driver, Corporation corporation) throws CrawlerException, IOException {
        List<Article> articles = new ArrayList<>();

        try {
            driver.get(corporation.getBlogLink());
            Thread.sleep(5000);

            // 스크롤 시뮬레이션
            performScrollSimulation(driver);

            // 페이지에서 article 파싱
            String pageSource = driver.getPageSource();
            articles = parseArticlesFromPage(pageSource, corporation);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(corporation.getBlogLink(), 2);
        }

        return articles;
    }

    /**
     * 스크롤 시뮬레이션 수행
     * 무한 스크롤 페이지에서 모든 콘텐츠를 로드하기 위해 사용
     */
    private void performScrollSimulation(WebDriver driver) throws InterruptedException {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Random random = new Random();
        int maxScrollAttempts = 50; // 최대 스크롤 횟수 제한
        int scrollCount = 0;
        long previousHeight = 0;
        int noChangeCount = 0;

        while (scrollCount < maxScrollAttempts) {
            long currentPos = (long) js.executeScript("return window.scrollY + window.innerHeight;");
            long totalHeight = (long) js.executeScript("return document.body.scrollHeight;");

            if (currentPos >= totalHeight) {
                break; // 페이지 끝 도달
            }

            // 높이 변화 감지 (무한 스크롤 대응)
            if (totalHeight == previousHeight) {
                noChangeCount++;
                if (noChangeCount >= 3) {
                    break; // 3번 연속 높이 변화 없으면 종료
                }
            } else {
                noChangeCount = 0;
                previousHeight = totalHeight;
            }

            // 랜덤 스크롤
            int scrollAmount = 200 + random.nextInt(300);
            js.executeScript("window.scrollBy(0, " + scrollAmount + ")");

            Thread.sleep(500 + random.nextInt(1000));
            scrollCount++;
        }
    }

    /**
     * 페이지 소스에서 Article 목록 파싱
     */
    private List<Article> parseArticlesFromPage(String pageSource, Corporation corporation) {
        List<Article> articles = new ArrayList<>();
        Document doc = Jsoup.parse(pageSource);

        // 다양한 CSS 선택자로 아티클 찾기
        Elements articleElements = doc.select(parsingSelector.getArticle());
        for (Element element : articleElements) {
            try {
                Article article = parseArticle(element, corporation);
                if (article != null) {
                    articles.add(article);
                }
            } catch (Exception e) {
                log.warn("기본 크롤러 개별 아티클 파싱 실패 - 오류 타입: {}, 오류: {}, HTML: {}",
                        e.getClass().getSimpleName(), e.getMessage(), element.outerHtml().substring(0, Math.min(200, element.outerHtml().length())), e);
            }
        }

        return articles;
    }


    /**
     * HTML 요소에서 Article 파싱
     */
    private Article parseArticle(Element element, Corporation corporation) {
        try {
            String title = parseTitle(element);
            if (title == null) return null;

            String link = parseLink(element);
            if (link == null) return null;

            String summary = parseSummary(element);
            String thumbnailImage = parseThumbnailImage(element, corporation);
            LocalDateTime publishedAt = parsePublishedDate(element);

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
            log.warn("기본 크롤러 아티클 파싱 오류 - 오류 타입: {}, 오류: {}, 요소 HTML: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    element.outerHtml().substring(0, Math.min(300, element.outerHtml().length())),
                    e);
            return null;
        }
    }

    /**
     * 제목 파싱
     */
    private String parseTitle(Element element) {
        Element titleElement = element.selectFirst(parsingSelector.getTitle());
        if (titleElement == null) return null;
        String title = titleElement.text().trim();
        return title.isEmpty() ? null : title;
    }

    /**
     * 링크 파싱 및 절대 경로 변환
     */
    private String parseLink(Element element) {
        Element linkElement = element.selectFirst(parsingSelector.getLink());
        if (linkElement == null) return null;

        String link = linkElement.attr("href");
        if (link.isEmpty()) return null;

        // 상대 경로를 절대 경로로 변환
        if (!link.startsWith("http")) {
            if (link.startsWith("/")) {
                String baseUrl = parsingSelector.getBaseUrl();
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                link = baseUrl + link;
            } else {
                return null;
            }
        }

        return link;
    }

    /**
     * 요약 파싱
     */
    private String parseSummary(Element element) {
        Element summaryElement = element.selectFirst(
            ".summary, .excerpt, .description, .content, p, " +
            "[class*='summary'], [class*='excerpt'], [class*='desc']"
        );

        if (summaryElement == null) return "";

        String summary = summaryElement.text().trim();
        if (summary.length() > 200) {
            summary = summary.substring(0, 200) + "...";
        }

        return summary;
    }

    /**
     * 썸네일 이미지 URL 파싱
     */
    private String parseThumbnailImage(Element element, Corporation corporation) {
        Element imgElement = element.selectFirst(parsingSelector.getThumbnail());
        if (imgElement == null) return "";

        String imgSrc = extractImageSrc(imgElement);
        if (imgSrc == null || imgSrc.isEmpty()) return "";

        // 상대 경로를 절대 경로로 변환
        if (imgSrc.startsWith("//")) {
            imgSrc = "https:" + imgSrc; // kakaomobility 때문에 추가 (프로토콜 상대 URL)
        } else if (!imgSrc.startsWith("http")) {
            imgSrc = resolveImageUrl(imgSrc, corporation.getBlogLink());
        }

        return imgSrc;
    }

    /**
     * 이미지 소스 추출 (사이트별 특수 처리)
     */
    private String extractImageSrc(Element imgElement) {
        String baseUrl = parsingSelector.getBaseUrl();

        // 토스 블로그: Next.js 특수 처리
        if (baseUrl != null && baseUrl.contains("toss")) {
            Element parent = imgElement.parent();
            if (parent != null) {
                Elements imgElements = parent.select("img[alt*='thumbnail']");
                if (imgElements.size() > 1) {
                    imgElement = imgElements.get(1);
                }
            }
        }

        // NHN Cloud, KT Cloud: CSS background-image
        if (baseUrl != null && (baseUrl.contains("nhncloud") || baseUrl.contains("ktcloud"))) {
            return extractCssImgUrl(imgElement.attr("style"));
        }

        // 강남언니: srcset 속성
        if (baseUrl != null && baseUrl.contains("gangnamunni")) {
            return imgElement.attr("srcset");
        }

        // 일반적인 경우: src 속성
        return imgElement.attr("src");
    }

    /**
     * 발행일 파싱
     */
    private LocalDateTime parsePublishedDate(Element element) {
        Element publishElement = element.selectFirst(parsingSelector.getPublish());
        if (publishElement == null) {
            throw new IllegalStateException("발행일 요소를 찾을 수 없습니다. 셀렉터: " + parsingSelector.getPublish());
        }

        String dateText = publishElement.text().trim();
        if (dateText.isEmpty()) {
            throw new IllegalArgumentException("발행일 텍스트가 비어있습니다. 요소: " + publishElement.outerHtml());
        }

        String publishFormat = parsingSelector.getPublishFormat();
        if (publishFormat == null) {
            throw new IllegalStateException("publishFormat이 설정되지 않았습니다.");
        }

        LocalDateTime publishedAt;

        if (publishFormat.equals("yyyy.MM.dd")
            || publishFormat.equals("yyyy.M.dd")
            || publishFormat.equals("yyyy-MM-dd")) {
            publishedAt = parseKoreanDateFormat(dateText);
        } else if (publishFormat.equals("yyyy년 MM월 dd일")
            || publishFormat.equals("yyyy년 M월 d일")) {
            publishedAt = parseKoreanYearMonthDayFormat(dateText);
        } else if (publishFormat.equals("ISO8601")) {
            publishedAt = parseISO8601Format(publishElement);
        } else if (publishFormat.equals("MMM d, yyyy")
            || publishFormat.equals("MMMM d, yyyy")
            || publishFormat.equals("MMMM dd, yyyy")
            || publishFormat.equals("MMM dd, yyyy")) {
            publishedAt = parseEnglishDateFormat(dateText);
        } else if (publishFormat.trim().equals("dd MMM yyyy")) {
            publishedAt = parseDayMonthYearFormat(dateText);
        } else if (publishFormat.trim().equals("dd MMM")) {
            publishedAt = parseShortEnglishDateFormat(dateText);
        } else if (publishFormat.trim().equals("MMM dd") || publishFormat.trim().equals("MMM d")) {
            publishedAt = parseMonthDayFormat(dateText);
        } else {
            publishedAt = parseDateText(dateText, publishFormat);
        }

        // 미래 날짜일 시 크롤링된 시각으로 설정 (SK플래닛 블로그 대응)
        if (publishedAt.isAfter(LocalDateTime.now())) {
            publishedAt = LocalDateTime.now();
        }

        return publishedAt;
    }

    /**
     * 한국 날짜 형식 파싱 (yyyy.MM.dd)
     */
    private LocalDateTime parseKoreanDateFormat(String dateText) {
        String cleanDateText = extractDateOnly(dateText);
        return TimeUtil.dateWithSeoulTime(parseDate(cleanDateText));
    }

    /**
     * 한국어 날짜 형식 파싱 (yyyy년 MM월 dd일)
     */
    private LocalDateTime parseKoreanYearMonthDayFormat(String dateText) {
        try {
            // 날짜 부분만 추출 (yyyy년 MM월 dd일 패턴)
            Pattern pattern = Pattern.compile("(\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일)");
            Matcher matcher = pattern.matcher(dateText);
            String extractedDate = matcher.find() ? matcher.group(1) : dateText.trim();

            // "yyyy년 MM월 dd일" 또는 "yyyy년 M월 d일" 형식 파싱
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .appendPattern("[yyyy년 M월 d일][yyyy년 MM월 dd일]")
                    .toFormatter(Locale.KOREAN);
            LocalDate date = LocalDate.parse(extractedDate, formatter);
            return TimeUtil.dateWithSeoulTime(date);
        } catch (DateTimeParseException e) {
            log.warn("한국어 날짜 형식 파싱 실패: {} - {}", dateText, e.getMessage());
            // 파싱 실패 시 현재 날짜 반환
            return LocalDateTime.now();
        }
    }

    /**
     * ISO8601 형식 파싱
     */
    private LocalDateTime parseISO8601Format(Element publishElement) {
        String datetime = publishElement.attr("datetime");
        ZonedDateTime zonedDateTime = ZonedDateTime.parse(datetime);
        return zonedDateTime.toLocalDateTime();
    }

    /**
     * 영어 날짜 형식 파싱 (MMM d, yyyy)
     */
    private LocalDateTime parseEnglishDateFormat(String dateText) {
        dateText = dateText.split("/")[0].trim();
        dateText = dateText.replaceAll("[^a-zA-Z0-9\\s,]", "");

        // 비표준 월 축약형을 표준 형태로 정규화
        dateText = normalizeEnglishMonths(dateText);

        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                        .parseCaseInsensitive()
                        .appendPattern("[MMMM d, yyyy][MMM d, yyyy]")
                        .toFormatter(Locale.ENGLISH);
        LocalDate date = LocalDate.parse(dateText, formatter);
        return TimeUtil.dateWithSeoulTime(date);
    }

    /**
     * dd MMM yyyy 형식 파싱
     */
    private LocalDateTime parseDayMonthYearFormat(String dateText) {
        // "dd MMM yyyy" 패턴 추출 (예: "30 Jun 2025")
        Pattern pattern = Pattern.compile("(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})");
        Matcher matcher = pattern.matcher(dateText);

        if (!matcher.find()) {
            throw new IllegalArgumentException("dd MMM yyyy 형식을 찾을 수 없습니다: " + dateText);
        }

        String extractedDate = matcher.group(1).trim();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
        LocalDate date = LocalDate.parse(extractedDate, formatter);
        return TimeUtil.dateWithSeoulTime(date);
    }

    /**
     * 짧은 영어 날짜 형식 파싱 (dd MMM)
     */
    private LocalDateTime parseShortEnglishDateFormat(String dateText) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("[dd MMM][d MMM]")
                .toFormatter(Locale.ENGLISH);
        TemporalAccessor temporalAccessor = formatter.parse(dateText);
        LocalDate date = LocalDate.of(LocalDate.now().getYear(),
                                    temporalAccessor.get(ChronoField.MONTH_OF_YEAR),
                                    temporalAccessor.get(ChronoField.DAY_OF_MONTH));
        return TimeUtil.dateWithSeoulTime(date);
    }

    /**
     * 월-일 영어 날짜 형식 파싱 (MMM dd)
     */
    private LocalDateTime parseMonthDayFormat(String dateText) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("[MMM dd][MMM d]")
                .toFormatter(Locale.ENGLISH);
        TemporalAccessor temporalAccessor = formatter.parse(dateText);
        LocalDate date = LocalDate.of(LocalDate.now().getYear(),
                                    temporalAccessor.get(ChronoField.MONTH_OF_YEAR),
                                    temporalAccessor.get(ChronoField.DAY_OF_MONTH));
        return TimeUtil.dateWithSeoulTime(date);
    }

    private LocalDateTime parseDateText(String dateText, String publishFormat) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("[" + publishFormat + "]")
                .toFormatter(Locale.ENGLISH);
        TemporalAccessor temporalAccessor = formatter.parse(dateText);
        LocalDate date = LocalDate.of(LocalDate.now().getYear(),
                                    temporalAccessor.get(ChronoField.MONTH_OF_YEAR),
                                    temporalAccessor.get(ChronoField.DAY_OF_MONTH));
        return TimeUtil.dateWithSeoulTime(date);
    }

    /**
     * 영어 월 이름 정규화
     */
    private String normalizeEnglishMonths(String dateText) {
        return dateText.replaceAll("(?i)\\bJANUARY\\b", "JAN")
                      .replaceAll("(?i)\\bFEBRUARY\\b", "FEB")
                      .replaceAll("(?i)\\bMARCH\\b", "MAR")
                      .replaceAll("(?i)\\bAPRIL\\b", "APR")
                      .replaceAll("(?i)\\bJUNE\\b", "JUN")
                      .replaceAll("(?i)\\bJULY\\b", "JUL")
                      .replaceAll("(?i)\\bAUGUST\\b", "AUG")
                      .replaceAll("(?i)\\bSEPTEMBER\\b", "SEP")
                      .replaceAll("(?i)\\bSEPT\\b", "SEP")
                      .replaceAll("(?i)\\bOCTOBER\\b", "OCT")
                      .replaceAll("(?i)\\bNOVEMBER\\b", "NOV")
                      .replaceAll("(?i)\\bDECEMBER\\b", "DEC");
    }

    private static String extractCssImgUrl(String cssBackgroundImage) {
        // url('...') 또는 url("...") 또는 url(...) 패턴 매칭
        Pattern pattern = Pattern.compile("url\\(['\"]?([^'\"\\)]+)['\"]?\\)");
        Matcher matcher = pattern.matcher(cssBackgroundImage);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractDateOnly(String dateText) {
        return dateText.replaceAll("[^.0-9]", ".")
                        .replaceAll("\\.+", ".") // 연속된 점을 하나로
                        .replaceAll("^\\.|\\.$", ""); // 앞뒤 점 제거
    }  
    
    public LocalDate parseDate(String dateStr) {
        DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy.M.d");
        DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        
        try {
            return LocalDate.parse(dateStr, formatter1);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(dateStr, formatter2);
        }
    }

    private String resolveImageUrl(String imgSrc, String baseUrl) {
        try {
            if (imgSrc.startsWith("http")) {
                return imgSrc; // 이미 절대 URL
            }
            
            // 기본 URL을 java.net.URL로 파싱
            java.net.URL base = new java.net.URL(baseUrl);
            
            if (imgSrc.startsWith("/")) {
                // 절대 경로: 프로토콜 + 호스트 + 경로
                return base.getProtocol() + "://" + base.getHost() + 
                       (base.getPort() != -1 ? ":" + base.getPort() : "") + imgSrc;
            } else {
                // 상대 경로: 현재 디렉토리 기준
                java.net.URL resolved = new java.net.URL(base, imgSrc);
                return resolved.toString();
            }
        } catch (Exception e) {
            log.warn("이미지 URL 해석 실패: {} (base: {})", imgSrc, baseUrl);
            return imgSrc; // 실패 시 원본 반환
        }
    }

    /**
     * RSS/Atom 피드 크롤링 시도 (WebDriver 사용)
     */
    private List<Article> tryRssFeedCrawling(WebDriver driver, Corporation corporation) {
        List<Article> articles = new ArrayList<>();
        
        // 일반적인 RSS 피드 URL 패턴들
        List<String> feedUrls = generateFeedUrls(corporation.getBlogLink());
        
        for (String feedUrl : feedUrls) {
            try {
                List<Article> feedArticles = parseFeedWithWebDriver(driver, feedUrl, corporation);
                if (!feedArticles.isEmpty()) {
                    log.info("RSS 피드 발견 및 파싱 성공: {}", feedUrl);
                    articles.addAll(feedArticles);
                    break; // 첫 번째로 성공한 피드만 사용
                }
            } catch (Exception e) {
                log.debug("RSS 피드 파싱 실패: {} - {}", feedUrl, e.getMessage());
            }
        }
        
        return articles;
    }
    
    /**
     * 가능한 RSS 피드 URL 생성
     */
    private List<String> generateFeedUrls(String blogUrl) {
        String baseUrl = blogUrl.endsWith("/") ? blogUrl.substring(0, blogUrl.length() - 1) : blogUrl;
        
        List<String> feedUrls = new ArrayList<>();
        
        // Medium 특화 RSS 피드 패턴 (우선순위 높음)
        if (blogUrl.contains("medium.com")) {
            feedUrls.add(baseUrl + "/feed");
            feedUrls.add(baseUrl + "/rss");
        }
        
        // 일반적인 RSS 피드 URL 패턴들
        feedUrls.addAll(Arrays.asList(
            baseUrl + "/feed",
            baseUrl + "/rss", 
            baseUrl + "/feed.xml",
            baseUrl + "/rss.xml",
            baseUrl + "/atom.xml",
            baseUrl + "/feeds/posts/default", // Blogger
            baseUrl + "/feed/",
            baseUrl + "/rss/",
            baseUrl + "/index.xml", // Hugo
            baseUrl + "/feed.rss"
        ));
        
        log.debug("생성된 RSS 피드 URL 후보들: {}", feedUrls);
        return feedUrls;
    }
    
    /**
     * WebDriver를 사용한 RSS/Atom 피드 파싱
     */
    private List<Article> parseFeedWithWebDriver(WebDriver driver, String feedUrl, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        log.debug("WebDriver로 RSS 피드 파싱 시도: {}", feedUrl);
        
        try {
            // WebDriver로 RSS 피드 URL에 접근
            log.debug("WebDriver로 RSS 피드 접근 시작: {}", feedUrl);
            driver.get(feedUrl);
            Thread.sleep(2000); // 페이지 로딩 대기
            
            // 페이지 소스 가져오기
            String pageSource = driver.getPageSource();
            log.debug("WebDriver 페이지 소스 길이: {}", pageSource.length());
            log.debug("WebDriver 페이지 소스 시작 200자: {}", pageSource.substring(0, Math.min(200, pageSource.length())));
            
            // Chrome XML 뷰어로 래핑된 경우 실제 XML 추출
            String actualXml = extractXmlFromChromeViewer(pageSource);
            log.debug("XML 추출 후 길이: {}, 시작 200자: {}", actualXml.length(), actualXml.substring(0, Math.min(200, actualXml.length())));
            
            // XML 형식인지 확인
            if (!actualXml.trim().startsWith("<?xml") && !actualXml.contains("<rss") && !actualXml.contains("<feed")) {
                log.debug("RSS 피드가 아닌 것으로 판단됨: {}", feedUrl);
                log.debug("actualXml 전체 내용 (처음 500자): {}", actualXml.substring(0, Math.min(500, actualXml.length())));
                throw new Exception("Not a valid RSS/Atom feed: " + feedUrl);
            }
            
            log.debug("RSS 피드 XML 감지됨: {}", feedUrl);
            
            // Rome 라이브러리로 XML 파싱
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new java.io.StringReader(actualXml));
            
            log.info("WebDriver RSS 피드 파싱 성공 - URL: {}, 제목: {}, 엔트리 수: {}", 
                    feedUrl, feed.getTitle(), feed.getEntries().size());
            
            for (SyndEntry entry : feed.getEntries()) {
                try {
                    Article article = convertSyndEntryToArticle(entry, corporation);
                    if (article != null) {
                        articles.add(article);
                    }
                } catch (Exception e) {
                    log.warn("피드 엔트리 변환 실패: {} - {}", entry.getTitle(), e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.debug("WebDriver RSS 피드 파싱 실패: {} - {}", feedUrl, e.getMessage());
            log.debug("WebDriver RSS 파싱 예외 상세: ", e);
            throw new Exception("WebDriver RSS 피드 파싱 실패: " + feedUrl, e);
        }
        
        return articles;
    }
    
    /**
     * SyndEntry를 Article로 변환
     */
    private Article convertSyndEntryToArticle(SyndEntry entry, Corporation corporation) {
        String title = entry.getTitle();
        if (title == null || title.trim().length() < 5) {
            return null;
        }
        
        String link = entry.getLink();
        if (link == null || link.trim().isEmpty()) {
            return null;
        }
        
        String summary = "";
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            summary = Jsoup.parse(entry.getDescription().getValue()).text();
            if (summary.length() > 200) {
                summary = summary.substring(0, 200) + "...";
            }
        }
        
        // 발행일 처리
        LocalDateTime publishedAt = LocalDateTime.now();
        Date publishedDate = entry.getPublishedDate();
        if (publishedDate == null) {
            publishedDate = entry.getUpdatedDate();
        }
        if (publishedDate != null) {
            publishedAt = publishedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        
        return Article.builder()
                .corporation(corporation)
                .title(title.trim())
                .summary(summary)
                .link(link)
                .publishedAt(publishedAt)
                .build();
    }

    /**
     * Chrome XML 뷰어로 래핑된 경우 실제 XML 추출
     */
    private String extractXmlFromChromeViewer(String pageSource) {
        log.debug("Chrome XML 뷰어 추출 시작 - 페이지 소스 길이: {}", pageSource.length());
        
        if (pageSource.contains("xml-viewer")) {
            log.debug("Chrome XML 뷰어 감지됨 - 실제 XML 추출 시도");
            
            try {
                Document doc = Jsoup.parse(pageSource, "", Parser.xmlParser());
                
                Elements rssElements = doc.select("rss");
                if (rssElements.isEmpty()) {
                    throw new IOException("rss 태그를 찾을 수 없습니다.");    
                }

                String xmlContent = rssElements.first().outerHtml();
                log.debug("Chrome XML 뷰어에서 XML 추출 성공 - 추출된 XML 길이: {}", xmlContent.length());
                return xmlContent;
            } catch (Exception e) {
                log.warn("Chrome XML 뷰어에서 XML 추출 실패: {}", e.getMessage());
            }
        }
        
        return pageSource;
    }
    
    @Override
    public String getProviderName() {
        return "Default";
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

            // 페이지 로딩 대기 (2초)
            Thread.sleep(2000);

            // HTML 소스 가져오기
            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);

            // body 태그에서 텍스트 추출
            Element body = doc.selectFirst("body");
            if (body == null) {
                log.warn("본문 추출 실패: body 태그를 찾을 수 없음 - {}", articleUrl);
                return "";
            }

            String content = body.text();
            log.debug("본문 추출 완료: {} (길이: {}자)", articleUrl, content.length());

            return content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("본문 추출 중 인터럽트 발생: {}", articleUrl, e);
            return "";
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

        // 페이지네이션 타입이 NONE이거나 null이면 첫 페이지만 크롤링
        if (parsingSelector.getPaginationType() == null || "NONE".equals(parsingSelector.getPaginationType())) {
            log.info("페이지네이션 타입이 NONE - 첫 페이지만 크롤링: {}", corporation.getName());
            return crawl(driver, corporation);
        }

        // INFINITE_SCROLL 타입이면 무한스크롤 전용 로직 사용
        if ("INFINITE_SCROLL".equals(parsingSelector.getPaginationType())) {
            log.info("페이지네이션 타입이 INFINITE_SCROLL - 무한스크롤 크롤링: {}", corporation.getName());
            return crawlWithInfiniteScroll(driver, corporation);
        }

        List<Article> allArticles = new ArrayList<>();
        int currentPage = 1;
        Integer maxPages = parsingSelector.getMaxPages();
        String paginationType = parsingSelector.getPaginationType();

        log.info("전체 페이지 크롤링 시작 - 기업: {}, 타입: {}, 최대 페이지: {}",
                corporation.getName(), paginationType, maxPages != null ? maxPages : "무제한");

        try {
            while (true) {
                // 페이지 URL 생성 및 이동
                String pageUrl = buildPageUrl(corporation.getBlogLink(), currentPage);
                log.info("페이지 {} 크롤링 시작: {}", currentPage, pageUrl);

                driver.get(pageUrl);
                Thread.sleep(5000); // 페이지 로드 대기

                // 스크롤 시뮬레이션
                performScrollSimulation(driver);

                // 페이지에서 article 파싱
                String pageSource = driver.getPageSource();
                List<Article> pageArticles = parseArticlesFromPage(pageSource, corporation);
                log.debug("발견된 article 요소 수: {}", pageArticles.size());

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
                Thread.sleep(2000); // 페이지 간 딜레이
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
        // 예: https://techblog.lycorp.co.jp/ko/page/1 -> https://techblog.lycorp.co.jp/ko
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
                    Document doc = Jsoup.parse(driver.getPageSource());
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
     * 무한스크롤 크롤링
     */
    private List<Article> crawlWithInfiniteScroll(WebDriver driver, Corporation corporation) throws CrawlerException {
        java.util.Set<String> collectedLinks = new java.util.HashSet<>();
        List<Article> allArticles = new ArrayList<>();

        try {
            driver.get(corporation.getBlogLink());
            Thread.sleep(5000); // 초기 페이지 로딩 대기

            int maxScrollAttempts = 100; // 최대 스크롤 횟수 (안전장치)
            int noNewArticlesCount = 0;  // 새 article이 없는 연속 횟수
            int scrollCount = 0;

            log.info("무한스크롤 크롤링 시작 - 기업: {}", corporation.getName());

            while (scrollCount < maxScrollAttempts) {
                // 현재 페이지에서 article 수집
                int beforeCount = collectedLinks.size();
                List<Article> newArticles = parseArticlesFromCurrentPage(driver, corporation, collectedLinks);
                allArticles.addAll(newArticles);
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

                // 페이지 끝까지 스크롤
                scrollToBottom(driver);
                Thread.sleep(2000 + random.nextInt(1000)); // 새 콘텐츠 로딩 대기

                scrollCount++;
            }

            if (scrollCount >= maxScrollAttempts) {
                log.warn("최대 스크롤 횟수 {}회 도달 - 크롤링 종료", maxScrollAttempts);
            }

            log.info("무한스크롤 크롤링 완료 - 기업: {}, 총 스크롤: {}회, 총 글: {}개",
                    corporation.getName(), scrollCount, allArticles.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(corporation.getBlogLink(), 10);
        } catch (Exception e) {
            log.error("무한스크롤 크롤링 중 오류 - 기업: {}", corporation.getName(), e);
            throw new CrawlerException("CRAWLER_ERROR", "Error during infinite scroll crawling", e) {};
        }

        return allArticles;
    }

    /**
     * 페이지 끝까지 스크롤
     */
    private void scrollToBottom(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
    }

    /**
     * 현재 페이지에서 article 파싱 (중복 제거)
     */
    private List<Article> parseArticlesFromCurrentPage(WebDriver driver, Corporation corporation, java.util.Set<String> collectedLinks) {
        List<Article> newArticles = new ArrayList<>();
        String pageSource = driver.getPageSource();
        Document doc = Jsoup.parse(pageSource);

        Elements articleElements = doc.select(parsingSelector.getArticle());
        for (Element element : articleElements) {
            try {
                Article article = parseArticle(element, corporation);
                if (article != null && article.getLink() != null && !collectedLinks.contains(article.getLink())) {
                    collectedLinks.add(article.getLink());
                    newArticles.add(article);
                }
            } catch (Exception e) {
                log.warn("기본 크롤러 개별 아티클 파싱 실패 (무한스크롤) - 오류 타입: {}, 오류: {}, HTML: {}",
                        e.getClass().getSimpleName(), e.getMessage(), element.outerHtml().substring(0, Math.min(200, element.outerHtml().length())), e);
            }
        }

        return newArticles;
    }

}