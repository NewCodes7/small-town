package com.newcodes7.small_town.crawler.crawler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.BlogType;
import com.newcodes7.small_town.global.entity.Corporation;

/**
 * MediumBlogCrawler 단위 테스트
 *
 * WebDriver/Thread.sleep 없이 핵심 파싱 로직을 검증.
 * - canHandle: URL 기반 / BlogType.MEDIUM 기반
 * - parseArticlesFromApolloState (ReflectionTestUtils): mediumUrl / uniqueSlug /
 *   썸네일 유무 / 발행일 / 중복 제거 / 다양한 누락 케이스
 * - processImageUpload: S3 업로드 성공/실패/조건
 * - isCloudflareChallengePage (ReflectionTestUtils): 각 Cloudflare 시그니처
 */
@ExtendWith(MockitoExtension.class)
public class MediumBlogCrawlerTest {

    @Mock
    private S3ImageService s3ImageService;

    @InjectMocks
    private MediumBlogCrawler crawler;

    private Corporation netflixCorp;

    @BeforeEach
    void setUp() {
        netflixCorp = Corporation.builder()
                .id(1L)
                .name("Netflix")
                .blogLink("https://netflixtechblog.com")
                .blogType(BlogType.MEDIUM)
                .build();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────────────

    private String loadJson(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(is).as("리소스 파일 없음: " + resourcePath).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("JSON 파일 로드 실패: " + resourcePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Article> parseApolloState(String json, Corporation corp) {
        return (List<Article>) ReflectionTestUtils.invokeMethod(
                crawler, "parseArticlesFromApolloState", json, corp);
    }

    @SuppressWarnings("unchecked")
    private List<Article> parseApolloStateWithDedup(String json, Corporation corp, Set<String> seen) {
        return (List<Article>) ReflectionTestUtils.invokeMethod(
                crawler, "parseArticlesFromApolloStateWithDedup", json, corp, seen);
    }

    private boolean isCloudflare(String content) {
        return (Boolean) ReflectionTestUtils.invokeMethod(
                crawler, "isCloudflareChallengePage", content);
    }

    private String buildFeedUrl(String blogLink) {
        return (String) ReflectionTestUtils.invokeMethod(
                crawler, "buildFeedUrlFromBlogLink", blogLink);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fetchRssContentMap(String blogLink) {
        return (Map<String, String>) ReflectionTestUtils.invokeMethod(
                crawler, "fetchRssContentMap", blogLink);
    }

    private String findContentByUrl(Map<String, String> map, String url) {
        return (String) ReflectionTestUtils.invokeMethod(
                crawler, "findContentByUrl", map, url);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. canHandle
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        @DisplayName("URL 기반: medium.com 포함 → true")
        void canHandle_mediumUrl_true() {
            assertThat(crawler.canHandle("https://medium.com/test")).isTrue();
        }

        @Test
        @DisplayName("URL 기반: netflixtechblog.com → true")
        void canHandle_netflixUrl_true() {
            assertThat(crawler.canHandle("https://netflixtechblog.com")).isTrue();
        }

        @Test
        @DisplayName("URL 기반: yogiyo.co.kr → true")
        void canHandle_yogiyoUrl_true() {
            assertThat(crawler.canHandle("https://techblog.yogiyo.co.kr")).isTrue();
        }

        @Test
        @DisplayName("URL 기반: 일반 블로그 → false")
        void canHandle_otherUrl_false() {
            assertThat(crawler.canHandle("https://toss.tech")).isFalse();
        }

        @Test
        @DisplayName("URL 기반: null → false")
        void canHandle_nullUrl_false() {
            assertThat(crawler.canHandle((String) null)).isFalse();
        }

        @Test
        @DisplayName("Corporation 기반: BlogType.MEDIUM → true")
        void canHandle_mediumCorporation_true() {
            assertThat(crawler.canHandle(netflixCorp)).isTrue();
        }

        @Test
        @DisplayName("Corporation 기반: BlogType.DEFAULT → false")
        void canHandle_defaultCorporation_false() {
            Corporation corp = Corporation.builder().blogType(BlogType.DEFAULT).build();
            assertThat(crawler.canHandle(corp)).isFalse();
        }

        @Test
        @DisplayName("Corporation 기반: null → false")
        void canHandle_nullCorporation_false() {
            assertThat(crawler.canHandle((Corporation) null)).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. getProviderName
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProviderName → 'Medium'")
    void getProviderName() {
        assertThat(crawler.getProviderName()).isEqualTo("Medium");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. parseArticlesFromApolloState (fixture 기반)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseArticlesFromApolloState - JSON 픽스처")
    class ParseFromFixture {

        @Test
        @DisplayName("medium_apollo_state.json 픽스처: 3개 아티클 파싱")
        void fixture_returns3Articles() {
            String json = loadJson("medium_apollo_state.json");

            List<Article> articles = parseApolloState(json, netflixCorp);

            assertThat(articles).hasSize(3);
        }

        @Test
        @DisplayName("mediumUrl 있는 post → link = mediumUrl")
        void fixture_post001_mediumUrl() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            Article post001 = articles.stream()
                    .filter(a -> a.getTitle().contains("Building More Effective Teams"))
                    .findFirst().orElseThrow();

            assertThat(post001.getLink())
                    .isEqualTo("https://netflixtechblog.com/building-more-effective-teams-at-netflix-post001");
        }

        @Test
        @DisplayName("mediumUrl 없고 uniqueSlug 있는 post → link = medium.com/slug")
        void fixture_post002_uniqueSlug() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            Article post002 = articles.stream()
                    .filter(a -> a.getTitle().contains("GraphQL"))
                    .findFirst().orElseThrow();

            assertThat(post002.getLink())
                    .isEqualTo("https://medium.com/migrating-netflix-to-graphql-safely-post002");
        }

        @Test
        @DisplayName("previewImage 있는 post → thumbnail = Medium CDN URL")
        void fixture_post001_thumbnailUrl() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            Article post001 = articles.stream()
                    .filter(a -> a.getTitle().contains("Building More Effective Teams"))
                    .findFirst().orElseThrow();

            assertThat(post001.getThumbnailImage())
                    .startsWith("https://miro.medium.com/v2/resize:fit:1200/")
                    .contains("abc123thumbnailId");
        }

        @Test
        @DisplayName("previewImage 없는 post → thumbnailImage 빈 문자열")
        void fixture_post003_noThumbnail() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            Article post003 = articles.stream()
                    .filter(a -> a.getTitle().contains("Edge Computing"))
                    .findFirst().orElseThrow();

            assertThat(post003.getThumbnailImage()).isEmpty();
        }

        @Test
        @DisplayName("firstPublishedAt (ms timestamp) → LocalDateTime 변환")
        void fixture_post001_publishedAt() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            Article post001 = articles.stream()
                    .filter(a -> a.getTitle().contains("Building More Effective Teams"))
                    .findFirst().orElseThrow();

            // 1700000000000ms = 2023-11-15 (서울 시간)
            assertThat(post001.getPublishedAt())
                    .isNotNull()
                    .hasYear(2023);
        }

        @Test
        @DisplayName("파싱된 아티클 - corporation 참조 설정됨")
        void fixture_corporationSet() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            articles.forEach(a -> assertThat(a.getCorporation()).isEqualTo(netflixCorp));
        }

        @Test
        @DisplayName("파싱된 아티클 기본값 - viewCount/likeCount=0, content 빈 문자열")
        void fixture_defaultValues() {
            List<Article> articles = parseApolloState(loadJson("medium_apollo_state.json"), netflixCorp);

            articles.forEach(a -> {
                assertThat(a.getViewCount()).isEqualTo(0);
                assertThat(a.getLikeCount()).isEqualTo(0);
                assertThat(a.getContent()).isEmpty();
            });
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. parseArticlesFromApolloState - 누락/이상 케이스
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseArticlesFromApolloState - 누락/이상 케이스")
    class ParseEdgeCases {

        @Test
        @DisplayName("ROOT_QUERY 없음 → 빈 리스트")
        void noRootQuery_returnsEmpty() {
            String json = "{\"SomeOtherKey\": {}}";
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("Publication 참조 없음 → 빈 리스트")
        void noPublicationRef_returnsEmpty() {
            String json = "{\"ROOT_QUERY\": {\"unrelated\": \"value\"}}";
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("Publication 객체 없음 → 빈 리스트")
        void noPublicationObject_returnsEmpty() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:missing" }
                      }
                    }
                    """;
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("publicationPostsConnection 없음 → 빈 리스트")
        void noPostsConnection_returnsEmpty() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": { "name": "Test" }
                    }
                    """;
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("edges 배열 없음 → 빈 리스트")
        void noEdges_returnsEmpty() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": {
                        "publicationPostsConnection(x)": {}
                      }
                    }
                    """;
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("edges 빈 배열 → 빈 리스트")
        void emptyEdges_returnsEmpty() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": {
                        "publicationPostsConnection(x)": { "edges": [] }
                      }
                    }
                    """;
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("빈 JSON 문자열 → 빈 리스트 (예외 없음)")
        void emptyJson_returnsEmpty() {
            assertThat(parseApolloState("{}", netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("잘못된 JSON → 빈 리스트 (예외 없음)")
        void invalidJson_returnsEmpty() {
            assertThat(parseApolloState("not-json", netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("title 비어있는 post → skip")
        void emptyTitlePost_skipped() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": {
                        "publicationPostsConnection(x)": {
                          "edges": [{ "node": { "__ref": "Post:p1" } }]
                        }
                      },
                      "Post:p1": {
                        "title": "",
                        "mediumUrl": "https://medium.com/empty-title"
                      }
                    }
                    """;
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("link 없는 post (mediumUrl, uniqueSlug 모두 없음) → skip")
        void noLinkPost_skipped() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": {
                        "publicationPostsConnection(x)": {
                          "edges": [{ "node": { "__ref": "Post:p1" } }]
                        }
                      },
                      "Post:p1": {
                        "title": "링크없는 글"
                      }
                    }
                    """;
            assertThat(parseApolloState(json, netflixCorp)).isEmpty();
        }

        @Test
        @DisplayName("firstPublishedAt 없는 post → publishedAt = now() (null 아님)")
        void noFirstPublishedAt_usesNow() {
            String json = """
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": {
                        "publicationPostsConnection(x)": {
                          "edges": [{ "node": { "__ref": "Post:p1" } }]
                        }
                      },
                      "Post:p1": {
                        "title": "발행일 없는 글",
                        "mediumUrl": "https://medium.com/no-date"
                      }
                    }
                    """;
            List<Article> articles = parseApolloState(json, netflixCorp);

            assertThat(articles).hasSize(1);
            // TimeUtil.nowInSeoul()은 Asia/Seoul(UTC+9) 기준 → LocalDateTime.now()(UTC)보다 최대 9시간 앞섬
            assertThat(articles.get(0).getPublishedAt())
                    .isNotNull()
                    .isBefore(LocalDateTime.now().plusHours(10));
        }

        @Test
        @DisplayName("collectionByDomainOrSlug 키도 publication 참조로 인식")
        void collectionByDomainOrSlugKey_recognized() {
            // JSON 키 안의 따옴표를 피하기 위해 파라미터를 단순하게 작성
            // 코드는 key.contains("collectionByDomainOrSlug")만 체크하므로 파라미터 형식은 무관
            String json = """
                    {
                      "ROOT_QUERY": {
                        "collectionByDomainOrSlug(yogiyo.co.kr)": {
                          "__ref": "Publication:yogiyo"
                        }
                      },
                      "Publication:yogiyo": {
                        "publicationPostsConnection(x)": {
                          "edges": [{ "node": { "__ref": "Post:p1" } }]
                        }
                      },
                      "Post:p1": {
                        "title": "배달의민족 기술 블로그",
                        "mediumUrl": "https://medium.com/deliverytechkorea/post1"
                      }
                    }
                    """;
            List<Article> articles = parseApolloState(json, netflixCorp);

            assertThat(articles).hasSize(1);
            assertThat(articles.get(0).getTitle()).isEqualTo("배달의민족 기술 블로그");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. 중복 제거 (parseArticlesFromApolloStateWithDedup)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("중복 제거 (collectedPostIds)")
    class Deduplication {

        private String singlePostJson(String postId, String title, String mediumUrl) {
            return String.format("""
                    {
                      "ROOT_QUERY": {
                        "publicationByRef(x)": { "__ref": "Publication:p1" }
                      },
                      "Publication:p1": {
                        "publicationPostsConnection(x)": {
                          "edges": [{ "node": { "__ref": "Post:%s" } }]
                        }
                      },
                      "Post:%s": {
                        "title": "%s",
                        "mediumUrl": "%s"
                      }
                    }
                    """, postId, postId, title, mediumUrl);
        }

        @Test
        @DisplayName("collectedPostIds=null → 중복 체크 없이 모두 수집")
        void nullCollectedIds_noDedupCheck() {
            String json = singlePostJson("abc", "제목", "https://medium.com/post");

            List<Article> articles = parseApolloStateWithDedup(json, netflixCorp, null);

            assertThat(articles).hasSize(1);
        }

        @Test
        @DisplayName("이미 수집된 postId → skip")
        void alreadyCollected_skipped() {
            Set<String> seen = new HashSet<>();
            seen.add("abc");
            String json = singlePostJson("abc", "제목", "https://medium.com/post");

            List<Article> articles = parseApolloStateWithDedup(json, netflixCorp, seen);

            assertThat(articles).isEmpty();
        }

        @Test
        @DisplayName("새 postId → 수집 후 collectedPostIds에 추가")
        void newPostId_collectedAndAdded() {
            Set<String> seen = new HashSet<>();
            String json = singlePostJson("newPost", "새 글", "https://medium.com/new");

            List<Article> articles = parseApolloStateWithDedup(json, netflixCorp, seen);

            assertThat(articles).hasSize(1);
            assertThat(seen).contains("newPost");
        }

        @Test
        @DisplayName("같은 JSON 두 번 호출 → 두 번째 호출은 빈 리스트")
        void calledTwice_secondCallEmpty() {
            Set<String> seen = new HashSet<>();
            String json = singlePostJson("postX", "중복 글", "https://medium.com/dup");

            List<Article> first = parseApolloStateWithDedup(json, netflixCorp, seen);
            List<Article> second = parseApolloStateWithDedup(json, netflixCorp, seen);

            assertThat(first).hasSize(1);
            assertThat(second).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 6. processImageUpload
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processImageUpload")
    class ProcessImageUpload {

        @Test
        @DisplayName("http URL → S3 업로드 후 thumbnailImage 교체")
        void httpUrl_uploadAndReplace() {
            Article article = Article.builder()
                    .thumbnailImage("https://miro.medium.com/original.jpg")
                    .build();
            when(s3ImageService.uploadImageFromUrl(any(), any()))
                    .thenReturn("https://s3.example.com/uploaded.jpg");

            crawler.processImageUpload(article, netflixCorp);

            assertThat(article.getThumbnailImage()).isEqualTo("https://s3.example.com/uploaded.jpg");
        }

        @Test
        @DisplayName("null thumbnailImage → S3 업로드 미호출")
        void nullUrl_noUpload() {
            Article article = Article.builder().thumbnailImage(null).build();

            crawler.processImageUpload(article, netflixCorp);

            verifyNoInteractions(s3ImageService);
        }

        @Test
        @DisplayName("빈 thumbnailImage → S3 업로드 미호출")
        void emptyUrl_noUpload() {
            Article article = Article.builder().thumbnailImage("").build();

            crawler.processImageUpload(article, netflixCorp);

            verifyNoInteractions(s3ImageService);
        }

        @Test
        @DisplayName("http로 시작하지 않는 URL → S3 업로드 미호출")
        void nonHttpUrl_noUpload() {
            Article article = Article.builder().thumbnailImage("data:image/png;base64,abc").build();

            crawler.processImageUpload(article, netflixCorp);

            verifyNoInteractions(s3ImageService);
        }

        @Test
        @DisplayName("S3 업로드 실패 → 예외 전파 안 되고 원본 URL 유지")
        void s3Failure_keepOriginalUrl() {
            Article article = Article.builder()
                    .thumbnailImage("https://miro.medium.com/original.jpg")
                    .build();
            when(s3ImageService.uploadImageFromUrl(any(), any()))
                    .thenThrow(new RuntimeException("S3 connection failed"));

            assertThatCode(() -> crawler.processImageUpload(article, netflixCorp))
                    .doesNotThrowAnyException();
            assertThat(article.getThumbnailImage()).isEqualTo("https://miro.medium.com/original.jpg");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 7. buildFeedUrlFromBlogLink
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildFeedUrlFromBlogLink")
    class BuildFeedUrl {

        @Test
        @DisplayName("medium.com 단순 경로 → /feed/{slug}")
        void mediumSimplePath() {
            assertThat(buildFeedUrl("https://medium.com/watcha"))
                    .isEqualTo("https://medium.com/feed/watcha");
        }

        @Test
        @DisplayName("medium.com /all 접미사 → /feed/{slug} (all 제거)")
        void mediumWithAllSuffix() {
            assertThat(buildFeedUrl("https://medium.com/ssgtech/all"))
                    .isEqualTo("https://medium.com/feed/ssgtech");
        }

        @Test
        @DisplayName("medium.com 후행 슬래시 → /feed/{slug}")
        void mediumTrailingSlash() {
            assertThat(buildFeedUrl("https://medium.com/ssgtech/"))
                    .isEqualTo("https://medium.com/feed/ssgtech");
        }

        @Test
        @DisplayName("커스텀 도메인 → {host}/feed")
        void customDomain() {
            assertThat(buildFeedUrl("https://techblog.gccompany.co.kr"))
                    .isEqualTo("https://techblog.gccompany.co.kr/feed");
        }

        @Test
        @DisplayName("netflixtechblog.com → {host}/feed")
        void netflixDomain() {
            assertThat(buildFeedUrl("https://netflixtechblog.com"))
                    .isEqualTo("https://netflixtechblog.com/feed");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 8. RSS 통합 테스트 (실제 네트워크, 수동 실행용)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RSS 통합 테스트 (실제 네트워크)")
    class RssIntegration {

        private static final String SSG_BLOG_LINK = "https://medium.com/ssgtech/all";
        private static final String TARGET_ARTICLE_URL =
                "https://medium.com/ssgtech/%EC%99%9C-%EB%85%B8%EC%B6%9C%EC%9D%B4-%EC%95%88-%EB%90%A0%EA%B9%8C-%EB%A5%BC-%ED%95%9C-%EB%B2%88%EC%97%90-%EC%B6%94%EC%A0%81%ED%95%98%EB%8A%94-%EB%B0%A9%EB%B2%95-cd5c7a488b75";

        @Test
        @Tag("manual")
        @DisplayName("ssgtech/all → RSS feed URL 생성 후 본문 수집 성공")
        void ssgRssFetch_articleContentFound() {
            // given: /all 접미사가 있어도 feed URL을 올바르게 생성해야 함
            String feedUrl = buildFeedUrl(SSG_BLOG_LINK);
            assertThat(feedUrl).isEqualTo("https://medium.com/feed/ssgtech");

            // when: RSS feed에서 본문 맵 수집
            Map<String, String> contentMap = fetchRssContentMap(SSG_BLOG_LINK);

            // then: 맵이 비어있지 않아야 함 (RSS 접근 성공)
            assertThat(contentMap)
                    .as("RSS feed에서 아티클을 하나 이상 수집해야 함")
                    .isNotEmpty();

            // and: 대상 아티클 본문이 존재해야 함
            String content = findContentByUrl(contentMap, TARGET_ARTICLE_URL);
            assertThat(content)
                    .as("대상 아티클 본문이 RSS에 포함되어야 함: " + TARGET_ARTICLE_URL)
                    .isNotBlank()
                    .hasSizeGreaterThan(100);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 9. isCloudflareChallengePage
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isCloudflareChallengePage")
    class CloudflareDetection {

        @Test
        @DisplayName("null → false")
        void nullContent_false() {
            assertThat(isCloudflare(null)).isFalse();
        }

        @Test
        @DisplayName("정상 HTML → false")
        void normalHtml_false() {
            assertThat(isCloudflare("<html><body><p>정상 콘텐츠입니다.</p></body></html>")).isFalse();
        }

        @Test
        @DisplayName("'사람인지 확인' 포함 → true")
        void koreanHumanCheck_true() {
            assertThat(isCloudflare("사람인지 확인 중입니다")).isTrue();
        }

        @Test
        @DisplayName("'Checking your browser' 포함 → true")
        void checkingBrowser_true() {
            assertThat(isCloudflare("Checking your browser before accessing the site")).isTrue();
        }

        @Test
        @DisplayName("'Just a moment' 포함 → true")
        void justAMoment_true() {
            assertThat(isCloudflare("Just a moment... Cloudflare")).isTrue();
        }

        @Test
        @DisplayName("'Verify you are human' 포함 → true")
        void verifyHuman_true() {
            assertThat(isCloudflare("Verify you are human by completing the action below")).isTrue();
        }

        @Test
        @DisplayName("'cf-browser-verification' 포함 → true")
        void cfBrowserVerification_true() {
            assertThat(isCloudflare("<div id='cf-browser-verification'>")).isTrue();
        }

        @Test
        @DisplayName("'cf_chl_opt' 포함 → true")
        void cfChlOpt_true() {
            assertThat(isCloudflare("var cf_chl_opt = {};")).isTrue();
        }
    }
}
