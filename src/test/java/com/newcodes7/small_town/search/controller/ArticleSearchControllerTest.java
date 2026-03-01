package com.newcodes7.small_town.search.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.TermSource;
import com.newcodes7.small_town.utils.ArticleCreator;

import jakarta.persistence.EntityManager;

@TestPropertySource("classpath:application-test.properties")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class ArticleSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleRepository articleRepository;

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
        entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW article_search_view").executeUpdate();
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
}
