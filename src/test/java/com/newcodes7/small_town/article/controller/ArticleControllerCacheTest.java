package com.newcodes7.small_town.article.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.crawler.service.ArticlePersistenceService;
import com.newcodes7.small_town.crawler.service.DefaultBlogCrawler;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.utils.ArticleCreator;


@TestPropertySource("classpath:application-test.properties")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class ArticleControllerCacheTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ArticleRepository articleRepository;

    @Autowired
    private ArticlePersistenceService articlePersistenceService;

    @Autowired
    private DefaultBlogCrawler defaultBlogCrawler;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private CacheManager cacheManager;

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
        clearInvocations(articleRepository);
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });

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
    void 기업별_그룹_조회시_캐시_성공() throws Exception {
        // when
        mockMvc.perform(get("/").param("view", "grouped"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();
        mockMvc.perform(get("/").param("view", "grouped"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        // then 
        verify(articleRepository, times(1))
            .findTop3ArticlesGroupedByCorporation(
                isNull(), anyList(), anyInt(), anyList(), 
                anyInt(), anyInt(), anyInt());
    }

    @Test
    void 기업별_그룹_조회시_키워드_있으면_캐시_안됨() throws Exception {
        // when - 키워드 검색은 캐시 조건 불충족
        mockMvc.perform(get("/").param("view", "grouped").param("keyword", "100"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();
        mockMvc.perform(get("/").param("view", "grouped").param("keyword", "100"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        // then - 매번 DB 호출됨을 검증
        verify(articleRepository, times(2))
            .findTop3ArticlesGroupedByCorporation(
                any(), anyList(), anyInt(), anyList(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void 리스트_조회시_캐시_성공() throws Exception {
        // when
        mockMvc.perform(get("/").param("view", "list"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();
        mockMvc.perform(get("/").param("view", "list"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        // then 
        verify(articleRepository, times(1))
            .findArticlesWithFilters(any(), any(), any(), any(), any());
    }

    @Test
    void 게시글_추가시_캐시_삭제() throws Exception {
        // given - 캐시 생성
        mockMvc.perform(get("/").param("view", "grouped"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();

        // when - 게시글 추가
        Corporation corp = corporations.get(0);
        Article newArticle = ArticleCreator.createArticle(corp);
        articlePersistenceService.saveArticleWithAnalysis(newArticle, corp, defaultBlogCrawler);

        // then - 캐시 삭제되어 다시 DB 조회
        mockMvc.perform(get("/").param("view", "grouped"))
            .andExpect(status().isOk())
            .andExpect(view().name("home"))
            .andExpect(model().attributeExists("articles"))
            .andReturn();
        verify(articleRepository, times(2))
            .findTop3ArticlesGroupedByCorporation(
                isNull(), anyList(), anyInt(), anyList(), 
                anyInt(), anyInt(), anyInt());
    }
}
