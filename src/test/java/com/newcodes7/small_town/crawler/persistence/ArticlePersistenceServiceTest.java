package com.newcodes7.small_town.crawler.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.crawler.dto.ArticleAnalysisResponse;
import com.newcodes7.small_town.crawler.integration.openai.OpenaiService;
import com.newcodes7.small_town.crawler.integration.translation.TranslationService;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.crawler.service.CrawlingRunService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;

/**
 * ArticlePersistenceService 단위 테스트
 * AI 분석, 카테고리 저장, 번역 처리 로직 검증
 */
@ExtendWith(MockitoExtension.class)
class ArticlePersistenceServiceTest {

    @Mock
    private CrawlerArticleRepository crawlerArticleRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private OpenaiService openaiService;

    @Mock
    private TranslationService translationService;

    @Mock
    private CrawlingRunService crawlingRunService;

    private ArticlePersistenceService articlePersistenceService;

    @BeforeEach
    void setUp() {
        articlePersistenceService = new ArticlePersistenceService(
                crawlerArticleRepository,
                categoryRepository,
                openaiService,
                translationService,
                crawlingRunService
        );
    }

    // ===== analyzeExistingArticles 테스트 =====

    @Nested
    @DisplayName("analyzeExistingArticles")
    class AnalyzeExistingArticlesTests {

        @Test
        @DisplayName("분석 대상 없음 → success 반환, processedCount=0")
        void analyzeExistingArticles_emptyList_returnsSuccessWithZeroCount() throws Exception {
            // given
            when(crawlerArticleRepository.findUnanalyzedArticles()).thenReturn(List.of());

            // when
            Map<String, Object> result = articlePersistenceService.analyzeExistingArticles();

            // then
            assertThat(result.get("success")).isEqualTo(true);
            assertThat(result.get("processedCount")).isEqualTo(0);
            assertThat(result.get("successCount")).isEqualTo(0);
            assertThat(result.get("failureCount")).isEqualTo(0);
            verify(openaiService, never()).sendArticleAnalysis(any());
        }

        @Test
        @DisplayName("분석 성공 시 successCount 증가")
        void analyzeExistingArticles_successfulAnalysis_incrementsSuccessCount() throws Exception {
            // given
            Article article = Article.builder()
                    .title("Spring Boot Guide")
                    .link("https://example.com/1")
                    .build();
            article.setId(1L);

            when(crawlerArticleRepository.findUnanalyzedArticles()).thenReturn(List.of(article));

            ArticleAnalysisResponse response = ArticleAnalysisResponse.builder()
                    .category("Backend")
                    .build();
            when(openaiService.sendArticleAnalysis(article)).thenReturn(response);

            Category category = Category.builder().name("Backend").build();
            when(categoryRepository.findByName("Backend")).thenReturn(Optional.of(category));
            when(crawlerArticleRepository.save(any())).thenReturn(article);

            // when
            Map<String, Object> result = articlePersistenceService.analyzeExistingArticles();

            // then
            assertThat(result.get("successCount")).isEqualTo(1);
            assertThat(result.get("failureCount")).isEqualTo(0);
            assertThat(result.get("processedCount")).isEqualTo(1);
        }

        @Test
        @DisplayName("일부 분석 실패 시 나머지 계속 처리")
        void analyzeExistingArticles_partialFailure_continuesProcessing() throws Exception {
            // given
            Article article1 = Article.builder().title("Article 1").link("https://ex.com/1").build();
            article1.setId(1L);
            Article article2 = Article.builder().title("Article 2").link("https://ex.com/2").build();
            article2.setId(2L);

            when(crawlerArticleRepository.findUnanalyzedArticles()).thenReturn(List.of(article1, article2));

            // article1 실패
            when(openaiService.sendArticleAnalysis(article1)).thenThrow(new RuntimeException("OpenAI timeout"));

            // article2 성공
            ArticleAnalysisResponse response = ArticleAnalysisResponse.builder().category("DevOps").build();
            when(openaiService.sendArticleAnalysis(article2)).thenReturn(response);
            Category category = Category.builder().name("DevOps").build();
            when(categoryRepository.findByName("DevOps")).thenReturn(Optional.of(category));
            when(crawlerArticleRepository.save(any())).thenReturn(article2);

            // when
            Map<String, Object> result = articlePersistenceService.analyzeExistingArticles();

            // then
            assertThat(result.get("successCount")).isEqualTo(1);
            assertThat(result.get("failureCount")).isEqualTo(1);
            assertThat(result.get("processedCount")).isEqualTo(2);
        }

        @Test
        @DisplayName("카테고리가 없으면 새로 생성하여 저장")
        void analyzeExistingArticles_categoryNotFound_createsNewCategory() throws Exception {
            // given
            Article article = Article.builder().title("New Article").link("https://ex.com/new").build();
            article.setId(10L);

            when(crawlerArticleRepository.findUnanalyzedArticles()).thenReturn(List.of(article));

            ArticleAnalysisResponse response = ArticleAnalysisResponse.builder()
                    .category("NewCategory")
                    .build();
            when(openaiService.sendArticleAnalysis(article)).thenReturn(response);

            // 기존 카테고리 없음
            when(categoryRepository.findByName("NewCategory")).thenReturn(Optional.empty());
            Category newCategory = Category.builder().name("NewCategory").build();
            when(categoryRepository.save(any(Category.class))).thenReturn(newCategory);
            when(crawlerArticleRepository.save(any())).thenReturn(article);

            // when
            articlePersistenceService.analyzeExistingArticles();

            // then
            verify(categoryRepository).save(any(Category.class));
        }
    }

    // ===== updateArticleCategory 테스트 =====

    @Nested
    @DisplayName("updateArticleCategory")
    class UpdateArticleCategoryTests {

        @Test
        @DisplayName("존재하지 않는 글 ID → IllegalArgumentException")
        void updateArticleCategory_articleNotFound_throwsException() {
            // given
            when(crawlerArticleRepository.findById(999L)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> articlePersistenceService.updateArticleCategory(999L, "Backend"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("999");
        }

        @Test
        @DisplayName("삭제된 글 → IllegalArgumentException")
        void updateArticleCategory_deletedArticle_throwsException() {
            // given
            Article article = Article.builder()
                    .title("Deleted Article")
                    .link("https://ex.com/deleted")
                    .build();
            article.setId(5L);
            article.setDeletedAt(java.time.LocalDateTime.now());

            when(crawlerArticleRepository.findById(5L)).thenReturn(Optional.of(article));

            // when / then
            assertThatThrownBy(() -> articlePersistenceService.updateArticleCategory(5L, "Backend"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정상 카테고리 업데이트 → 카테고리 저장 및 article 업데이트")
        void updateArticleCategory_success_updatesCategory() {
            // given
            Article article = Article.builder()
                    .title("Normal Article")
                    .link("https://ex.com/normal")
                    .build();
            article.setId(1L);

            when(crawlerArticleRepository.findById(1L)).thenReturn(Optional.of(article));

            Category category = Category.builder().name("Frontend").build();
            when(categoryRepository.findByName("Frontend")).thenReturn(Optional.of(category));
            when(crawlerArticleRepository.save(article)).thenReturn(article);

            // when
            assertThatCode(() -> articlePersistenceService.updateArticleCategory(1L, "Frontend"))
                    .doesNotThrowAnyException();

            // then
            verify(crawlerArticleRepository).save(article);
            assertThat(article.getCategory()).isEqualTo(category);
        }
    }

    // ===== updateArticleTranslatedTitle 테스트 =====

    @Nested
    @DisplayName("updateArticleTranslatedTitle")
    class UpdateArticleTranslatedTitleTests {

        @Test
        @DisplayName("존재하지 않는 글 → IllegalArgumentException")
        void updateArticleTranslatedTitle_notFound_throwsException() {
            // given
            when(crawlerArticleRepository.findById(99L)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> articlePersistenceService.updateArticleTranslatedTitle(99L, "번역 제목"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정상 번역 제목 업데이트 → article에 translatedTitle 설정 후 저장")
        void updateArticleTranslatedTitle_success_setsTranslatedTitle() {
            // given
            Article article = Article.builder()
                    .title("English Title")
                    .link("https://ex.com/en")
                    .build();
            article.setId(3L);

            when(crawlerArticleRepository.findById(3L)).thenReturn(Optional.of(article));
            when(crawlerArticleRepository.save(article)).thenReturn(article);

            // when
            articlePersistenceService.updateArticleTranslatedTitle(3L, "한국어 제목");

            // then
            assertThat(article.getTranslatedTitle()).isEqualTo("한국어 제목");
            verify(crawlerArticleRepository).save(article);
        }
    }
}
