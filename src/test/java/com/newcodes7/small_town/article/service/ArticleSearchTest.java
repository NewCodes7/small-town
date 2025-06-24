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
import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
public class ArticleSearchTest {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    public void testSearchArticlesByTitle() throws Exception {
        Corporation corporation = createCorporation("Test Corporation", "https://test.com", "https://blog.test.com");
        entityManager.persist(corporation);

        Article article1 = createArticle("Spring Boot 개발 가이드", "Spring Boot 개발에 대한 내용", 
                                        "https://test.com/spring-boot", corporation, LocalDateTime.now());
        Article article2 = createArticle("React 프론트엔드 개발", "React 개발에 대한 내용", 
                                        "https://test.com/react", corporation, LocalDateTime.now());
        Article article3 = createArticle("Java 프로그래밍 기초", "Java 기초에 대한 내용", 
                                        "https://test.com/java", corporation, LocalDateTime.now());

        entityManager.persist(article1);
        entityManager.persist(article2);
        entityManager.persist(article3);
        entityManager.flush();

        Page<ArticleListResponseDto> result = articleService.searchArticles("Spring", 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getTitle()).contains("Spring");

        Page<ArticleListResponseDto> result2 = articleService.searchArticles("개발", 0, 10);

        assertThat(result2.getTotalElements()).isEqualTo(2);
        assertThat(result2.getContent()).hasSize(2);

        Page<ArticleListResponseDto> result3 = articleService.searchArticles("없는키워드", 0, 10);

        assertThat(result3.getTotalElements()).isEqualTo(0);
        assertThat(result3.getContent()).isEmpty();
    }

    private Corporation createCorporation(String name, String homeLink, String blogLink) throws Exception {
        Constructor<Corporation> constructor = Corporation.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Corporation corporation = constructor.newInstance();
        
        setField(corporation, "name", name);
        setField(corporation, "homeLink", homeLink);
        setField(corporation, "blogLink", blogLink);
        
        return corporation;
    }

    private Article createArticle(String title, String summary, String link, Corporation corporation, LocalDateTime publishedAt) throws Exception {
        Constructor<Article> constructor = Article.class.getDeclaredConstructor();
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