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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
@Transactional
public class ArticlePopularitySortTest {

    @Autowired
    private ArticleService articleService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    public void testPopularitySortByViewCount() throws Exception {
        Corporation corporation = createCorporation("테스트 회사", "https://test.com", "https://blog.test.com");
        entityManager.persist(corporation);

        // 조회수가 높은 글
        Article highViewArticle = createArticle("조회수 높은 글", "내용", "https://test.com/high-view", 
                                               corporation, LocalDateTime.now().minusDays(30));
        setField(highViewArticle, "viewCount", 1000);
        setField(highViewArticle, "likeCount", 5);

        // 조회수가 낮은 글
        Article lowViewArticle = createArticle("조회수 낮은 글", "내용", "https://test.com/low-view", 
                                              corporation, LocalDateTime.now());
        setField(lowViewArticle, "viewCount", 10);
        setField(lowViewArticle, "likeCount", 1);

        entityManager.persist(highViewArticle);
        entityManager.persist(lowViewArticle);
        entityManager.flush();

        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, "popular");

        assertThat(result.getTotalElements()).isEqualTo(2);
        // 조회수가 높은 글이 인기도 점수가 더 높아야 함
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("조회수 높은 글");
    }

    @Test
    public void testPopularitySortByLikeCount() throws Exception {
        Corporation corporation = createCorporation("테스트 회사", "https://test.com", "https://blog.test.com");
        entityManager.persist(corporation);

        // 좋아요가 많은 글
        Article highLikeArticle = createArticle("좋아요 많은 글", "내용", "https://test.com/high-like", 
                                               corporation, LocalDateTime.now().minusDays(1));
        setField(highLikeArticle, "viewCount", 100);
        setField(highLikeArticle, "likeCount", 200);

        // 좋아요가 적은 글
        Article lowLikeArticle = createArticle("좋아요 적은 글", "내용", "https://test.com/low-like", 
                                              corporation, LocalDateTime.now().minusDays(1));
        setField(lowLikeArticle, "viewCount", 150);
        setField(lowLikeArticle, "likeCount", 5);

        entityManager.persist(highLikeArticle);
        entityManager.persist(lowLikeArticle);
        entityManager.flush();

        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, "popular");

        assertThat(result.getTotalElements()).isEqualTo(2);
        // 좋아요 가중치로 인해 첫 번째 글이 더 높은 점수를 받아야 함
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("좋아요 많은 글");
    }

    @Test
    public void testPopularitySortCombined() throws Exception {
        Corporation corporation = createCorporation("테스트 회사", "https://test.com", "https://blog.test.com", true);
        entityManager.persist(corporation);

        // 높은 조회수 글
        Article highViewArticle = createArticle("높은 조회수 글", "내용", "https://test.com/high-view", 
                                               corporation, LocalDateTime.now().minusDays(1));
        setField(highViewArticle, "viewCount", 500);
        setField(highViewArticle, "likeCount", 10);

        // 높은 좋아요 글
        Article highLikeArticle = createArticle("높은 좋아요 글", "내용", "https://test.com/high-like", 
                                               corporation, LocalDateTime.now().minusDays(1));
        setField(highLikeArticle, "viewCount", 100);
        setField(highLikeArticle, "likeCount", 50);

        entityManager.persist(highViewArticle);
        entityManager.persist(highLikeArticle);
        entityManager.flush();

        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, "popular");

        assertThat(result.getTotalElements()).isEqualTo(2);
        // 조회수 점수: 500*0.6 + 10*0.3 = 303점
        // 좋아요 점수: 100*0.6 + 50*0.3 = 75점
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("높은 조회수 글");
    }

    @Test
    public void testLatestSort() throws Exception {
        Corporation corporation = createCorporation("테스트 회사", "https://test.com", "https://blog.test.com", true);
        entityManager.persist(corporation);

        Article oldArticle = createArticle("오래된 글", "내용", "https://test.com/old", 
                                          corporation, LocalDateTime.now().minusDays(5));
        Article newArticle = createArticle("최신 글", "내용", "https://test.com/new", 
                                          corporation, LocalDateTime.now());

        entityManager.persist(oldArticle);
        entityManager.persist(newArticle);
        entityManager.flush();

        Page<ArticleListResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("최신 글");
        assertThat(result.getContent().get(1).getTitle()).isEqualTo("오래된 글");
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

    private Corporation createCorporation(String name, String homeLink, String blogLink) throws Exception {
        return createCorporation(name, homeLink, blogLink, true);
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