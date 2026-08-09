package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.embedding.service.ArticleEmbeddingService;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.search.service.SemanticTermExpansionService.QueryComplexity;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.service.TermSynonymService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

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

    /**
     * 기본 테스트에서는 getCache()가 null을 반환해 캐시 없이 동작한다.
     * 캐시 동작 자체는 expandSearchTerms_CachesResult에서 실 CacheManager로 검증.
     */
    @Mock
    private CacheManager cacheManager;

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
        when(termSynonymService.getSynonymsByTerms(List.of("spring"))).thenReturn(Map.of());

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("spring", 1.0);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("검색어 확장 - 유의어 포함")
    void expandSearchTerms_WithSynonyms() {
        // given
        String keyword = "spring";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("spring", new MorphemeAnalyzer.TermInfo("spring", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);

        when(termSynonymService.getSynonymsByTerms(List.of("spring")))
            .thenReturn(Map.of("spring", List.of("springboot")));

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
        when(termSynonymService.getSynonymsByTerms(List.of("java")))
            .thenThrow(new RuntimeException("DB error"));

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("java", 1.0);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("검색어 확장 - 직접 매칭과 같은 유의어는 가중치를 낮추지 않음")
    void expandSearchTerms_KeepsDirectWeightForSelfSynonym() {
        // given
        String keyword = "docker";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("docker", new MorphemeAnalyzer.TermInfo("docker", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);

        // 유의어 조회가 직접 매칭과 동일한 term을 돌려줘도 1.0이 0.8로 덮이면 안 된다
        when(termSynonymService.getSynonymsByTerms(List.of("docker")))
            .thenReturn(Map.of("docker", List.of("docker")));

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("docker", 1.0);
        assertThat(result).hasSize(1);
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
        when(termSynonymService.getSynonymsByTerms(List.of("spring", "boot"))).thenReturn(Map.of());

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("spring", 1.0);
        assertThat(result).containsEntry("boot", 1.0);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("검색어 확장 - 유의어 조회는 term 수와 무관하게 1회만 호출 (N+1 회귀 방지)")
    void expandSearchTerms_QueriesSynonymsOnce() {
        // given
        String keyword = "spring boot mysql";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("spring", new MorphemeAnalyzer.TermInfo("spring", "SL", 1));
        termMap.put("boot", new MorphemeAnalyzer.TermInfo("boot", "SL", 1));
        termMap.put("mysql", new MorphemeAnalyzer.TermInfo("mysql", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);
        when(termSynonymService.getSynonymsByTerms(List.of("spring", "boot", "mysql")))
            .thenReturn(Map.of("mysql", List.of("마이에스큐엘")));

        // when
        Map<String, Double> result = semanticTermExpansionService.expandSearchTerms(keyword);

        // then
        assertThat(result).containsEntry("spring", 1.0);
        assertThat(result).containsEntry("mysql", 1.0);
        assertThat(result).containsEntry("마이에스큐엘", 0.8);
        verify(termSynonymService, times(1)).getSynonymsByTerms(anyCollection());
        verifyNoInteractions(termRepository);
    }

    @Test
    @DisplayName("검색어 확장 - 동일 키워드 재호출 시 캐시 히트로 DB 조회 없음")
    void expandSearchTerms_CachesResult() {
        // given - 실 CacheManager를 주입한 인스턴스로 검증
        SemanticTermExpansionService service = new SemanticTermExpansionService(
            termRepository, embeddingService, termSynonymService, morphemeAnalyzer,
            new ConcurrentMapCacheManager("searchTermExpansion"));

        String keyword = "kubernetes";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("kubernetes", new MorphemeAnalyzer.TermInfo("kubernetes", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);
        when(termSynonymService.getSynonymsByTerms(List.of("kubernetes")))
            .thenReturn(Map.of("kubernetes", List.of("k8s")));

        // when
        Map<String, Double> first = service.expandSearchTerms(keyword);
        Map<String, Double> second = service.expandSearchTerms(keyword);

        // then
        assertThat(first).isEqualTo(second);
        assertThat(second).containsEntry("k8s", 0.8);
        verify(termSynonymService, times(1)).getSynonymsByTerms(anyCollection());
        verify(morphemeAnalyzer, times(1)).extractTermsFromMultipleTexts(List.of(keyword));
    }

    @Test
    @DisplayName("검색어 확장 - 유의어 조회 실패 결과는 캐시하지 않음")
    void expandSearchTerms_DoesNotCacheDegradedResult() {
        // given
        SemanticTermExpansionService service = new SemanticTermExpansionService(
            termRepository, embeddingService, termSynonymService, morphemeAnalyzer,
            new ConcurrentMapCacheManager("searchTermExpansion"));

        String keyword = "redis";
        Map<String, MorphemeAnalyzer.TermInfo> termMap = new LinkedHashMap<>();
        termMap.put("redis", new MorphemeAnalyzer.TermInfo("redis", "SL", 1));
        when(morphemeAnalyzer.extractTermsFromMultipleTexts(List.of(keyword))).thenReturn(termMap);
        when(termSynonymService.getSynonymsByTerms(List.of("redis")))
            .thenThrow(new RuntimeException("DB error"))
            .thenReturn(Map.of("redis", List.of("레디스")));

        // when - 1회차는 degrade, 2회차는 정상 (캐시됐다면 2회차가 degrade 결과를 그대로 받는다)
        Map<String, Double> degraded = service.expandSearchTerms(keyword);
        Map<String, Double> recovered = service.expandSearchTerms(keyword);

        // then
        assertThat(degraded).hasSize(1).containsEntry("redis", 1.0);
        assertThat(recovered).containsEntry("레디스", 0.8);
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
