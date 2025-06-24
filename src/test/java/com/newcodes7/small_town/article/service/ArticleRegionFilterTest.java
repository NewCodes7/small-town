package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
public class ArticleRegionFilterTest {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    public void testFilterByDomesticOnly() throws Exception {
        Corporation domesticCorp = createCorporation("국내 회사", "https://domestic.com", "https://blog.domestic.com", true);
        Corporation overseasCorp = createCorporation("해외 회사", "https://overseas.com", "https://blog.overseas.com", false);
        
        entityManager.persist(domesticCorp);
        entityManager.persist(overseasCorp);

        Article domesticArticle = createArticle("국내 기술 블로그", "국내 기술에 대한 내용", 
                                              "https://domestic.com/article", domesticCorp, LocalDateTime.now());
        Article overseasArticle = createArticle("해외 기술 블로그", "해외 기술에 대한 내용", 
                                              "https://overseas.com/article", overseasCorp, LocalDateTime.now());

        entityManager.persist(domesticArticle);
        entityManager.persist(overseasArticle);
        entityManager.flush();

        List<String> regions = Arrays.asList("domestic");
        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, regions, 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("국내 기술 블로그");
    }

    @Test
    public void testFilterByOverseasOnly() throws Exception {
        Corporation domesticCorp = createCorporation("국내 회사", "https://domestic.com", "https://blog.domestic.com", true);
        Corporation overseasCorp = createCorporation("해외 회사", "https://overseas.com", "https://blog.overseas.com", false);
        
        entityManager.persist(domesticCorp);
        entityManager.persist(overseasCorp);

        Article domesticArticle = createArticle("국내 기술 블로그", "국내 기술에 대한 내용", 
                                              "https://domestic.com/article", domesticCorp, LocalDateTime.now());
        Article overseasArticle = createArticle("해외 기술 블로그", "해외 기술에 대한 내용", 
                                              "https://overseas.com/article", overseasCorp, LocalDateTime.now());

        entityManager.persist(domesticArticle);
        entityManager.persist(overseasArticle);
        entityManager.flush();

        List<String> regions = Arrays.asList("overseas");
        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, regions, 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("해외 기술 블로그");
    }

    @Test
    public void testFilterByBothRegions() throws Exception {
        Corporation domesticCorp = createCorporation("국내 회사", "https://domestic.com", "https://blog.domestic.com", true);
        Corporation overseasCorp = createCorporation("해외 회사", "https://overseas.com", "https://blog.overseas.com", false);
        
        entityManager.persist(domesticCorp);
        entityManager.persist(overseasCorp);

        Article domesticArticle = createArticle("국내 기술 블로그", "국내 기술에 대한 내용", 
                                              "https://domestic.com/article", domesticCorp, LocalDateTime.now());
        Article overseasArticle = createArticle("해외 기술 블로그", "해외 기술에 대한 내용", 
                                              "https://overseas.com/article", overseasCorp, LocalDateTime.now());

        entityManager.persist(domesticArticle);
        entityManager.persist(overseasArticle);
        entityManager.flush();

        List<String> regions = Arrays.asList("domestic", "overseas");
        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, regions, 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    public void testNoRegionFilter() throws Exception {
        Corporation domesticCorp = createCorporation("국내 회사", "https://domestic.com", "https://blog.domestic.com", true);
        Corporation overseasCorp = createCorporation("해외 회사", "https://overseas.com", "https://blog.overseas.com", false);
        
        entityManager.persist(domesticCorp);
        entityManager.persist(overseasCorp);

        Article domesticArticle = createArticle("국내 기술 블로그", "국내 기술에 대한 내용", 
                                              "https://domestic.com/article", domesticCorp, LocalDateTime.now());
        Article overseasArticle = createArticle("해외 기술 블로그", "해외 기술에 대한 내용", 
                                              "https://overseas.com/article", overseasCorp, LocalDateTime.now());

        entityManager.persist(domesticArticle);
        entityManager.persist(overseasArticle);
        entityManager.flush();

        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    private Corporation createCorporation(String name, String homeLink, String blogLink, boolean isDomestic) throws Exception {
        java.lang.reflect.Constructor<Corporation> constructor = Corporation.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Corporation corporation = constructor.newInstance();
        
        setField(corporation, "name", name);
        setField(corporation, "homeLink", homeLink);
        setField(corporation, "blogLink", blogLink);
        setField(corporation, "isDomestic", isDomestic);
        
        return corporation;
    }

    private Article createArticle(String title, String summary, String link, Corporation corporation, LocalDateTime publishedAt) throws Exception {
        java.lang.reflect.Constructor<Article> constructor = Article.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Article article = constructor.newInstance();
        
        setField(article, "title", title);
        setField(article, "summary", summary);
        setField(article, "link", link);
        setField(article, "corporation", corporation);
        setField(article, "publishedAt", publishedAt);
        
        return article;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}