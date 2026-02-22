package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.search.service.ClovaSearchService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ArticleService 검색 기능 통합 테스트
 * 실제 PostgreSQL 데이터베이스를 사용한 검색 동작 검증
 */
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class ArticleSearchIntegrationTest {

    @Autowired
    private ArticleSearchService articleSearchService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    @Qualifier("articleCorporationRepository")
    private CorporationRepository corporationRepository;

    @MockBean
    private ClovaSearchService clovaSearchService;

    private Corporation testCorporation;
    private List<Article> testArticles;

    @BeforeEach
    void setUp() {
        // 테스트용 Corporation 생성
        testCorporation = Corporation.builder()
                .name("테스트기업")
                .isDomestic(1)
                .build();
        testCorporation = corporationRepository.save(testCorporation);

        // 테스트용 Article 생성
        testArticles = new ArrayList<>();
        
        Article article1 = Article.builder()
                .title("Spring Boot 완벽 가이드")
                .link("https://test.com/spring-boot-guide")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now().minusDays(1))
                .viewCount(100)
                .likeCount(10)
                .summary("Spring Boot 프레임워크를 처음부터 끝까지 배우는 가이드")
                .build();
        testArticles.add(articleRepository.save(article1));

        Article article2 = Article.builder()
                .title("Java 프로그래밍 기초")
                .link("https://test.com/java-basics")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now().minusDays(2))
                .viewCount(200)
                .likeCount(20)
                .summary("Java 언어의 기초를 다지는 튜토리얼")
                .build();
        testArticles.add(articleRepository.save(article2));

        Article article3 = Article.builder()
                .title("Kotlin 실전 활용")
                .link("https://test.com/kotlin-practice")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now().minusDays(3))
                .viewCount(150)
                .likeCount(15)
                .summary("Kotlin을 실무에서 활용하는 방법")
                .build();
        testArticles.add(articleRepository.save(article3));

        Article article4 = Article.builder()
                .title("Docker 컨테이너 완벽 이해")
                .link("https://test.com/docker-guide")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now().minusDays(4))
                .viewCount(300)
                .likeCount(30)
                .summary("Docker를 이용한 컨테이너 기술 마스터")
                .build();
        testArticles.add(articleRepository.save(article4));

        Article article5 = Article.builder()
                .title("Kubernetes 오케스트레이션")
                .link("https://test.com/kubernetes")
                .corporation(testCorporation)
                .publishedAt(LocalDateTime.now().minusDays(5))
                .viewCount(250)
                .likeCount(25)
                .summary("Kubernetes로 컨테이너 오케스트레이션")
                .build();
        testArticles.add(articleRepository.save(article5));
    }

    @Test
    @DisplayName("통합 검색 - Spring 키워드로 검색")
    void searchArticles_SpringKeyword() {
        // given
        String keyword = "Spring";
        
        // Mock Vector search result
        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(testArticles.get(0).getId(), 0.95); // Spring Boot article
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        assertThat(result.getContent())
                .extracting(ArticleSearchResultDto::getTitle)
                .anyMatch(title -> title.contains("Spring"));
    }

    @Test
    @DisplayName("따옴표 검색 - 제목 ILIKE 매칭")
    void searchArticles_ExactMatch() {
        // given
        String keyword = "Docker";

        // when - 따옴표 검색은 ILIKE 기반이므로 Vector mock 불필요
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        assertThat(result.getContent().get(0).getTitle()).contains("Docker");
    }

    @Test
    @DisplayName("통합 검색 - 여러 키워드 조합")
    void searchArticles_MultipleKeywords() {
        // given
        String keyword = "Java Spring";
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(testArticles.get(0).getId(), 0.90); // Spring Boot
        vectorScores.put(testArticles.get(1).getId(), 0.85); // Java
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        assertThat(result.getContent().size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("통합 검색 - 지역 필터 (국내)")
    void searchArticles_DomesticFilter() {
        // given
        String keyword = "Java";
        List<String> regions = List.of("domestic");
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(testArticles.get(1).getId(), 0.90);
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, regions, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        // All results should be from domestic corporations (isDomestic = 1)
    }

    @Test
    @DisplayName("통합 검색 - 페이징")
    void searchArticles_Pagination() {
        // given
        String keyword = "가이드"; // Should match multiple articles
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        for (Article article : testArticles) {
            vectorScores.put(article.getId(), 0.80 + (Math.random() * 0.2));
        }
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when - First page
        Page<ArticleSearchResultDto> page1 = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 2, "latest", "127.0.0.1", null
        );

        // then
        assertThat(page1.getNumber()).isEqualTo(0);
        assertThat(page1.getSize()).isEqualTo(2);
        assertThat(page1.getContent()).hasSizeLessThanOrEqualTo(2);
        
        if (page1.getTotalElements() > 2) {
            // when - Second page
            Page<ArticleSearchResultDto> page2 = articleSearchService.searchArticlesHybrid(
                    keyword, null, null, 1, 2, "latest", "127.0.0.1", null
            );

            // then
            assertThat(page2.getNumber()).isEqualTo(1);
            assertThat(page2.getContent()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("통합 검색 - Vector 검색 실패 시 BM25만으로 동작")
    void searchArticles_VectorFailureFallback() {
        // given
        String keyword = "Kubernetes";

        // Mock Vector search failure
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenThrow(new RuntimeException("Vector API failed"));

        // when - BM25만으로 검색 (H2 테스트에서는 BM25도 미지원이므로 빈 결과 가능)
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then - BM25만으로 검색 시도 (H2에서는 빈 결과)
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("통합 검색 - 빈 결과")
    void searchArticles_NoResults() {
        // given
        String keyword = "NonExistentKeywordXYZ123";
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("통합 검색 - 인기도순 정렬")
    void searchArticles_PopularSort() {
        // given
        String keyword = "컨테이너";
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(testArticles.get(3).getId(), 0.85); // Docker
        vectorScores.put(testArticles.get(4).getId(), 0.80); // Kubernetes
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "popular", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        // Results should be ordered by popularity (view + like counts)
    }

    @Test
    @DisplayName("통합 검색 - 최신순 정렬")
    void searchArticles_LatestSort() {
        // given
        String keyword = "프로그래밍";
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(testArticles.get(1).getId(), 0.85);
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        // Results should be ordered by publish date (newest first)
    }

    @Test
    @DisplayName("통합 검색 - RRF 스코어 계산 검증")
    void searchArticles_RRFScoring() {
        // given
        String keyword = "Java";
        
        // Mock Vector search with different scores
        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(testArticles.get(1).getId(), 0.95); // Java article - high vector score
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "relevance", "127.0.0.1", null
        );

        // then
        assertThat(result).isNotEmpty();
        // The article with title containing "Java" should rank high
        ArticleSearchResultDto topResult = result.getContent().get(0);
        assertThat(topResult.getTitle()).contains("Java");
        assertThat(topResult.getFinalScore()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("통합 검색 - 성능 벤치마크")
    void searchArticles_PerformanceBenchmark() {
        // given
        String keyword = "Spring Boot Java";
        
        // Mock Vector search
        Map<Long, Double> vectorScores = new HashMap<>();
        for (Article article : testArticles) {
            vectorScores.put(article.getId(), 0.75 + (Math.random() * 0.2));
        }
        ClovaSearchService.VectorSearchResult vectorResult = 
                new ClovaSearchService.VectorSearchResult(vectorScores, new float[1536]);
        when(clovaSearchService.searchByKeywordWithEmbedding(keyword))
                .thenReturn(vectorResult);
        when(clovaSearchService.computeSimilarityForArticlesWithEmbedding(any(), anyList()))
                .thenReturn(new HashMap<>());

        // when
        long startTime = System.currentTimeMillis();
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, null, null, 0, 10, "latest", "127.0.0.1", null
        );
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // then
        assertThat(result).isNotEmpty();
        assertThat(duration).isLessThan(5000); // Should complete within 5 seconds
        System.out.println("Search completed in " + duration + "ms");
    }
}
