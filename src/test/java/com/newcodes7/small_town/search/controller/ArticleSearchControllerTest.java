package com.newcodes7.small_town.search.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.TermSource;
import com.newcodes7.small_town.search.service.SearchConcurrencyLimiter;
import com.newcodes7.small_town.term.repository.ArticleTermRepository;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.utils.ArticleCreator;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class ArticleSearchControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private SearchConcurrencyLimiter searchConcurrencyLimiter;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private TermRepository termRepository;

    @Autowired
    private ArticleTermRepository articleTermRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    private static final int CORPORATION_TOTAL_COUNT = 40;
    private static final int ARTICLE_TOTAL_COUNT = 160;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private Map<Corporation, List<Article>> corporationArticles;
    private List<Corporation> corporations;
    private List<Article> articles;

    @BeforeEach
    public void setUp() {
        articleTermRepository.deleteAll();
        termRepository.deleteAll();
        corporationRepository.deleteAll();
        articleRepository.deleteAll();
        ArticleCreator.resetArticleIdCounter();

        corporations = new ArrayList<>();
        corporationArticles = new LinkedHashMap<>();
        for (int i = 0; i < CORPORATION_TOTAL_COUNT; i++) {
            Corporation corp = ArticleCreator.createCorporation(i, i % 2);
            corporationRepository.save(corp);
            corporations.add(corp);
            corporationArticles.put(corp, new ArrayList<>());
        }

        articles = new ArrayList<>();
        for (int i = 0; i < ARTICLE_TOTAL_COUNT; i++) {
            Corporation corp = corporations.get(i % CORPORATION_TOTAL_COUNT);
            Article article = ArticleCreator.createArticle(corp);
            corporationArticles.get(corp).add(article);
            articles.add(article);
        }
        articleRepository.saveAll(articles);

        // BM25 검색을 위한 Term/ArticleTerm 데이터 생성
        List<Term> terms = new ArrayList<>();
        for (int i = 0; i < ARTICLE_TOTAL_COUNT; i++) {
            Term term = Term.builder()
                .term(String.valueOf(i))
                .termType("SN")
                .build();
            terms.add(term);
        }
        termRepository.saveAll(terms);

        List<ArticleTerm> articleTerms = new ArrayList<>();
        for (int i = 0; i < ARTICLE_TOTAL_COUNT; i++) {
            ArticleTerm articleTerm = ArticleTerm.builder()
                .article(articles.get(i))
                .term(terms.get(i))
                .frequency(1)
                .score(1.0)
                .source(TermSource.TITLE)
                .build();
            articleTerms.add(articleTerm);
        }
        articleTermRepository.saveAll(articleTerms);

        entityManager.flush();
        entityManager.createNativeQuery("""
                INSERT INTO article_analyzed_content (id, title, published_at, corporation_id, category_id, title_terms, content_terms, updated_at)
                SELECT a.id, a.title, a.published_at, a.corporation_id, a.category_id,
                       STRING_AGG(CASE WHEN at.source IN ('TITLE','BOTH') THEN t.term END, ' ' ORDER BY at.score DESC),
                       STRING_AGG(CASE WHEN at.source IN ('CONTENT','BOTH') THEN t.term END, ' ' ORDER BY at.score DESC),
                       NOW()
                FROM article a
                LEFT JOIN article_term at ON a.id = at.article_id
                LEFT JOIN term t ON at.term_id = t.id
                WHERE a.deleted_at IS NULL
                GROUP BY a.id, a.title, a.published_at, a.corporation_id, a.category_id
                ON CONFLICT (id) DO UPDATE SET
                    title_terms = EXCLUDED.title_terms,
                    content_terms = EXCLUDED.content_terms,
                    updated_at = NOW()
                """).executeUpdate();
    }

    @Test
    public void 검색API_리스트_기본조회() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(DEFAULT_PAGE_SIZE))
            .andExpect(jsonPath("$.totalPages").isNumber())
            .andExpect(jsonPath("$.totalElements").isNumber())
            .andExpect(jsonPath("$.currentPage").value(0))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.hasPrevious").value(false))
            .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode content = root.get("content");
        Set<Long> responseIds = new HashSet<>();

        for (int i = 0; i < content.size(); i++) {
            JsonNode articleNode = content.get(i);
            long articleId = articleNode.get("id").asLong();
            assertThat(articleId).isPositive();
            assertThat(responseIds.add(articleId)).isTrue();
            assertThat(articleNode.get("title").asText()).isNotBlank();
        }
    }

    @Test
    public void 검색API_키워드_검색() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .param("keyword", "100")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.keyword").value("100"))
            .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode content = root.get("content");

        assertThat(content.get(0).get("id").asLong()).isEqualTo(articles.get(100).getId());
        assertThat(content.get(0).get("title").asText()).isEqualTo(articles.get(100).getTitle());
    }

    @Test
    public void 검색API_지역_필터_국내() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .param("regions", "domestic")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(DEFAULT_PAGE_SIZE))
            .andExpect(jsonPath("$.selectedRegions[0]").value("domestic"))
            .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode content = root.get("content");

        for (int i = 0; i < content.size(); i++) {
            JsonNode article = content.get(i);
            assertThat(article.has("corporation")).isTrue();
            assertThat(article.get("corporation").get("id").asLong()).isPositive();
        }
    }

    @Test
    public void 검색API_지역_필터_해외() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .param("regions", "overseas")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(DEFAULT_PAGE_SIZE))
            .andExpect(jsonPath("$.selectedRegions[0]").value("overseas"))
            .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode content = root.get("content");

        for (int i = 0; i < content.size(); i++) {
            JsonNode article = content.get(i);
            assertThat(article.has("corporation")).isTrue();
            assertThat(article.get("corporation").get("id").asLong()).isPositive();
        }
    }

    @Test
    public void 검색API_카테고리_필터() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .param("category", "backend0")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode firstArticle = root.get("content").get(0);
        assertThat(firstArticle.get("category").get("name").asText()).isEqualTo("backend0");
    }

    @Test
    public void 검색API_페이지네이션() throws Exception {
        mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .param("page", "1")
                .param("size", "10")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(DEFAULT_PAGE_SIZE))
            .andExpect(jsonPath("$.currentPage").value(1))
            .andExpect(jsonPath("$.hasPrevious").value(true));
    }

    @Test
    public void 검색API_그룹뷰() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/articles")
                .param("view", "grouped")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(DEFAULT_PAGE_SIZE))
            .andExpect(jsonPath("$.view").value("grouped"))
            .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        JsonNode content = root.get("content");

        // 그룹 뷰에서는 각 요소에 corporation과 articles가 있어야 함
        for (int i = 0; i < content.size(); i++) {
            JsonNode group = content.get(i);
            assertThat(group.has("corporation")).isTrue();
            assertThat(group.has("articles")).isTrue();
            assertThat(group.get("articles").isArray()).isTrue();
        }
    }

    @Test
    public void 검색API_JSON_응답_형식_검증() throws Exception {
        mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").exists())
            .andExpect(jsonPath("$.currentPage").exists())
            .andExpect(jsonPath("$.totalPages").exists())
            .andExpect(jsonPath("$.totalElements").exists())
            .andExpect(jsonPath("$.hasNext").exists())
            .andExpect(jsonPath("$.hasPrevious").exists())
            .andExpect(jsonPath("$.currentSort").exists())
            .andExpect(jsonPath("$.selectedRegions").exists())
            .andExpect(jsonPath("$.view").exists());
    }

    @Test
    public void 검색API_빈결과() throws Exception {
        mockMvc.perform(get("/api/search/articles")
                .param("view", "list")
                .param("keyword", "nonexistentkeyword12345")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * 동시 실행 상한을 넘긴 요청은 5xx가 아니라 429로 즉시 돌아와야 한다.
     * "결과 없음"(200 + 빈 배열)과 구분되는 응답이어야 프론트가 안내를 달리 띄울 수 있다
     * (articleManager.showBusyState).
     */
    @Test
    public void 동시_실행_상한을_넘기면_429와_RetryAfter를_돌려준다() throws Exception {
        int acquired = 0;
        try {
            // 상한만큼 permit을 미리 소진시킨다 (테스트 DB에는 설정 행이 없어 기본값 15가 적용된다)
            while (acquired < 100 && searchConcurrencyLimiter.tryAcquire()) {
                acquired++;
            }
            assertThat(acquired)
                    .as("permit을 하나도 잡지 못하면 이 테스트는 아무것도 검증하지 못한다")
                    .isGreaterThan(0);

            mockMvc.perform(get("/api/search/articles")
                            .param("keyword", "kafka")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.error").value("SEARCH_BUSY"))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(1));
        } finally {
            for (int i = 0; i < acquired; i++) {
                searchConcurrencyLimiter.release();
            }
        }
    }
}
