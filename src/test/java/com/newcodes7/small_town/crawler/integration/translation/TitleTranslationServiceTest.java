package com.newcodes7.small_town.crawler.integration.translation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.persistence.VideoPersistenceService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.video.repository.VideoRepository;

/**
 * TitleTranslationService 단위 테스트
 * 번역 로직, 스킵 조건, 유틸리티 메서드 검증
 */
@ExtendWith(MockitoExtension.class)
class TitleTranslationServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private TranslationService translationService;

    @Mock
    private VideoPersistenceService videoPersistenceService;

    private TitleTranslationService titleTranslationService;

    @BeforeEach
    void setUp() {
        titleTranslationService = new TitleTranslationService(
                articleRepository,
                videoRepository,
                translationService,
                videoPersistenceService
        );
    }

    // ===== getDisplayTitle 테스트 =====

    @Nested
    @DisplayName("getDisplayTitle")
    class GetDisplayTitleTests {

        @Test
        @DisplayName("번역된 제목이 있으면 번역된 제목 반환")
        void getDisplayTitle_translatedTitleExists_returnsTranslatedTitle() {
            // given
            Article article = Article.builder()
                    .title("How to Use Spring Boot")
                    .translatedTitle("스프링 부트 사용 방법")
                    .build();

            // when
            String result = titleTranslationService.getDisplayTitle(article);

            // then
            assertThat(result).isEqualTo("스프링 부트 사용 방법");
        }

        @Test
        @DisplayName("번역된 제목이 null이면 원본 제목 반환")
        void getDisplayTitle_noTranslatedTitle_returnsOriginalTitle() {
            // given
            Article article = Article.builder()
                    .title("How to Use Spring Boot")
                    .translatedTitle(null)
                    .build();

            // when
            String result = titleTranslationService.getDisplayTitle(article);

            // then
            assertThat(result).isEqualTo("How to Use Spring Boot");
        }

        @Test
        @DisplayName("번역된 제목이 빈 문자열이면 원본 제목 반환")
        void getDisplayTitle_emptyTranslatedTitle_returnsOriginalTitle() {
            // given
            Article article = Article.builder()
                    .title("How to Use Spring Boot")
                    .translatedTitle("")
                    .build();

            // when
            String result = titleTranslationService.getDisplayTitle(article);

            // then
            assertThat(result).isEqualTo("How to Use Spring Boot");
        }
    }

    // ===== isOverseasCompany 테스트 =====

    @Nested
    @DisplayName("isOverseasCompany")
    class IsOverseasCompanyTests {

        @Test
        @DisplayName("isDomestic=false 기업 → 해외 기업으로 판단")
        void isOverseasCompany_domesticFalse_returnsTrue() {
            // given
            Corporation corp = Corporation.builder()
                    .name("Foreign Corp")
                    .isDomestic(0)
                    .build();
            Article article = Article.builder()
                    .corporation(corp)
                    .title("Some Title")
                    .build();

            // when
            boolean result = titleTranslationService.isOverseasCompany(article);

            // then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("isDomestic=true 기업 → 국내 기업으로 판단")
        void isOverseasCompany_domesticTrue_returnsFalse() {
            // given
            Corporation corp = Corporation.builder()
                    .name("한국 기업")
                    .isDomestic(1)
                    .build();
            Article article = Article.builder()
                    .corporation(corp)
                    .title("제목")
                    .build();

            // when
            boolean result = titleTranslationService.isOverseasCompany(article);

            // then
            assertThat(result).isFalse();
        }
    }

    // ===== translateAllOverseasArticleTitles 테스트 =====

    @Nested
    @DisplayName("translateAllOverseasArticleTitles")
    class TranslateAllOverseasArticleTitlesTests {

        @Test
        @DisplayName("번역 대상 없음 → translateTitle 미호출")
        void translateAllOverseas_emptyList_noTranslationCalled() {
            // given
            when(articleRepository.findOverseasArticlesWithoutKoreanTitles()).thenReturn(List.of());

            // when
            titleTranslationService.translateAllOverseasArticleTitles();

            // then
            verify(translationService, never()).translateTitle(anyString());
            verify(articleRepository, never()).save(any());
        }

        @Test
        @DisplayName("원본 제목에 한국어 포함 시 번역 스킵")
        void translateAllOverseas_koreanOriginalTitle_skipsTranslation() {
            // given
            Article article = Article.builder()
                    .title("한국어 제목")
                    .build();
            when(articleRepository.findOverseasArticlesWithoutKoreanTitles()).thenReturn(List.of(article));
            when(translationService.containsKorean("한국어 제목")).thenReturn(true);

            // when
            titleTranslationService.translateAllOverseasArticleTitles();

            // then
            verify(translationService, never()).translateTitle(anyString());
            verify(articleRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 한국어 번역 제목 존재 시 번역 스킵")
        void translateAllOverseas_alreadyTranslated_skipsTranslation() {
            // given
            Article article = Article.builder()
                    .title("How to use Spring Boot")
                    .translatedTitle("스프링 부트 사용법")
                    .build();
            when(articleRepository.findOverseasArticlesWithoutKoreanTitles()).thenReturn(List.of(article));
            when(translationService.containsKorean("스프링 부트 사용법")).thenReturn(true);

            // when
            titleTranslationService.translateAllOverseasArticleTitles();

            // then
            verify(translationService, never()).translateTitle(anyString());
        }

        @Test
        @DisplayName("번역 결과에 한국어 미포함 시 저장 스킵")
        void translateAllOverseas_translationNotKorean_skipsStorage() {
            // given
            Article article = Article.builder()
                    .title("Spring Boot Guide")
                    .build();
            when(articleRepository.findOverseasArticlesWithoutKoreanTitles()).thenReturn(List.of(article));
            when(translationService.containsKorean("Spring Boot Guide")).thenReturn(false);
            when(translationService.translateTitle("Spring Boot Guide")).thenReturn("Spring Boot Anleitung");
            when(translationService.containsKorean("Spring Boot Anleitung")).thenReturn(false);

            // when
            titleTranslationService.translateAllOverseasArticleTitles();

            // then
            verify(articleRepository, never()).save(any());
        }
    }

    // ===== translateCorporationArticleTitles 테스트 =====

    @Nested
    @DisplayName("translateCorporationArticleTitles")
    class TranslateCorporationArticleTitlesTests {

        @Test
        @DisplayName("번역 대상 없음 → 번역 미호출")
        void translateCorporation_emptyArticles_noTranslation() {
            // given
            when(articleRepository.findByCorporationIdAndDeletedAtIsNull(1L)).thenReturn(List.of());

            // when
            titleTranslationService.translateCorporationArticleTitles(1L);

            // then
            verify(translationService, never()).translateTitle(anyString());
        }

        @Test
        @DisplayName("이미 번역 제목 존재 → 번역 스킵")
        void translateCorporation_alreadyTranslated_skips() {
            // given
            Article article = Article.builder()
                    .title("Title")
                    .translatedTitle("제목")
                    .build();
            when(articleRepository.findByCorporationIdAndDeletedAtIsNull(1L)).thenReturn(List.of(article));

            // when
            titleTranslationService.translateCorporationArticleTitles(1L);

            // then
            verify(translationService, never()).translateTitle(anyString());
        }
    }
}
