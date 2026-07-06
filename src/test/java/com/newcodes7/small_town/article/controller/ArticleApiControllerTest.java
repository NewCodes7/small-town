package com.newcodes7.small_town.article.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.utils.ArticleCreator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MockMvc;

public class ArticleApiControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private CacheManager cacheManager;

    private Article weeklyOnlyArticle;
    private Article monthlyOnlyArticle;

    @BeforeEach
    public void setUp() {
        articleRepository.deleteAll();
        corporationRepository.deleteAll();
        ArticleCreator.resetArticleIdCounter();
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });

        Corporation corporation = ArticleCreator.createCorporation(1);
        corporationRepository.save(corporation);

        // 최근 3일 이내 발행 - 주간/월간 모두 노출
        weeklyOnlyArticle = ArticleCreator.createArticle(corporation);
        weeklyOnlyArticle.setPublishedAt(LocalDateTime.now().minusDays(3));
        articleRepository.save(weeklyOnlyArticle);

        // 15일 전 발행 - 주간 조회에서는 제외, 월간 조회에서는 포함
        monthlyOnlyArticle = ArticleCreator.createArticle(corporation);
        monthlyOnlyArticle.setPublishedAt(LocalDateTime.now().minusDays(15));
        articleRepository.save(monthlyOnlyArticle);
    }

    @Test
    public void 인기글_조회_기본값은_주간() throws Exception {
        mockMvc.perform(get("/api/articles/popular"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(weeklyOnlyArticle.getId()));
    }

    @Test
    public void 인기글_조회_주간() throws Exception {
        mockMvc.perform(get("/api/articles/popular").param("period", "weekly"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(weeklyOnlyArticle.getId()));
    }

    @Test
    public void 인기글_조회_월간() throws Exception {
        mockMvc.perform(get("/api/articles/popular").param("period", "monthly"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    public void 인기글_조회_잘못된_period() throws Exception {
        mockMvc.perform(get("/api/articles/popular").param("period", "yearly"))
            .andExpect(status().isBadRequest());
    }
}
