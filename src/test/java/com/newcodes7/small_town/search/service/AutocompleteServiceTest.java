package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.article.repository.TermAutocompleteJpaRepository;
import com.newcodes7.small_town.article.repository.TermAutocompleteJpaRepository.TermAutocompleteProjection;
import com.newcodes7.small_town.corporation.repository.CorporationAutocompleteJpaRepository;
import com.newcodes7.small_town.corporation.repository.CorporationAutocompleteJpaRepository.CorporationAutocompleteProjection;
import com.newcodes7.small_town.theme.repository.ThemeAutocompleteJpaRepository;
import com.newcodes7.small_town.theme.repository.ThemeAutocompleteJpaRepository.ThemeAutocompleteProjection;

/**
 * AutocompleteService 단위 테스트
 *
 * 빈 입력, 최소 길이, 병렬 검색, 결과 합성, 예외 처리 등 검증
 */
@ExtendWith(MockitoExtension.class)
class AutocompleteServiceTest {

    @Mock private TermAutocompleteJpaRepository termAutocompleteJpaRepository;
    @Mock private CorporationAutocompleteJpaRepository corporationAutocompleteJpaRepository;
    @Mock private ThemeAutocompleteJpaRepository themeAutocompleteJpaRepository;

    private AutocompleteService autocompleteService;

    // 동기 executor를 사용하여 CompletableFuture가 즉시 실행되도록
    private final ExecutorService syncExecutor = Executors.newFixedThreadPool(3);

    @BeforeEach
    void setUp() {
        autocompleteService = new AutocompleteService(
                syncExecutor,
                termAutocompleteJpaRepository,
                corporationAutocompleteJpaRepository,
                themeAutocompleteJpaRepository
        );
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: null 쿼리 → 빈 리스트")
    void getAutocompleteSuggestions_nullQuery_returnsEmpty() {
        // when
        List<Object> result = autocompleteService.getAutocompleteSuggestions(null);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(termAutocompleteJpaRepository, corporationAutocompleteJpaRepository, themeAutocompleteJpaRepository);
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: 빈 문자열 → 빈 리스트")
    void getAutocompleteSuggestions_emptyQuery_returnsEmpty() {
        // when
        List<Object> result = autocompleteService.getAutocompleteSuggestions("");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: 공백만 있는 문자열 → 빈 리스트")
    void getAutocompleteSuggestions_whitespaceQuery_returnsEmpty() {
        // when
        List<Object> result = autocompleteService.getAutocompleteSuggestions("   ");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: 정상 쿼리 → Corporation, Theme, Term 순서로 결과 반환")
    void getAutocompleteSuggestions_normalQuery_returnsOrderedResults() {
        // given
        CorporationAutocompleteProjection corpProj = mock(CorporationAutocompleteProjection.class);
        when(corpProj.getId()).thenReturn(1L);
        when(corpProj.getName()).thenReturn("Naver");
        when(corpProj.getAlternateName()).thenReturn(null);
        when(corpProj.getLogoUrl()).thenReturn(null);
        when(corpProj.getLogoS3Url()).thenReturn(null);
        when(corpProj.getLogoFilename()).thenReturn(null);
        when(corpProj.getDecomposedName()).thenReturn("ㄴㅔㅇㅣㅂㅓ");
        when(corpProj.getDecomposedAlternateName()).thenReturn(null);
        when(corporationAutocompleteJpaRepository.findAutocompleteCorporationsWithTwoPatterns(
                anyString(), anyString(), eq(2)))
                .thenReturn(List.of(corpProj));

        ThemeAutocompleteProjection themeProj = mock(ThemeAutocompleteProjection.class);
        when(themeProj.getId()).thenReturn(10L);
        when(themeProj.getName()).thenReturn("AI 기술");
        when(themeAutocompleteJpaRepository.findAutocompleteThemesSimple(anyString(), anyString(), eq(2)))
                .thenReturn(List.of(themeProj));

        TermAutocompleteProjection termProj = mock(TermAutocompleteProjection.class);
        when(termProj.getTerm()).thenReturn("naver");
        when(termProj.getTotalFrequency()).thenReturn(100L);
        when(termAutocompleteJpaRepository.findAutocompleteTerms(anyString(), eq(4)))
                .thenReturn(List.of(termProj));

        // when
        List<Object> result = autocompleteService.getAutocompleteSuggestions("nav");

        // then
        assertThat(result).hasSize(3);
        // Corporation 결과가 먼저 (Object[]{0, name, id, logoUrl})
        Object[] corpResult = (Object[]) result.get(0);
        assertThat(corpResult[0]).isEqualTo(0);
        assertThat(corpResult[1]).isEqualTo("Naver");
        // Theme 결과
        Object[] themeResult = (Object[]) result.get(1);
        assertThat(themeResult[0]).isEqualTo(1);
        // Term 결과
        assertThat(result.get(2)).isEqualTo("naver");
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: 모든 검색이 빈 결과 → 빈 리스트")
    void getAutocompleteSuggestions_allEmpty_returnsEmpty() {
        // given
        when(corporationAutocompleteJpaRepository.findAutocompleteCorporationsWithTwoPatterns(
                anyString(), anyString(), eq(2)))
                .thenReturn(List.of());
        when(themeAutocompleteJpaRepository.findAutocompleteThemesSimple(anyString(), anyString(), eq(2)))
                .thenReturn(List.of());
        when(termAutocompleteJpaRepository.findAutocompleteTerms(anyString(), eq(4)))
                .thenReturn(List.of());

        // when
        List<Object> result = autocompleteService.getAutocompleteSuggestions("xyz");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: Corporation 검색 예외 시에도 다른 결과 반환")
    void getAutocompleteSuggestions_corporationThrows_otherResultsReturned() {
        // given
        when(corporationAutocompleteJpaRepository.findAutocompleteCorporationsWithTwoPatterns(
                anyString(), anyString(), eq(2)))
                .thenThrow(new RuntimeException("DB error"));

        when(themeAutocompleteJpaRepository.findAutocompleteThemesSimple(anyString(), anyString(), eq(2)))
                .thenReturn(List.of());

        TermAutocompleteProjection termProj = mock(TermAutocompleteProjection.class);
        when(termProj.getTerm()).thenReturn("redis");
        when(termProj.getTotalFrequency()).thenReturn(50L);
        when(termAutocompleteJpaRepository.findAutocompleteTerms(anyString(), eq(4)))
                .thenReturn(List.of(termProj));

        // when
        List<Object> result = autocompleteService.getAutocompleteSuggestions("red");

        // then: Corporation 실패해도 Term 결과는 반환
        assertThat(result).isNotEmpty();
        assertThat(result).contains("redis");
    }

    @Test
    @DisplayName("getAutocompleteSuggestions: 한글 검색어 처리 확인")
    void getAutocompleteSuggestions_koreanQuery_worksCorrectly() {
        // given
        when(corporationAutocompleteJpaRepository.findAutocompleteCorporationsWithTwoPatterns(
                anyString(), anyString(), eq(2)))
                .thenReturn(List.of());
        when(themeAutocompleteJpaRepository.findAutocompleteThemesSimple(anyString(), anyString(), eq(2)))
                .thenReturn(List.of());
        when(termAutocompleteJpaRepository.findAutocompleteTerms(anyString(), eq(4)))
                .thenReturn(List.of());

        // when: 한글 쿼리도 예외 없이 처리
        List<Object> result = autocompleteService.getAutocompleteSuggestions("프로");

        // then: 결과가 비어있을 수 있지만 예외는 발생하지 않음
        assertThat(result).isNotNull();
    }
}
