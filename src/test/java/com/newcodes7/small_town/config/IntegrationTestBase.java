package com.newcodes7.small_town.config;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.config.WebDriverConfig;
import com.newcodes7.small_town.crawler.integration.youtube.YouTubeService;
import com.newcodes7.small_town.crawler.service.ContentExtractor;
import com.newcodes7.small_town.search.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

/**
 * 모든 @SpringBootTest 통합테스트의 공통 base.
 *
 * mock/spy 구성을 통일해 ApplicationContext 캐시 키를 단일화한다 — 새 통합테스트는
 * 반드시 이 클래스를 상속하고, 개별 @MockitoBean/@Import 추가 금지 (컨텍스트가 분열되어
 * 스키마 재생성이 반복됨). 스텁은 각 테스트의 @BeforeEach에서 상속 필드에 수행하며,
 * Spring이 각 테스트 후 자동으로 reset한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource("classpath:application-test.properties")
@Transactional
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    // 외부 I/O 차단: 항상 mock (실제 호출이 필요한 테스트 없음)
    @MockitoBean(name = "openaiRestTemplate")
    protected RestTemplate openaiRestTemplate;

    @MockitoBean
    protected YouTubeService youtubeService;

    // 실 Chrome 기동 방지 — createWebDriver()를 스텁해 mock WebDriver 반환
    @MockitoBean
    protected WebDriverConfig webDriverConfig;

    // 실동작이 필요한 테스트가 있어 spy: 기본은 실빈 위임, 스텁은 doReturn/doThrow만 사용
    @MockitoSpyBean
    protected ContentExtractor contentExtractor;

    @MockitoSpyBean
    protected VectorSearchService vectorSearchService;

    // 캐시 동작 검증(verify) 용도 — 기본은 실빈 위임
    @MockitoSpyBean
    protected ArticleRepository articleRepository;
}
