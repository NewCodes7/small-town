package com.newcodes7.small_town.crawler.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.auth.dto.JwtResponseDto;
import com.newcodes7.small_town.auth.dto.LoginRequestDto;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.service.AuthService;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.crawler.dto.OpenAiResponse;
import com.newcodes7.small_town.crawler.dto.YouTubeVideo;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.utils.ArticleCreator;
import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class CrawlingControllerTest extends IntegrationTestBase {

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationService corporationService;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private ParsingSelectorRepository parsingSelectorRepository;

    @Autowired
    private CrawlerVideoRepository crawlerVideoRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    private String accessToken;

    private String refreshToken;

    @BeforeEach
    @Transactional
    public void setUp() {
        articleRepository.deleteAll();
        corporationRepository.deleteAll();
        parsingSelectorRepository.deleteAll();
        ArticleCreator.resetArticleIdCounter();

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ADMIN").build()));

        User adminUser = userRepository.findByEmail("admin@smalltown.com")
            .orElseGet(() -> {
                User newUser = User.builder()
                        .email("admin2@smalltown.com")
                        .password("admin123!")
                        .nickname("Admin")
                        .role(adminRole)
                        .status(User.UserStatus.ACTIVE)
                        .build();
                return userRepository.save(newUser);
            });

        JwtResponseDto jwtResponseDto = authService.login(
                LoginRequestDto.builder()
                .email(adminUser.getEmail())
                .password("admin123!")
                .build());

        accessToken = jwtResponseDto.getAccessToken();
        refreshToken = jwtResponseDto.getRefreshToken();

        // WebDriver mock 설정: 실 Chrome 대신 toss_blog.html을 페이지 소스로 반환
        try {
            WebDriver mockWebDriver = Mockito.mock(WebDriver.class,
                    withSettings().extraInterfaces(JavascriptExecutor.class));
            when(((JavascriptExecutor) mockWebDriver).executeScript(anyString())).thenReturn(100L);

            ClassPathResource htmlResource = new ClassPathResource("toss_blog.html");
            String htmlContent;
            try (InputStream inputStream = htmlResource.getInputStream()) {
                htmlContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            when(mockWebDriver.getPageSource()).thenReturn(htmlContent);
            when(webDriverConfig.createWebDriver()).thenReturn(mockWebDriver);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // RestTemplate mock 설정 (OpenAI API)
        try {
            ClassPathResource resource = new ClassPathResource("openai_article_analysis.json");
            String mockResponseJson;
            try (InputStream inputStream = resource.getInputStream()) {
                mockResponseJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            // json parsing해서 output에 해당하는 거 List<Output>로 변환
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(mockResponseJson);
            JsonNode outputNode = rootNode.path("output");
            List<OpenAiResponse.Output> outputList = objectMapper.readValue(
                outputNode.toString(),
                new TypeReference<List<OpenAiResponse.Output>>() {}
            );

            OpenAiResponse mockOpenAiResponse = OpenAiResponse.builder()
                .output(outputList)
                .build();

            ResponseEntity<OpenAiResponse> mockResponseEntity =
                new ResponseEntity<>(mockOpenAiResponse, HttpStatus.OK);

            // RestTemplate.exchange() 메서드 mock 설정
            when(openaiRestTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(OpenAiResponse.class)
            )).thenReturn(mockResponseEntity);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // YouTubeService mock 설정
        try {
            ClassPathResource youtubeResource = new ClassPathResource("youtube_videos_mock.json");
            String youtubeJsonContent;
            try (InputStream inputStream = youtubeResource.getInputStream()) {
                youtubeJsonContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }

            ObjectMapper objectMapper = new ObjectMapper(); // Jackson 3는 java.time 지원 내장
            JsonNode rootNode = objectMapper.readTree(youtubeJsonContent);
            JsonNode videosNode = rootNode.path("videos");

            List<YouTubeVideo> mockYoutubeVideos = objectMapper.readValue(
                videosNode.toString(),
                new TypeReference<List<YouTubeVideo>>() {}
            );

            // YouTubeService mock 설정
            when(youtubeService.getLatestVideos(anyString())).thenReturn(mockYoutubeVideos);
            when(youtubeService.getLatestVideos(anyString(), any(Integer.class))).thenReturn(mockYoutubeVideos);
            // YouTube Channel ID 추출 mock (모든 YouTube URL에 대해)
            when(youtubeService.getChannelIdFromUrl(anyString()))
                .thenReturn("UCeg5g-vWgtgzQ0cYNV2Cyow");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void 토스_블로그_크롤링_단일() throws Exception {
        //given
        Corporation corporation = createTossCorporation("토스", "https://toss.tech", 1);

        //when&then
        mockMvc.perform(get("/api/crawling/blogs/{corporationId}", corporation.getId())
                .cookie(new Cookie("accessToken", accessToken))
                .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.corporationName", is(corporation.getName())))
                .andExpect(jsonPath("$.newArticles", is(20)))
                .andExpect(jsonPath("$.totalArticles", is(20)))
                .andReturn();
        articleRepository.findByCorporationId(corporation.getId(), PageRequest.of(0, 10))
            .getContent()
            .forEach(article -> {
                assertThat(article.getTitle()).isNotEmpty();
                assertThat(article.getLink()).isNotEmpty();
                assertThat(article.getThumbnailImage()).isNotEmpty();
                assertThat(article.getPublishedAt()).isNotNull();
                assertThat(article.getCategory()).isNotNull();
            });
    }

    @Test
    public void 토스_유튜브_크롤링_단일() throws Exception {
        //given
        Corporation corporation = createTossCorporation("토스", "https://toss.tech", 1);

        //when&then
        mockMvc.perform(get("/api/crawling/youtube/{corporationId}", corporation.getId())
                .cookie(new Cookie("accessToken", accessToken))
                .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.corporationName", is(corporation.getName())))
                .andExpect(jsonPath("$.newVideos", is(47)))
                .andExpect(jsonPath("$.totalVideos", is(47)))
                .andReturn();


        List<Video> savedVideos = crawlerVideoRepository.findByCorporationId(corporation.getId());
        savedVideos.stream().limit(10).forEach(video -> {
            assertThat(video.getTitle()).isNotEmpty();
            assertThat(video.getLink()).isNotEmpty();
            assertThat(video.getLink()).startsWith("https://www.youtube.com/watch?v=");
            assertThat(video.getThumbnailUrl()).isNotEmpty();
            assertThat(video.getPublishedAt()).isNotNull();
            assertThat(video.getVideoId()).isNotEmpty();
            assertThat(video.getCorporation()).isNotNull();
            assertThat(video.getCorporation().getId()).isEqualTo(corporation.getId());
        });
    }

    @Test
    public void OpenAiAPI_아티클_분석_및_저장() throws Exception {
        //given
        Corporation corporation = createTossCorporation("토스", "https://toss.tech", 1);

        //when
        mockMvc.perform(get("/api/crawling/blogs/{corporationId}", corporation.getId())
                .cookie(new Cookie("accessToken", accessToken))
                .accept(MediaType.APPLICATION_JSON));

        //then
        Pageable pageable = PageRequest.of(0, 10);
        Page<Article> savedArticles = articleRepository.findByCorporationId(corporation.getId(), pageable);
        assertThat(savedArticles.getTotalElements()).isEqualTo(20L);
        for (Article article : savedArticles) {
            assertThat(article.getTitle()).isNotEmpty();
            assertThat(article.getLink()).isNotEmpty();
            assertThat(article.getCategory().getName()).isNotEmpty();
        }
    }

    private Corporation createTossCorporation(String name, String blogLink, int isDomestic) {
        CorporationCreateDto dto = CorporationCreateDto.builder()
                .name(name)
                .isDomestic(isDomestic)
                .homeLink("https://newcodes.net/" + name)
                .blogLink(blogLink)
                .crewLink("https://newcodes.net/" + name + "/crew")
                .logoUrl("https://newcodes.net/favicon.ico")
                .youtubeUrl("https://www.youtube.com/@toss_official")
                .baseUrl(blogLink)
                .article("a[class*='css-1qr3mg1'], a[class*='e1sck7qg4']")
                .title("span[class*='typography--h6']")
                .link("a[href]")
                .thumbnail("img[alt*='thumbnail']")
                .publish("span[class*='typography--small']")
                .publishFormat("yyyy.M.dd")
                .build();

        Long corporationId = corporationService.createCorporation(dto).getId();

        Corporation savedCorp = corporationRepository.findById(corporationId).orElseThrow();
        savedCorp.setYoutubeChannelId("UCeg5g-vWgtgzQ0cYNV2Cyow");
        corporationRepository.save(savedCorp);

        return corporationRepository.findById(corporationId).orElseThrow();
    }
}
