package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.service.ArticleEmbeddingService;
import com.newcodes7.small_town.article.service.TermSynonymService;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService.QueryComplexity;

@ExtendWith(MockitoExtension.class)
public class SemanticTermExpansionServiceTest {

    @Mock
    private TermRepository termRepository;

    @Mock
    private ArticleEmbeddingService embeddingService;

    @Mock
    private TermSynonymService termSynonymService;

    @Mock
    private MorphemeAnalyzer morphemeAnalyzer;

    @InjectMocks
    private SemanticTermExpansionService semanticTermExpansionService;

    @Test
    @DisplayName("쿼리 복잡도 분류 - SIMPLE (단어 1개)")
    void classifyQueryComplexity_Simple() {
        // when & then
        assertThat(semanticTermExpansionService.classifyQueryComplexity("mysql"))
            .isEqualTo(QueryComplexity.SIMPLE);
        assertThat(semanticTermExpansionService.classifyQueryComplexity("kubernetes"))
            .isEqualTo(QueryComplexity.SIMPLE);
    }

    @Test
    @DisplayName("쿼리 복잡도 분류 - MODERATE (단어 2개)")
    void classifyQueryComplexity_Moderate() {
        // when & then
        assertThat(semanticTermExpansionService.classifyQueryComplexity("mysql 최적화"))
            .isEqualTo(QueryComplexity.MODERATE);
        assertThat(semanticTermExpansionService.classifyQueryComplexity("spring boot"))
            .isEqualTo(QueryComplexity.MODERATE);
    }

    @Test
    @DisplayName("쿼리 복잡도 분류 - COMPLEX (단어 3개 이상)")
    void classifyQueryComplexity_Complex() {
        // when & then
        assertThat(semanticTermExpansionService.classifyQueryComplexity("mysql 인덱스 설계"))
            .isEqualTo(QueryComplexity.COMPLEX);
        assertThat(semanticTermExpansionService.classifyQueryComplexity("검색 성능 개선 방법"))
            .isEqualTo(QueryComplexity.COMPLEX);
    }

    @Test
    @DisplayName("쿼리 복잡도 분류 - null 또는 빈 문자열은 SIMPLE")
    void classifyQueryComplexity_NullOrEmpty() {
        // when & then
        assertThat(semanticTermExpansionService.classifyQueryComplexity(null))
            .isEqualTo(QueryComplexity.SIMPLE);
        assertThat(semanticTermExpansionService.classifyQueryComplexity(""))
            .isEqualTo(QueryComplexity.SIMPLE);
        assertThat(semanticTermExpansionService.classifyQueryComplexity("   "))
            .isEqualTo(QueryComplexity.SIMPLE);
    }

    @Test
    @DisplayName("검색어 확장 - Term 추출 실패 시 원본 키워드 반환")
    void expandSearchTerms_NoTermsExtracted_ReturnsOriginal() {
        // given
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of("xyz")))
            .thenReturn(new LinkedHashMap<>());

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms("xyz");

        // then
        assertThat(result).hasSize(1);
        assertThat(result).containsEntry("xyz", 1.0);
    }

    @Test
    @DisplayName("검색어 확장 - 직접 매칭만 (유의어 없음)")
    void expandSearchTerms_DirectMatchOnly() {
        // given
        String keyword = "spring";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("spring", new MorphemeAnalyzer.TermInfo("spring", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);
        when(termRepository.findByTerm("spring")).thenReturn(List.of());

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("spring", 1.0);
    }

    @Test
    @DisplayName("검색어 확장 - 유의어 포함")
    void expandSearchTerms_WithSynonyms() {
        // given
        String keyword = "spring";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("spring", new MorphemeAnalyzer.TermInfo("spring", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);

        Term springTerm = Term.builder().id(1L).term("spring").termType("SL").build();
        Term springBootTerm = Term.builder().id(2L).term("springboot").termType("SL").build();
        when(termRepository.findByTerm("spring")).thenReturn(List.of(springTerm));
        when(termSynonymService.getSynonymTermIds(1L)).thenReturn(List.of(1L, 2L));
        when(termRepository.findById(2L)).thenReturn(Optional.of(springBootTerm));

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("spring", 1.0);
        assertThat(result).containsEntry("springboot", 0.8);
    }

    @Test
    @DisplayName("검색어 확장 - 유의어 조회 중 예외 발생 시 직접 매칭만 반환")
    void expandSearchTerms_SynonymError_FallbackToDirectMatch() {
        // given
        String keyword = "java";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("java", new MorphemeAnalyzer.TermInfo("java", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);
        when(termRepository.findByTerm("java")).thenThrow(new RuntimeException("DB error"));

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("java", 1.0);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("검색어 확장 - 자기 자신 유의어 제외")
    void expandSearchTerms_ExcludesSelfSynonym() {
        // given
        String keyword = "docker";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("docker", new MorphemeAnalyzer.TermInfo("docker", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);

        Term dockerTerm = Term.builder().id(1L).term("docker").termType("SL").build();
        when(termRepository.findByTerm("docker")).thenReturn(List.of(dockerTerm));
        // synonym IDs include self
        when(termSynonymService.getSynonymTermIds(1L)).thenReturn(List.of(1L));

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("docker", 1.0);
        assertThat(result).hasSize(1);
        verify(termRepository, never()).findById(1L);
    }

    @Test
    @DisplayName("검색어 확장 - 여러 Term 추출")
    void expandSearchTerms_MultipleTerms() {
        // given
        String keyword = "spring boot";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("spring", new MorphemeAnalyzer.TermInfo("spring", "SL", 1));
        termMap.put("boot", new MorphemeAnalyzer.TermInfo("boot", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);
        when(termRepository.findByTerm("spring")).thenReturn(List.of());
        when(termRepository.findByTerm("boot")).thenReturn(List.of());

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("spring", 1.0);
        assertThat(result).containsEntry("boot", 1.0);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("SimilarTermInfo 생성 및 조회")
    void similarTermInfo_Creation() {
        // given
        SemanticTermExpansionService.SimilarTermInfo info =
            new SemanticTermExpansionService.SimilarTermInfo("test", 0.95);

        // then
        assertThat(info.getTerm()).isEqualTo("test");
        assertThat(info.getSimilarity()).isEqualTo(0.95);
    }
}
