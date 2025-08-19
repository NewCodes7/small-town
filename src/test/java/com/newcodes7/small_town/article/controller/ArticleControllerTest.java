package com.newcodes7.small_town.article.controller;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
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

    private Corporation corporation1;
    private Corporation corporation2;
    private Article article1;
    private Article article2;
    private Article article3;
    private Article article4;
    private List<Article> articles;

    @BeforeEach
    public void setUp() {
        corporationRepository.deleteAll();
        articleRepository.deleteAll();
        ArticleCreator.resetArticleIdCounter();

        corporation1 = ArticleCreator.createCorporation(1L, true);
        corporation2 = ArticleCreator.createCorporation(2L, false);
        corporationRepository.save(corporation1);
        corporationRepository.save(corporation2);

        article1 = ArticleCreator.createArticle(corporation1);
        article2 = ArticleCreator.createArticle(corporation1);
        article3 = ArticleCreator.createArticle(corporation2);
        article4 = ArticleCreator.createArticle(corporation2);
        articles = List.of(article1, article2, article3, article4);
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
        assertThat(contents).hasSize(4);

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
        assertThat(contents).hasSize(2);

        GroupedArticlesDto groupedDto1 = contents.get(0);
        GroupedArticlesDto groupedDto2 = contents.get(1);
        assertThat(groupedDto1.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto2.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto1.getArticles()).hasSize(2);
        assertThat(groupedDto2.getArticles()).hasSize(2);   
        assertThat(groupedDto1.getArticles().get(0).getId()).isEqualTo(article1.getId());
        assertThat(groupedDto1.getArticles().get(1).getId()).isEqualTo(article2.getId());
        assertThat(groupedDto2.getArticles().get(0).getId()).isEqualTo(article3.getId());
        assertThat(groupedDto2.getArticles().get(1).getId()).isEqualTo(article4.getId());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("keyword", "1"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();

        assertThat(contents).hasSize(1);
        GroupedArticlesDto groupedDto = contents.get(0);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(article1.getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(article1.getTitle());
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
        
        assertThat(contents).hasSize(1);
        GroupedArticlesDto groupedDto = contents.get(0);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(2);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(article1.getId());
        assertThat(groupedDto.getArticles().get(1).getId()).isEqualTo(article2.getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(article1.getTitle());
        assertThat(groupedDto.getArticles().get(1).getTitle()).isEqualTo(article2.getTitle());
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
        assertThat(contents).hasSize(1);
        GroupedArticlesDto groupedDto = contents.get(0);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto.getArticles()).hasSize(2);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(article3.getId());
        assertThat(groupedDto.getArticles().get(1).getId()).isEqualTo(article4.getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(article3.getTitle());
        assertThat(groupedDto.getArticles().get(1).getTitle()).isEqualTo(article4.getTitle());
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
        assertThat(contents).hasSize(2);
        GroupedArticlesDto groupedDto = contents.get(0);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(2);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(article1.getId());
        assertThat(groupedDto.getArticles().get(1).getId()).isEqualTo(article2.getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(article1.getTitle());
        assertThat(groupedDto.getArticles().get(1).getTitle()).isEqualTo(article2.getTitle());
        groupedDto = contents.get(1);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation2.getId());
        assertThat(groupedDto.getArticles()).hasSize(2);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(article3.getId());
        assertThat(groupedDto.getArticles().get(1).getId()).isEqualTo(article4.getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(article3.getTitle());
        assertThat(groupedDto.getArticles().get(1).getTitle()).isEqualTo(article4.getTitle());
    }

    @Test
    public void 홈페이지_게시글_조회_그룹_국내_검색() throws Exception {
        //when&then
        MvcResult result = mockMvc.perform(get("/").param("regions", "domestic").param("keyword", "1"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();   
        
        Page<GroupedArticlesDto> articlesPage = (Page<GroupedArticlesDto>) result.getModelAndView().getModel().get("articles");
        List<GroupedArticlesDto> contents = articlesPage.getContent();
        
        assertThat(contents).hasSize(1);
        GroupedArticlesDto groupedDto = contents.get(0);
        assertThat(groupedDto.getCorporation().getId()).isEqualTo(corporation1.getId());
        assertThat(groupedDto.getArticles()).hasSize(1);
        assertThat(groupedDto.getArticles().get(0).getId()).isEqualTo(article1.getId());
        assertThat(groupedDto.getArticles().get(0).getTitle()).isEqualTo(article1.getTitle());
    }
}