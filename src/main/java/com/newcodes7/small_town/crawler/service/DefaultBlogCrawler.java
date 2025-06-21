package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;

import lombok.extern.slf4j.Slf4j;

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
            articles = crawlHtmlContent(driver, corporation);
            log.info("기본 크롤러 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
        } catch (Exception e) {
            log.error("기본 크롤러 실패 - 기업: {}, 오류: {}", corporation.getName(), e.getMessage());
            throw e;
        }
        
        return articles;
    }

    /**
     * HTML 콘텐츠 크롤링 (기존 로직)
     */
    private List<Article> crawlHtmlContent(WebDriver driver, Corporation corporation) throws Exception {
        List<Article> articles = new ArrayList<>();
        
        driver.get(corporation.getBlogLink());
        Thread.sleep(2000);
        
        String pageSource = driver.getPageSource();
        Document doc = Jsoup.parse(pageSource);
        // 디버그용: doc 파일에 저장
        Files.write(Paths.get("doc.html"), doc.html().getBytes(StandardCharsets.UTF_8));

        // 다양한 CSS 선택자로 아티클 찾기
        Elements articleElements = doc.select(
            "article, .post, .entry, .blog-post, .item, " +
            "[class*='post'], [class*='article'], [class*='entry'], " +
            "[id*='post'], [id*='article'], [id*='entry']"
        );

        if (corporation.getBlogLink().contains("toss.tech")) {
            articleElements = doc.select("a[class*='css-1qr3mg1'], a[class*='e1sck7qg4']");
        } else if (corporation.getBlogLink().contains("aws")) {
            articleElements = doc.select("article[class*='blog-post']");
        } else if (corporation.getBlogLink().contains("googleblog")) {
            articleElements = doc.select("div[class*='search-result__wrapper']");
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
        
        return articles;
    }

    
    /**
     * HTML 요소에서 Article 파싱 (기존 로직 유지)
     */
    private Article parseArticle(Element element, Corporation corporation) {
        try {
            // 제목 찾기
            Element titleElement = element.selectFirst(
                "h1, h2, h3, h4, .title, .post-title, .entry-title, " +
                "[class*='title'], [class*='heading'], a[href]"
            );
            if (corporation.getBlogLink().contains("kakao.com")) {
                titleElement = element.selectFirst("h4");
            }
            if (corporation.getBlogLink().contains("toss.tech")) {
                titleElement = element.selectFirst("span[class*='typography--h6']");
            }
            if (corporation.getBlogLink().contains("aws")) {
                titleElement = element.selectFirst("span[property*='name headline']");
            }
            if (corporation.getBlogLink().contains("googleblog")) {
                titleElement = element.selectFirst("h3[class*='search-result__title']");
            }
            
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
                    if (corporation.getBlogLink().contains("googleblog")) {
                        baseUrl = "https://developers.googleblog.com";
                    }
                    if (baseUrl.endsWith("/")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    }
                    link = baseUrl + link;
                } else {
                    return null;
                }
            }

            // 카카오 블로그일 경우 링크 수정
            if (corporation.getBlogLink().contains("kakao.com")) {
                link = link.replace("https://tech.kakao.com/blog", "https://tech.kakao.com");
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
            Element imgElement = null;

            if (corporation.getBlogLink().contains("toss.tech")) {
                // Toss 기술블로그의 경우 두 번째 img 태그 선택
                Elements imgElements = element.select("img");
                if (imgElements.size() >= 2) {
                    imgElement = imgElements.get(1); // 두 번째 img 태그 (인덱스 1)
                } else if (imgElements.size() == 1) {
                    imgElement = imgElements.get(0); // 하나만 있다면 첫 번째 사용
                }
            } else {
                // 다른 기업 블로그의 경우 첫 번째 img 태그 선택
                imgElement = element.selectFirst("img");
            }

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

            // 발행일 찾기 (네이버 d2 기준)
            // TODO: 더 유지보수하기 쉽게 역할 분리
            Element publishElement;
            DateTimeFormatter customFormatter;
            if (corporation.getBlogLink().contains("d2.naver.com")) {
                publishElement = element.selectFirst("dd");
                customFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");
            } else if (corporation.getBlogLink().contains("kakao.com")) {
                publishElement = element.selectFirst("dd[class*='txt_date']");
                customFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");
            } else {
                publishElement = element.selectFirst("time, [datetime], [class*='date'], [class*='time']");
                customFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            }

            LocalDateTime publishedAt;
            
            if (corporation.getBlogLink().contains("toss.tech")) {
                publishElement = element.selectFirst("span[class*='typography--small']");
                String rawDateText = publishElement.text();
                String cleanDateText = extractDateOnly(rawDateText);
                publishedAt = parseKoreanDate(cleanDateText);
            } else if (corporation.getBlogLink().contains("aws")) {
                Element timeElement = element.selectFirst("time");
                String datetime = timeElement.attr("datetime");
                ZonedDateTime zonedDateTime = ZonedDateTime.parse(datetime);
                publishedAt = zonedDateTime.toLocalDateTime();
            } else if (corporation.getBlogLink().contains("googleblog")) {
                // ex. 2025년 6월 18일 / Cloud
                Element timeElement = element.selectFirst("p[class*='search-result__eyebrow']");
                String dateText = timeElement.text().trim();
                String cleanDateText = extractDateOnly(dateText);
                publishedAt = parseKoreanDate(cleanDateText);
            } else {
                publishedAt = publishElement != null ? 
                LocalDate.parse(publishElement.text().trim(), customFormatter).atStartOfDay() : 
                LocalDateTime.now();
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
            log.warn("기본 크롤러 아티클 파싱 오류: {}", e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private LocalDateTime parseKoreanDate(String dateText) {
        Pattern pattern = Pattern.compile("(\\d{4})년(\\d{1,2})월(\\d{1,2})일");
        Matcher matcher = pattern.matcher(dateText);
        
        if (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            
            LocalDate date = LocalDate.of(year, month, day);
            return date.atStartOfDay(); // 00:00:00으로 변환
        }
        
        throw new DateTimeParseException("한국어 날짜 형식을 파싱할 수 없습니다", dateText, 0);
    }

    private String extractDateOnly(String dateText) {
        // 한국어 날짜 패턴 (yyyy년MM월dd일 또는 yyyy년M월d일)
        Pattern datePattern = Pattern.compile("(\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일)");
        Matcher matcher = datePattern.matcher(dateText);
        
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\s+", ""); 
        }
        return dateText;
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
                .corporationId(corporation.getId())
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
}