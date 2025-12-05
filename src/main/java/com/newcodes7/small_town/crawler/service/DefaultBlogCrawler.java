package com.newcodes7.small_town.crawler.service;

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
            log.error("기본 크롤러 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("기본 크롤러 예상치 못한 오류 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage(), e);
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
            
            String pageSource = driver.getPageSource();
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
                    log.warn("기본 크롤러 개별 아티클 파싱 실패: {}", e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(corporation.getBlogLink(), 2);
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
            log.warn("기본 크롤러 아티클 파싱 오류: {}", e.getMessage());
            e.printStackTrace();
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
        if (baseUrl.contains("toss")) {
            Elements imgElements = imgElement.parent().select("img[alt*='thumbnail']");
            if (imgElements.size() > 1) {
                imgElement = imgElements.get(1);
            }
        }

        // NHN Cloud, KT Cloud: CSS background-image
        if (baseUrl.contains("nhncloud") || baseUrl.contains("ktcloud")) {
            return extractCssImgUrl(imgElement.attr("style"));
        }

        // 강남언니: srcset 속성
        if (baseUrl.contains("gangnamunni")) {
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
        String publishFormat = parsingSelector.getPublishFormat();
        String dateText = publishElement.text().trim();
        LocalDateTime publishedAt;

        if (publishFormat.equals("yyyy.MM.dd")
            || publishFormat.equals("yyyy.M.dd")
            || publishFormat.equals("yyyy-MM-dd")) {
            publishedAt = parseKoreanDateFormat(dateText);
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
        dateText = dateText.replaceAll("[^a-zA-Z0-9\\s]", "");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
        LocalDate date = LocalDate.parse(dateText, formatter);
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
}