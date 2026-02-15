package com.newcodes7.small_town.article.controller;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.utils.ArticleCreator;

@TestPropertySource("classpath:application-test.properties")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class ArticleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    private static final int CORPORATION_TOTAL_COUNT = 40;
    private static final int ARTICLE_TOTAL_COUNT = 160;
    private static final int GROUP_ARTILCE_COUNT = 3;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private Map<Corporation, List<Article>> corporationArticles;
    private List<Corporation> corporations;
    private List<Article> articles;

    @BeforeEach
    public void setUp() {
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
    }

    @Test
    public void 홈페이지_게시글_조회_리스트() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "list"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<ArticleListResponseDto> articlesPage = (Page<ArticleListResponseDto>) result.getModelAndView().getModel().get("articles");
        List<ArticleListResponseDto> contents = articlesPage.getContent();
        assertThat(contents).hasSize(DEFAULT_PAGE_SIZE);

        for (int i = 0; i < contents.size(); i++) {
            ArticleListResponseDto dto = contents.get(i);
            assertThat(dto.getId()).isEqualTo(articles.get(i).getId());
            assertThat(dto.getTitle()).isEqualTo(articles.get(i).getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(articles.get(i).getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_리스트_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "list").param("keyword", "100"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<ArticleListResponseDto> articlesPage = (Page<ArticleListResponseDto>) result.getModelAndView().getModel().get("articles");
        List<ArticleListResponseDto> contents = articlesPage.getContent();

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).getId()).isEqualTo(articles.get(100).getId());
        assertThat(contents.get(0).getTitle()).isEqualTo(articles.get(100).getTitle());
        assertThat(contents.get(0).getCorporation().getId()).isEqualTo(corporations.get(20).getId());
    }

    @Test
    public void 홈페이지_게시글_조회_리스트_국내() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "list").param("regions", "domestic"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<ArticleListResponseDto> articlesPage = (Page<ArticleListResponseDto>) result.getModelAndView().getModel().get("articles");
        List<ArticleListResponseDto> contents = articlesPage.getContent();
        assertThat(contents).hasSize(DEFAULT_PAGE_SIZE);

        for (int i = 0; i < contents.size(); i++) {
            ArticleListResponseDto dto = contents.get(i);
            Article article = articles.get(i * 2 + 1);
            assertThat(dto.getId()).isEqualTo(article.getId());
            assertThat(dto.getTitle()).isEqualTo(article.getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(article.getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_리스트_해외() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "list").param("regions", "overseas"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<ArticleListResponseDto> articlesPage = (Page<ArticleListResponseDto>) result.getModelAndView().getModel().get("articles");
        List<ArticleListResponseDto> contents = articlesPage.getContent();
        assertThat(contents).hasSize(DEFAULT_PAGE_SIZE);

        for (int i = 0; i < contents.size(); i++) {
            ArticleListResponseDto dto = contents.get(i);
            Article article = articles.get(i * 2);
            assertThat(dto.getId()).isEqualTo(article.getId());
            assertThat(dto.getTitle()).isEqualTo(article.getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(article.getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_리스트_카테고리() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "list").param("category", "backend0"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<ArticleListResponseDto> articlesPage = (Page<ArticleListResponseDto>) result.getModelAndView().getModel().get("articles");
        List<ArticleListResponseDto> contents = articlesPage.getContent();
        assertThat(contents).hasSize(1);

        ArticleListResponseDto dto = contents.get(0);
        assertThat(dto.getId()).isEqualTo(articles.get(0).getId());
        assertThat(dto.getTitle()).isEqualTo(articles.get(0).getTitle());
        assertThat(dto.getCorporation().getId()).isEqualTo(corporations.get(0).getId());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();

        assertThat(contents).hasSize(DEFAULT_PAGE_SIZE);
        for (int i = 0; i < contents.size(); i++) {
            GroupedArticlesDto groupedDto = contents.get(i);
            Corporation corporation = corporations.get(i);
            assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation.getId());
            assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
            for (int j = 0; j < groupedDto.getArticles().size(); j++) {
                ArticleListResponseDto dto = groupedDto.getArticles().get(j);
                Article article = corporationArticles.get(corporation).get(j);
                assertThat(dto.getId()).isEqualTo(article.getId());
                assertThat(dto.getTitle()).isEqualTo(article.getTitle());
                assertThat(dto.getCorporation().getId()).isEqualTo(article.getCorporation().getId());
            }
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "grouped").param("keyword", "100"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporations.get(20).getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(100).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(100).getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_검색2() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "grouped").param("keyword", "110"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporations.get(30).getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(110).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(110).getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("regions", "domestic"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);
        
        assertThat(contents).hasSize(CORPORATION_TOTAL_COUNT / 2 > DEFAULT_PAGE_SIZE ? DEFAULT_PAGE_SIZE : CORPORATION_TOTAL_COUNT / 2);
        for (int i = 0; i < contents.size(); i++) {
            groupedDto = contents.get(i);
            Corporation corporation = corporations.get(i * 2 + 1);
            assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation.getId());
            assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
            for (int j = 0; j < groupedDto.getArticles().size(); j++) {
                ArticleListResponseDto dto = groupedDto.getArticles().get(j);
                Article article = corporationArticles.get(corporation).get(j);
                assertThat(dto.getId()).isEqualTo(article.getId());
                assertThat(dto.getTitle()).isEqualTo(article.getTitle());
                assertThat(dto.getCorporation().getId()).isEqualTo(article.getCorporation().getId());
            }
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_해외() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("regions", "overseas"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(CORPORATION_TOTAL_COUNT / 2 > DEFAULT_PAGE_SIZE ? DEFAULT_PAGE_SIZE : CORPORATION_TOTAL_COUNT / 2);
        for (int i = 0; i < contents.size(); i++) {
            groupedDto = contents.get(i);
            Corporation corporation = corporations.get(i * 2);
            assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation.getId());
            assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
            for (int j = 0; j < groupedDto.getArticles().size(); j++) {
                ArticleListResponseDto dto = groupedDto.getArticles().get(j);
                Article article = corporationArticles.get(corporation).get(j);
                assertThat(dto.getId()).isEqualTo(article.getId());
                assertThat(dto.getTitle()).isEqualTo(article.getTitle());
                assertThat(dto.getCorporation().getId()).isEqualTo(article.getCorporation().getId());
            }
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내_해외() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("regions", "overseas").param("regions", "domestic"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();

        assertThat(contents).hasSize(DEFAULT_PAGE_SIZE);
        for (int i = 0; i < contents.size(); i++) {
            GroupedArticlesDto groupedDto = contents.get(i);
            Corporation corporation = corporations.get(i);
            assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation.getId());
            assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
            for (int j = 0; j < groupedDto.getArticles().size(); j++) {
                ArticleListResponseDto dto = groupedDto.getArticles().get(j);
                Article article = corporationArticles.get(corporation).get(j);
                assertThat(dto.getId()).isEqualTo(article.getId());
                assertThat(dto.getTitle()).isEqualTo(article.getTitle());
                assertThat(dto.getCorporation().getId()).isEqualTo(article.getCorporation().getId());
            }
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("view", "grouped").param("regions", "domestic").param("keyword", "101"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporations.get(21).getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(101).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(101).getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_카테고리() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/articles").param("category", "backend0"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporations.get(0).getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(0).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(0).getTitle());
    }
}