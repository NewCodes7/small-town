package com.newcodes7.small_town.article.repository;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public class ArticleRepositoryTest extends IntegrationTestBase {

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CorporationRepository corporationRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Corporation testCorporation;

    @BeforeEach
    void setUp() {
        // 테스트용 Corporation 생성
        testCorporation = Corporation.builder()
                .name("테스트회사")
                .isDomestic(1)
                .blogLink("https://test.com/blog")
                .build();
        testCorporation = corporationRepository.save(testCorporation);
    }

    @Test
    @DisplayName("활성 아티클 전체 조회")
    void findAllActiveArticlesWithDetails() {
        // given
        Article article1 = createArticle("제목1", LocalDateTime.now().minusDays(1));
        Article article2 = createArticle("제목2", LocalDateTime.now().minusDays(2));
        articleRepository.save(article1);
        articleRepository.save(article2);

        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<Article> result = articleRepository.findAllActiveArticlesWithDetails(pageable);

        // then
        assertThat(result.getContent()).hasSize(2);
        // 최신순으로 정렬되어야 함
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("제목1");
        assertThat(result.getContent().get(1).getTitle()).isEqualTo("제목2");
    }

    @Test
    @DisplayName("인기 아티클 조회 - 조회수와 좋아요 수 기준")
    void findPopularArticlesWithDetails() {
        // given
        Article article1 = createArticle("제목1", LocalDateTime.now());
        article1.setViewCount(100);
        article1.setLikeCount(10);

        Article article2 = createArticle("제목2", LocalDateTime.now());
        article2.setViewCount(50);
        article2.setLikeCount(5);

        articleRepository.save(article1);
        articleRepository.save(article2);

        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<Article> result = articleRepository.findPopularArticlesWithDetails(pageable);

        // then
        assertThat(result.getContent()).hasSize(2);
        // 인기도 높은 순으로 정렬되어야 함 (viewCount * 0.6 + likeCount * 0.3)
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("제목1");
    }

    @Test
    @DisplayName("제목으로 아티클 검색")
    void findArticlesByTitleContaining() {
        // given
        Article article1 = createArticle("Spring Boot 튜토리얼", LocalDateTime.now());
        Article article2 = createArticle("Java 프로그래밍", LocalDateTime.now());
        Article article3 = createArticle("Python 기초", LocalDateTime.now());

        articleRepository.save(article1);
        articleRepository.save(article2);
        articleRepository.save(article3);

        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<Article> result = articleRepository.findArticlesByTitleContaining("%spring%", pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).contains("Spring");
    }

    @Test
    @DisplayName("아티클 조회 - Corporation fetch join 확인")
    void findAllActiveArticles_WithCorporationFetchJoin() {
        // given
        Article article = createArticle("테스트", LocalDateTime.now());
        articleRepository.save(article);

        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<Article> result = articleRepository.findAllActiveArticlesWithDetails(pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        Article foundArticle = result.getContent().get(0);

        // Corporation이 fetch join되어 있어야 함 (lazy loading 방지)
        assertThat(foundArticle.getCorporation()).isNotNull();
        assertThat(foundArticle.getCorporation().getName()).isEqualTo("테스트회사");
    }

    @Test
    @DisplayName("삭제된 아티클은 조회되지 않음")
    void findAllActiveArticles_ExcludesDeletedArticles() {
        // given
        Article activeArticle = createArticle("활성아티클", LocalDateTime.now());
        Article deletedArticle = createArticle("삭제된아티클", LocalDateTime.now());
        deletedArticle.setDeletedAt(LocalDateTime.now());

        articleRepository.save(activeArticle);
        articleRepository.save(deletedArticle);

        Pageable pageable = PageRequest.of(0, 10);

        // when
        Page<Article> result = articleRepository.findAllActiveArticlesWithDetails(pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getTitle()).isEqualTo("활성아티클");
    }

    @Test
    @DisplayName("페이징 처리 테스트")
    void pagination() {
        // given
        for (int i = 1; i <= 25; i++) {
            Article article = createArticle("제목" + i, LocalDateTime.now().minusDays(i));
            articleRepository.save(article);
        }

        // when
        Page<Article> page1 = articleRepository.findAllActiveArticlesWithDetails(PageRequest.of(0, 10));
        Page<Article> page2 = articleRepository.findAllActiveArticlesWithDetails(PageRequest.of(1, 10));
        Page<Article> page3 = articleRepository.findAllActiveArticlesWithDetails(PageRequest.of(2, 10));

        // then
        assertThat(page1.getContent()).hasSize(10);
        assertThat(page2.getContent()).hasSize(10);
        assertThat(page3.getContent()).hasSize(5);
        assertThat(page1.getTotalElements()).isEqualTo(25);
        assertThat(page1.getTotalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("ID 목록 조회 - Category까지 fetch join되어 트랜잭션 밖에서도 접근 가능")
    void findByIdInWithCorporation_FetchesCategory() {
        // given: 카테고리가 있는 아티클과 없는 아티클
        Category category = categoryRepository.save(Category.builder().name("백엔드").build());

        Article withCategory = createArticle("카테고리있음", LocalDateTime.now());
        withCategory.setCategory(category);
        Article withoutCategory = createArticle("카테고리없음", LocalDateTime.now());
        articleRepository.save(withCategory);
        articleRepository.save(withoutCategory);

        // 영속성 컨텍스트를 비워 fetch join 여부가 실제 쿼리로 판정되게 한다
        entityManager.flush();
        entityManager.clear();

        // when
        List<Article> result = articleRepository.findByIdInWithCorporation(
                List.of(withCategory.getId(), withoutCategory.getId()));

        // then: LEFT JOIN FETCH이므로 category가 없는 아티클도 누락되지 않는다
        assertThat(result).hasSize(2);

        Article foundWithCategory = result.stream()
                .filter(a -> a.getId().equals(withCategory.getId()))
                .findFirst()
                .orElseThrow();

        // 세션 밖 DTO 조립(SearchPrewarmScheduler 등 OSIV 없는 스레드)에서 lazy 초기화가
        // 터지지 않으려면 조회 시점에 이미 초기화되어 있어야 한다
        assertThat(Hibernate.isInitialized(foundWithCategory.getCategory())).isTrue();
        assertThat(foundWithCategory.getCategory().getName()).isEqualTo("백엔드");

        Article foundWithoutCategory = result.stream()
                .filter(a -> a.getId().equals(withoutCategory.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(foundWithoutCategory.getCategory()).isNull();
    }

    private Article createArticle(String title, LocalDateTime publishedAt) {
        return Article.builder()
                .title(title)
                .link("https://test.com/article/" + title)
                .corporation(testCorporation)
                .publishedAt(publishedAt)
                .viewCount(0)
                .likeCount(0)
                .build();
    }
}
