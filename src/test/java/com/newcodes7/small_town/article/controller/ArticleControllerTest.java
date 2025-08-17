package com.newcodes7.small_town.article.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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

    @Test
    public void 홈페이지_게시글_조회_리스트() throws Exception {
        //given
        Corporation corporation1 = ArticleCreator.createCorporation(1L);
        Corporation corporation2 = ArticleCreator.createCorporation(2L);
        corporationRepository.save(corporation1);
        corporationRepository.save(corporation2);

        Article article1 = ArticleCreator.createArticle(1L, corporation1);
        Article article2 = ArticleCreator.createArticle(1L, corporation1);
        Article article3 = ArticleCreator.createArticle(1L, corporation2);
        Article article4 = ArticleCreator.createArticle(1L, corporation2);
        List<Article> articles = List.of(article1, article2, article3, article4);
        articleRepository.saveAll(articles);

        //when&then
        mockMvc.perform(get("/").param("view", "list"))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andExpect(model().attribute("articles", hasProperty("totalElements", equalTo(4L)))) 
            .andExpect(model().attribute("articles", hasProperty("content", hasSize(4)))) 
            .andExpect(model().attribute("articles", hasProperty("number", equalTo(0)))) 
            .andExpect(model().attribute("currentPage", 0));
    }
}