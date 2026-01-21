package com.newcodes7.small_town.crawler.crawler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerTimeoutException;
import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
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

            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);

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

            // JSON 파싱 및 Article 추출
            articles = parseArticlesFromApolloState(apolloStateJson, corporation);

            log.info("{} - APOLLO_STATE에서 추출한 아티클 수: {}", link, articles.size());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(link, 10);
        }
        
        return articles;
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

            return Article.builder()
                    .corporation(corporation)
                    .title(title)
                    .link(link)
                    .content("") // 본문은 별도 백필 API로 추출 (크롤링 성능 고려)
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