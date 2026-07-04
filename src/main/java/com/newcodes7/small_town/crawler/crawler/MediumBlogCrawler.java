package com.newcodes7.small_town.crawler.crawler;

import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerTimeoutException;
import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.BlogType;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.util.TimeUtil;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class MediumBlogCrawler implements BlogCrawler {

    private final Random random = new Random();
    private final S3ImageService s3ImageService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * URL 기반 판단 (하위 호환성 유지)
     * @deprecated canHandle(Corporation corporation)을 사용하세요
     */
    @Deprecated
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

    /**
     * Corporation의 blogType 필드를 기반으로 판단
     * blogType이 MEDIUM이면 이 크롤러를 사용
     */
    @Override
    public boolean canHandle(Corporation corporation) {
        return corporation != null && corporation.getBlogType() == BlogType.MEDIUM;
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

            String pageSource = driver.getPageSource();

            Files.writeString(Path.of("medium_page.html"), pageSource);

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

            // RSS에서 본문 미리 수집 (Cloudflare 우회)
            Map<String, String> rssContentMap = fetchRssContentMap(link);

            // JSON 파싱 및 Article 추출
            articles = parseArticlesFromApolloState(apolloStateJson, corporation, rssContentMap);

            log.info("{} - APOLLO_STATE에서 추출한 아티클 수: {}", link, articles.size());

            // APOLLO_STATE 파싱 실패 시 RSS 직접 파싱으로 fallback
            if (articles.isEmpty()) {
                log.info("{} - APOLLO_STATE 파싱 실패, RSS 직접 파싱으로 fallback", link);
                articles = crawlFromRssFallback(link, corporation);
                log.info("{} - RSS fallback으로 추출한 아티클 수: {}", link, articles.size());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(link, 10);
        }

        return articles;
    }

    /**
     * RSS feed에서 URL → 본문 맵 구성
     * Cloudflare가 차단하는 개별 페이지 대신 RSS로 본문을 미리 수집
     */
    private Map<String, String> fetchRssContentMap(String blogLink) {
        Map<String, String> contentMap = new HashMap<>();
        try {
            String feedUrl = buildFeedUrlFromBlogLink(blogLink);
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));

            for (SyndEntry entry : feed.getEntries()) {
                String content = extractTextFromRssEntry(entry);
                if (entry.getLink() != null && !content.isEmpty()) {
                    contentMap.put(stripQueryParams(entry.getLink()), content);
                }
                // uri도 키로 등록 (Medium에서 link와 uri가 다를 수 있음)
                if (entry.getUri() != null && !content.isEmpty()) {
                    contentMap.put(stripQueryParams(entry.getUri()), content);
                }
            }
            log.info("RSS 본문 수집 완료: {}개 아티클, feedUrl: {}", contentMap.size(), feedUrl);
        } catch (Exception e) {
            log.warn("RSS 본문 수집 실패 (APOLLO_STATE만 사용): {} - {}", blogLink, e.getMessage());
        }
        return contentMap;
    }

    /**
     * RSS entry에서 텍스트 본문 추출 (content:encoded 우선, description fallback)
     */
    private String extractTextFromRssEntry(SyndEntry entry) {
        // content:encoded 우선
        List<SyndContent> contents = entry.getContents();
        if (!contents.isEmpty() && contents.get(0).getValue() != null) {
            return extractTextFromHtml(contents.get(0).getValue());
        }
        // description fallback
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            return extractTextFromHtml(entry.getDescription().getValue());
        }
        return "";
    }

    /**
     * HTML에서 블록 요소 텍스트 추출
     */
    private String extractTextFromHtml(String html) {
        Document doc = Jsoup.parse(html);
        Elements blocks = doc.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre");
        return blocks.stream()
                .map(Element::text)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n\n"))
                .trim();
    }

    /**
     * article URL이 RSS 본문 맵의 키와 일치하는지 확인 (slug 기반 매칭 포함)
     */
    private String findContentByUrl(Map<String, String> rssContentMap, String articleUrl) {
        if (rssContentMap.isEmpty() || articleUrl == null) return "";

        // 정확한 URL 매칭
        if (rssContentMap.containsKey(articleUrl)) {
            return rssContentMap.get(articleUrl);
        }

        // URL 끝 slash 정규화 후 매칭
        String normalizedUrl = articleUrl.replaceAll("/$", "");
        for (Map.Entry<String, String> entry : rssContentMap.entrySet()) {
            if (entry.getKey().replaceAll("/$", "").equals(normalizedUrl)) {
                return entry.getValue();
            }
        }

        // slug 기반 매칭 (Medium URL의 마지막 경로 세그먼트)
        try {
            String slug = new URI(articleUrl).getPath().replaceAll("/$", "");
            for (Map.Entry<String, String> entry : rssContentMap.entrySet()) {
                String entrySlug = new URI(entry.getKey()).getPath().replaceAll("/$", "");
                if (slug.equals(entrySlug)) {
                    return entry.getValue();
                }
            }
        } catch (Exception e) {
            log.debug("slug 기반 매칭 실패: {}", articleUrl);
        }

        return "";
    }

    /**
     * RSS feed에서 직접 Article 목록 파싱 (APOLLO_STATE 파싱 실패 시 fallback)
     */
    private List<Article> crawlFromRssFallback(String blogLink, Corporation corporation) {
        List<Article> articles = new ArrayList<>();
        try {
            String feedUrl = buildFeedUrlFromBlogLink(blogLink);
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));

            for (SyndEntry entry : feed.getEntries()) {
                try {
                    String title = entry.getTitle();
                    String link = stripQueryParams(entry.getLink());
                    if (title == null || title.isBlank() || link == null || link.isBlank()) continue;

                    String content = extractTextFromRssEntry(entry);
                    String thumbnailImage = extractThumbnailFromRssEntry(entry);

                    LocalDateTime publishedAt = null;
                    if (entry.getPublishedDate() != null) {
                        publishedAt = entry.getPublishedDate().toInstant()
                                .atZone(ZoneId.of("Asia/Seoul"))
                                .toLocalDateTime();
                    } else if (entry.getUpdatedDate() != null) {
                        publishedAt = entry.getUpdatedDate().toInstant()
                                .atZone(ZoneId.of("Asia/Seoul"))
                                .toLocalDateTime();
                    } else {
                        publishedAt = TimeUtil.nowInSeoul();
                    }

                    articles.add(Article.builder()
                            .corporation(corporation)
                            .title(title)
                            .link(link)
                            .content(content)
                            .thumbnailImage(thumbnailImage)
                            .publishedAt(publishedAt)
                            .viewCount(0)
                            .likeCount(0)
                            .build());
                } catch (Exception e) {
                    log.warn("RSS entry 파싱 실패: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("RSS fallback 크롤링 실패: {} - {}", blogLink, e.getMessage());
        }
        return articles;
    }

    /**
     * RSS entry에서 썸네일 이미지 URL 추출
     * content:encoded 또는 description HTML에서 첫 번째 실제 이미지 추출
     * 1x1 추적 픽셀은 제외
     */
    private String extractThumbnailFromRssEntry(SyndEntry entry) {
        String html = null;
        List<SyndContent> contents = entry.getContents();
        if (!contents.isEmpty() && contents.get(0).getValue() != null) {
            html = contents.get(0).getValue();
        } else if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            html = entry.getDescription().getValue();
        }
        if (html == null) return "";

        Document doc = Jsoup.parse(html);
        for (Element img : doc.select("img[src]")) {
            String src = img.attr("src");
            // 1x1 추적 픽셀 제외
            String width = img.attr("width");
            String height = img.attr("height");
            if ("1".equals(width) || "1".equals(height)) continue;
            if (!src.isBlank()) return src;
        }
        return "";
    }

    /**
     * URL에서 쿼리 파라미터 제거 (RSS ?source=rss-... 파라미터 정리용)
     */
    private String stripQueryParams(String url) {
        if (url == null) return null;
        int idx = url.indexOf('?');
        return idx >= 0 ? url.substring(0, idx) : url;
    }

    /**
     * 블로그 링크(corporation.blogLink)에서 RSS feed URL 생성
     * - medium.com 호스팅 publication: medium.com/watcha → medium.com/feed/watcha
     * - 커스텀 도메인: techblog.gccompany.co.kr → techblog.gccompany.co.kr/feed
     */
    private String buildFeedUrlFromBlogLink(String blogLink) {
        try {
            URI uri = URI.create(blogLink.replaceAll("/$", ""));
            if ("medium.com".equals(uri.getHost())) {
                // publication slug는 첫 번째 경로 세그먼트만 사용
                // e.g., /ssgtech/all → /feed/ssgtech
                String[] segments = uri.getPath().split("/");
                String publicationSlug = segments.length > 1 ? "/" + segments[1] : uri.getPath();
                return "https://medium.com/feed" + publicationSlug;
            }
            return uri.getScheme() + "://" + uri.getHost() + "/feed";
        } catch (Exception e) {
            return blogLink.replaceAll("/$", "") + "/feed";
        }
    }

    /**
     * 아티클 URL에서 RSS feed URL 생성 (extractContentFromRssFallback 전용)
     * - medium.com/watcha/article-slug → medium.com/feed/watcha
     * - techblog.gccompany.co.kr/article-slug → techblog.gccompany.co.kr/feed
     */
    private String buildFeedUrlFromArticleUrl(String articleUrl) {
        try {
            URI uri = new URI(articleUrl);
            if ("medium.com".equals(uri.getHost())) {
                // 경로의 첫 번째 세그먼트가 publication slug: /watcha/article → /feed/watcha
                String[] segments = uri.getPath().split("/");
                String publication = segments.length > 1 ? segments[1] : "";
                return "https://medium.com/feed/" + publication;
            }
            return uri.getScheme() + "://" + uri.getHost() + "/feed";
        } catch (Exception e) {
            log.warn("feed URL 생성 실패: {}", articleUrl);
            return "";
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

    /**
     * 모든 페이지 크롤링 (Admin 전용)
     * Medium 무한 스크롤 방식으로 전체 글 수집
     */
    @Override
    public List<Article> crawlAllPages(WebDriver driver, Corporation corporation) throws CrawlerException {
        java.util.Set<String> collectedPostIds = new java.util.HashSet<>();
        List<Article> allArticles = new ArrayList<>();

        try {
            // Bot 감지 우회 설정
            setupAntiDetection(driver);

            driver.get(corporation.getBlogLink());
            Thread.sleep(3000); // 초기 페이지 로딩 대기

            // 인간처럼 페이지 행동 시뮬레이션
            simulateHumanBehavior(driver);

            JavascriptExecutor js = (JavascriptExecutor) driver;
            int maxScrollAttempts = 100; // 최대 스크롤 횟수
            int noNewArticlesCount = 0;  // 새 article이 없는 연속 횟수
            int scrollCount = 0;

            log.info("Medium 무한스크롤 크롤링 시작 - 기업: {}", corporation.getName());

            while (scrollCount < maxScrollAttempts) {
                // APOLLO_STATE에서 현재까지 로드된 글 수집
                int beforeCount = collectedPostIds.size();

                String apolloStateJson = (String) js.executeScript(
                    "return JSON.stringify(window.__APOLLO_STATE__ || {});"
                );

                if (apolloStateJson != null && !apolloStateJson.equals("{}")) {
                    List<Article> newArticles = parseArticlesFromApolloStateWithDedup(
                        apolloStateJson, corporation, collectedPostIds
                    );
                    allArticles.addAll(newArticles);
                }

                int afterCount = collectedPostIds.size();

                // 새로운 article이 추가되었는지 확인
                if (afterCount == beforeCount) {
                    noNewArticlesCount++;
                    log.debug("스크롤 {}: 새로운 article 없음 ({}/3)", scrollCount + 1, noNewArticlesCount);
                    // 처음 10번 스크롤은 무조건 시도, 이후부터는 연속 3번 새 article 없으면 종료
                    if (scrollCount >= 10 && noNewArticlesCount >= 3) {
                        log.info("연속 3번 새 article 없음 (10회 스크롤 이후) - 크롤링 종료");
                        break;
                    }
                } else {
                    int newCount = afterCount - beforeCount;
                    noNewArticlesCount = 0;
                    log.info("스크롤 {}: 새로운 article {}개 발견 (총 {}개)", scrollCount + 1, newCount, afterCount);
                }

                // 페이지 끝까지 스크롤
                js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
                Thread.sleep(2000 + random.nextInt(1000)); // 새 콘텐츠 로딩 대기

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

            // Bot 감지 우회 설정 (페이지 로드 후 실행해야 현재 페이지에 적용됨)
            setupAntiDetection(driver);

            // Cloudflare 챌린지 대기 및 통과
            waitForCloudflareChallenge(driver, articleUrl);

            // 인간처럼 페이지 행동 시뮬레이션
            simulateHumanBehavior(driver);

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

            // Cloudflare 챌린지 페이지인지 최종 확인
            if (isCloudflareChallengePage(content)) {
                log.warn("본문 추출 실패: Cloudflare 챌린지 통과 실패 - {}, RSS fallback 시도", articleUrl);
                String rssFallback = extractContentFromRssFallback(articleUrl);
                if (!rssFallback.isEmpty()) return rssFallback;
                return extractContentFromJinaAi(articleUrl);
            }

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
     * RSS feed를 통한 본문 추출 fallback
     * Cloudflare가 Selenium을 차단할 때 RSS feed로 본문을 가져옴
     */
    private String extractContentFromRssFallback(String articleUrl) {
        try {
            String feedUrl = buildFeedUrlFromArticleUrl(articleUrl);

            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new URL(feedUrl)));

            for (SyndEntry entry : feed.getEntries()) {
                // URL 매칭 (정확한 매칭 + slug 기반 매칭)
                String entryLink = entry.getLink();
                if (entryLink != null) {
                    String normalizedArticle = articleUrl.replaceAll("/$", "");
                    String normalizedEntry = entryLink.replaceAll("/$", "");
                    // 정확한 매칭 또는 slug(경로) 기반 매칭
                    boolean matched = normalizedArticle.equals(normalizedEntry)
                            || new URI(normalizedArticle).getPath().equals(new URI(normalizedEntry).getPath());
                    if (matched) {
                        String content = extractTextFromRssEntry(entry);
                        if (!content.isEmpty()) {
                            log.info("RSS fallback 성공: {} ({}자)", articleUrl, content.length());
                            return content;
                        }
                    }
                }
            }

            log.warn("RSS fallback 실패: 해당 article을 RSS에서 찾을 수 없음 - {}", articleUrl);
            return "";
        } catch (Exception e) {
            log.warn("RSS fallback 실패: {} - {}", articleUrl, e.getMessage());
            return "";
        }
    }

    /**
     * Jina.ai Reader API를 통한 본문 추출 (최후 fallback)
     * RSS에도 없는 오래된 아티클을 Cloudflare 없이 가져올 때 사용
     * https://r.jina.ai/{url} → plain text Markdown 반환
     */
    private String extractContentFromJinaAi(String articleUrl) {
        try {
            String jinaUrl = "https://r.jina.ai/" + articleUrl;
            String response = Jsoup.connect(jinaUrl)
                    .header("Accept", "text/plain")
                    .header("User-Agent", "Mozilla/5.0")
                    .ignoreContentType(true)
                    .timeout(30_000)
                    .execute()
                    .body();

            if (response == null || response.isBlank()) return "";

            // "Markdown Content:" 이후 본문만 추출
            int contentStart = response.indexOf("Markdown Content:");
            if (contentStart >= 0) {
                response = response.substring(contentStart + "Markdown Content:".length()).trim();
            }

            // 마크다운 이미지 제거, 링크는 텍스트만, 헤더 # 제거
            response = response
                    .replaceAll("!\\[.*?\\]\\(.*?\\)", "")
                    .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                    .replaceAll("(?m)^#+\\s*", "")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();

            if (response.length() < 200) return "";

            log.info("Jina.ai fallback 성공: {} ({}자)", articleUrl, response.length());
            return response;
        } catch (Exception e) {
            log.warn("Jina.ai fallback 실패: {} - {}", articleUrl, e.getMessage());
            return "";
        }
    }

    /**
     * Cloudflare 챌린지 페이지 대기 및 통과
     * 최대 15초까지 대기하며 챌린지가 자동 해결되기를 기다림
     */
    private void waitForCloudflareChallenge(WebDriver driver, String url) throws InterruptedException {
        int maxWaitSeconds = 30;
        int waitedSeconds = 0;
        int checkIntervalMs = 1000;

        while (waitedSeconds < maxWaitSeconds) {
            String pageSource = driver.getPageSource();

            // Cloudflare 챌린지 페이지가 아니면 통과
            if (!isCloudflareChallengePage(pageSource)) {
                if (waitedSeconds > 0) {
                    log.info("Cloudflare 챌린지 통과 완료 ({}초 소요): {}", waitedSeconds, url);
                }
                return;
            }

            log.debug("Cloudflare 챌린지 대기 중... ({}초/{}초): {}", waitedSeconds, maxWaitSeconds, url);
            Thread.sleep(checkIntervalMs);
            waitedSeconds++;
        }

        log.warn("Cloudflare 챌린지 대기 시간 초과 ({}초): {}", maxWaitSeconds, url);
    }

    /**
     * Cloudflare 챌린지 페이지인지 확인
     */
    private boolean isCloudflareChallengePage(String pageContent) {
        if (pageContent == null) return false;

        // Cloudflare 챌린지 페이지의 특징적인 문구들
        return pageContent.contains("보안 확인 수행 중")
                || pageContent.contains("악의적 봇으로부터 보호")
                || pageContent.contains("사람인지 확인")
                || pageContent.contains("보안을 검토")
                || pageContent.contains("응답을 기다리는 중")
                || pageContent.contains("Checking your browser")
                || pageContent.contains("Just a moment")
                || pageContent.contains("Verify you are human")
                || pageContent.contains("cf-browser-verification")
                || pageContent.contains("cf_chl_opt");
    }

    /**
     * window.__APOLLO_STATE__에서 Article 목록 파싱
     */
    private List<Article> parseArticlesFromApolloState(String apolloStateJson, Corporation corporation) {
        return parseArticlesFromApolloStateWithDedup(apolloStateJson, corporation, null, Map.of());
    }

    /**
     * window.__APOLLO_STATE__에서 Article 목록 파싱 (RSS 본문 맵 포함)
     */
    private List<Article> parseArticlesFromApolloState(String apolloStateJson, Corporation corporation, Map<String, String> rssContentMap) {
        return parseArticlesFromApolloStateWithDedup(apolloStateJson, corporation, null, rssContentMap);
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
        return parseArticlesFromApolloStateWithDedup(apolloStateJson, corporation, collectedPostIds, Map.of());
    }

    private List<Article> parseArticlesFromApolloStateWithDedup(
            String apolloStateJson,
            Corporation corporation,
            java.util.Set<String> collectedPostIds,
            Map<String, String> rssContentMap) {
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
            for (String key : rootQuery.propertyNames()) {
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
            for (String key : publication.propertyNames()) {
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

                    Article article = parseArticleFromApolloPost(postNode, root, corporation, rssContentMap);
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
        return parseArticleFromApolloPost(postNode, root, corporation, Map.of());
    }

    private Article parseArticleFromApolloPost(JsonNode postNode, JsonNode root, Corporation corporation, Map<String, String> rssContentMap) {
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

            // RSS에서 본문 조회 (크롤 시점에 바로 채움)
            String content = findContentByUrl(rssContentMap, link);
            if (!content.isEmpty()) {
                log.debug("RSS에서 본문 수집 완료: {} ({}자)", title, content.length());
            }

            return Article.builder()
                    .corporation(corporation)
                    .title(title)
                    .link(link)
                    .content(content) // RSS에서 가져온 본문 (없으면 빈 문자열, 백필 스케줄러가 처리)
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
