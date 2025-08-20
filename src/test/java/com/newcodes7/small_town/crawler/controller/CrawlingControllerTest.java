package com.newcodes7.small_town.crawler.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.auth.dto.JwtResponseDto;
import com.newcodes7.small_town.auth.dto.LoginRequestDto;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.service.AuthService;
import com.newcodes7.small_town.config.TestWebDriverConfig;
import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.utils.ArticleCreator;

import jakarta.servlet.http.Cookie;

@TestPropertySource("classpath:application-test.properties")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestWebDriverConfig.class)
public class CrawlingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private ParsingSelectorRepository parsingSelectorRepository;

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
    }

    @Test
    public void 토스_블로그_크롤링_단일() throws Exception {
        //given 
        Corporation corporation = Corporation.builder()
                .name("토스")
                .homeLink("https://newcodes.net")
                .blogLink("/?view=list")
                .crewLink("https://newcodes.net")
                .logoUrl("https://newcodes.net/favicon.ico")
                .logoFilename("logo_1.png")
                .logoS3Url("https://s3.newcodes.net/logo/1.png")
                .isDomestic(true)
                .build();
        corporationRepository.save(corporation);

        ParsingSelector parsingSelector = ParsingSelector.builder()
                .corporationId(corporation.getId())
                .baseUrl("https://toss.tech/")
                .article("a[class*='css-1qr3mg1'], a[class*='e1sck7qg4']")
                .title("span[class*='typography--h6']")
                .link("a[href]")
                .thumbnail("img[alt*='thumbnail']")
                .publish("span[class*='typography--small']")
                .publishFormat("yyyy.M.dd") 
                .build();
        parsingSelectorRepository.save(parsingSelector);

        List<Article> articles = ArticleCreator.createArticles(corporation, 10);
        articleRepository.saveAll(articles);
        
        //when&then
        mockMvc.perform(get("/api/crawling/corporation/{corporationId}", corporation.getId())
                .cookie(new Cookie("accessToken", accessToken))        
                .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.corporationName", is(corporation.getName())))
                .andExpect(jsonPath("$.newArticles", greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.totalArticles", greaterThanOrEqualTo(20)))
                .andReturn();
    }
}