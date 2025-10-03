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
import java.util.List;

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
import com.newcodes7.small_town.article.repository.CorporationRepository;
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

    private static final int ARTICLE_TOTAL_COUNT = 24;
    private static final int GROUP_ARTILCE_COUNT = 3;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private Corporation corporation1;
    private Corporation corporation2;
    private List<Article> articles;

    @BeforeEach
    public void setUp() {
        corporationRepository.deleteAll();
        articleRepository.deleteAll();
        ArticleCreator.resetArticleIdCounter();

        corporation1 = ArticleCreator.createCorporation(1L, 1);
        corporation2 = ArticleCreator.createCorporation(2L, 0);
        corporationRepository.save(corporation1);
        corporationRepository.save(corporation2);

        articles = new ArrayList<>();
        for (int i = 0; i < ARTICLE_TOTAL_COUNT; i++) {
            Corporation corp = (i % 2 == 0) ? corporation1 : corporation2;
            articles.add(ArticleCreator.createArticle(corp));
        }
        articleRepository.saveAll(articles);
    }

    @Test
    public void 홈페이지_게시글_조회_리스트() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("view", "list"))
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
    public void 홈페이지_게시글_조회_그룹() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto1 = contents.get(0);
        GroupedArticlesDto groupedDto2 = contents.get(1);   

        assertThat(contents).hasSize(2);
        assertThat(groupedDto1.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto2.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto1.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
        assertThat(groupedDto2.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
        for (int i = 0; i < groupedDto1.getArticles().size(); i++) {
            ArticleListResponseDto dto = groupedDto1.getArticles().get(i);
            assertThat(dto.getId()).isEqualTo(articles.get(i * 2).getId());
            assertThat(dto.getTitle()).isEqualTo(articles.get(i * 2).getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(articles.get(i * 2).getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("keyword", "10"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(10).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(10).getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_검색2() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("keyword", "11"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(11).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(11).getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("regions", "domestic"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);
        
        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
        for (int i = 0; i < groupedDto.getArticles().size(); i++) {
            ArticleListResponseDto dto = groupedDto.getArticles().get(i);
            assertThat(dto.getId()).isEqualTo(articles.get(i * 2).getId());
            assertThat(dto.getTitle()).isEqualTo(articles.get(i * 2).getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(articles.get(i * 2).getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_해외() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("regions", "overseas"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();   
        
        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
        for (int i = 0; i < groupedDto.getArticles().size(); i++) {
            ArticleListResponseDto dto = groupedDto.getArticles().get(i);
            assertThat(dto.getId()).isEqualTo(articles.get(i * 2 + 1).getId());
            assertThat(dto.getTitle()).isEqualTo(articles.get(i * 2 + 1).getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(articles.get(i * 2 + 1).getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내_해외() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("regions", "overseas").param("regions", "domestic"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();   
        
        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);

        assertThat(contents).hasSize(2);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
        for (int i = 0; i < groupedDto.getArticles().size(); i++) {
            ArticleListResponseDto dto = groupedDto.getArticles().get(i);
            assertThat(dto.getId()).isEqualTo(articles.get(i * 2).getId());
            assertThat(dto.getTitle()).isEqualTo(articles.get(i * 2).getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(articles.get(i * 2).getCorporation().getId());
        }
        groupedDto = contents.get(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto.getArticles()).hasSize(GROUP_ARTILCE_COUNT);
        for (int i = 0; i < groupedDto.getArticles().size(); i++) {
            ArticleListResponseDto dto = groupedDto.getArticles().get(i);
            assertThat(dto.getId()).isEqualTo(articles.get(i * 2 + 1).getId());
            assertThat(dto.getTitle()).isEqualTo(articles.get(i * 2 + 1).getTitle());
            assertThat(dto.getCorporation().getId()).isEqualTo(articles.get(i * 2 + 1).getCorporation().getId());
        }
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("regions", "domestic").param("keyword", "10"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();   
        
        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);
        
        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(10).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(10).getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_카테고리() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("category", "backend0"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();   
        
        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        GroupedArticlesDto groupedDto = contents.get(0);
        
        assertThat(contents).hasSize(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(articles.get(0).getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(articles.get(0).getTitle());
    }
}