package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * VectorSearchService 단위 테스트
 *
 * 2단계 벡터 검색(Binary HNSW → halfvec Reranking) 및 각 필터 라우팅 로직 검증
 */
@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    private ArticleChunkRepository chunkRepository;

    @Mock
    private SearchQueryEmbeddingService searchQueryEmbeddingService;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private VectorSearchService vectorSearchService;

    // 1024차원 테스트용 임베딩 (양수/음수 혼합)
    private static final float[] DUMMY_EMBEDDING = new float[1024];

    @BeforeAll
    static void setupEmbedding() {
        for (int i = 0; i < DUMMY_EMBEDDING.length; i++) {
            DUMMY_EMBEDDING[i] = i % 2 == 0 ? 0.5f : -0.3f;
        }
    }

    // ==================== searchByKeyword ====================

    @Test
    @DisplayName("null 키워드 → 빈 맵 반환, 의존성 호출 없음")
    void searchByKeyword_null키워드() {
        Map<Long, Double> result = vectorSearchService.searchByKeyword(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("공백 키워드 → 빈 맵 반환, 의존성 호출 없음")
    void searchByKeyword_공백키워드() {
        Map<Long, Double> result = vectorSearchService.searchByKeyword("   ");

        assertThat(result).isEmpty();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("임베딩 생성 실패(null 반환) → 빈 맵 반환")
    void searchByKeyword_임베딩생성실패() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(null);

        Map<Long, Double> result = vectorSearchService.searchByKeyword("spring boot");

        assertThat(result).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("정상 검색 → 아티클 ID와 유사도 스코어 맵 반환")
    void searchByKeyword_정상검색() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(DUMMY_EMBEDDING);
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{10L, 0.85},
                        new Object[]{20L, 0.72}
                ));

        Map<Long, Double> result = vectorSearchService.searchByKeyword("kubernetes");

        assertThat(result).hasSize(2);
        assertThat(result.get(10L)).isEqualTo(0.85);
        assertThat(result.get(20L)).isEqualTo(0.72);
    }

    @Test
    @DisplayName("결과 여러 개 → 모두 스코어 맵에 포함")
    void searchByKeyword_복수결과_모두포함() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(DUMMY_EMBEDDING);
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{10L, 0.85},
                        new Object[]{20L, 0.60}
                ));

        Map<Long, Double> result = vectorSearchService.searchByKeyword("redis");

        assertThat(result).hasSize(2).containsEntry(10L, 0.85).containsEntry(20L, 0.60);
    }

    @Test
    @DisplayName("DB 예외 발생 → 빈 맵 반환 (예외 전파 없음)")
    void searchByKeyword_예외발생() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(DUMMY_EMBEDDING);
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("DB 오류"));

        assertThatNoException().isThrownBy(() ->
                vectorSearchService.searchByKeyword("docker"));

        assertThat(vectorSearchService.searchByKeyword("docker")).isEmpty();
    }

    // ==================== searchByKeywordWithEmbedding — 필터 라우팅 ====================

    @Test
    @DisplayName("빈 키워드 → 빈 VectorSearchResult 반환, 의존성 미호출")
    void searchByKeywordWithEmbedding_빈키워드() {
        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("");

        assertThat(result.getScores()).isEmpty();
        assertThat(result.getQueryEmbedding()).isNull();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("필터 없음 → findArticlesByTwoStageSearch 호출")
    void searchByKeywordWithEmbedding_필터없음() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 0.80}));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("java");

        assertThat(result.getScores()).containsKey(1L);
        assertThat(result.getQueryEmbedding()).isEqualTo(DUMMY_EMBEDDING);
        verify(chunkRepository).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt());
        verify(chunkRepository, never()).findArticlesByTwoStageSearchWithDomesticFilter(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList());
    }

    @Test
    @DisplayName("국내 필터만 → findArticlesByTwoStageSearchWithDomesticFilter 호출")
    void searchByKeywordWithEmbedding_국내필터() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearchWithDomesticFilter(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 0.75}));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("java", List.of(1), null);

        assertThat(result.getScores()).containsKey(2L);
        verify(chunkRepository).findArticlesByTwoStageSearchWithDomesticFilter(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList());
        verify(chunkRepository, never()).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("카테고리 필터만 → findArticlesByTwoStageSearchWithCategoryFilter 호출")
    void searchByKeywordWithEmbedding_카테고리필터() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearchWithCategoryFilter(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList()))
                .thenReturn(List.of());

        vectorSearchService.searchByKeywordWithEmbedding("java", null, List.of("Backend"));

        verify(chunkRepository).findArticlesByTwoStageSearchWithCategoryFilter(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList());
    }

    @Test
    @DisplayName("국내+카테고리 복합 필터 → findArticlesByTwoStageSearchWithBothFilters 호출")
    void searchByKeywordWithEmbedding_복합필터() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearchWithBothFilters(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList(), anyList()))
                .thenReturn(List.of());

        vectorSearchService.searchByKeywordWithEmbedding("java", List.of(1), List.of("Backend"));

        verify(chunkRepository).findArticlesByTwoStageSearchWithBothFilters(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList(), anyList());
    }

    @Test
    @DisplayName("임베딩 생성 실패 → VectorSearchResult 스코어 빈 맵, 임베딩 null")
    void searchByKeywordWithEmbedding_임베딩생성실패() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.miss(null, 5, 100));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("java");

        assertThat(result.getScores()).isEmpty();
        assertThat(result.getQueryEmbedding()).isNull();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("캐시 히트 시 cacheHit=true 반영")
    void searchByKeywordWithEmbedding_캐시히트() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 3));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("java");

        assertThat(result.isCacheHit()).isTrue();
    }

    @Test
    @DisplayName("무필터 검색 결과 캐시: 같은 키워드 2회 호출 시 임베딩 조회/벡터 쿼리는 1회만 실행")
    void searchByKeywordWithEmbedding_결과캐시_중복실행방지() {
        when(cacheManager.getCache("vectorSearchResults"))
                .thenReturn(new org.springframework.cache.concurrent.ConcurrentMapCache("vectorSearchResults"));
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 0.80}));

        VectorSearchService.VectorSearchResult first =
                vectorSearchService.searchByKeywordWithEmbedding("java");
        VectorSearchService.VectorSearchResult second =
                vectorSearchService.searchByKeywordWithEmbedding("java");

        assertThat(first.getScores()).containsKey(1L);
        assertThat(second.getScores()).isEqualTo(first.getScores());
        verify(searchQueryEmbeddingService, times(1)).getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false));
        verify(chunkRepository, times(1)).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt());
    }

    // ==================== getChunksForArticlesByIds — 임베딩 재사용 ====================

    @Test
    @DisplayName("쿼리 임베딩이 전달되면 임베딩 재조회 없이 chunk 조회")
    void getChunksForArticlesByIds_임베딩전달시_재조회없음() {
        when(chunkRepository.findFirstAndBestChunksByArticleIds(anyString(), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        1L, "제목", "https://example.com/1", "내용", null, null, "테스트기업", null}));

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForArticlesByIds("java", List.of(1L), DUMMY_EMBEDDING);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).articleId()).isEqualTo(1L);
        verifyNoInteractions(searchQueryEmbeddingService);
    }

    // ==================== computeSimilarityForArticles ====================

    @Test
    @DisplayName("null 키워드 → 빈 맵")
    void computeSimilarityForArticles_null키워드() {
        assertThat(vectorSearchService.computeSimilarityForArticles(null, List.of(1L))).isEmpty();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("빈 키워드 → 빈 맵")
    void computeSimilarityForArticles_빈키워드() {
        assertThat(vectorSearchService.computeSimilarityForArticles("", List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("빈 articleIds → 빈 맵")
    void computeSimilarityForArticles_빈articleIds() {
        assertThat(vectorSearchService.computeSimilarityForArticles("java", List.of())).isEmpty();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("임베딩 생성 실패 → 빈 맵")
    void computeSimilarityForArticles_임베딩실패() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(null);

        assertThat(vectorSearchService.computeSimilarityForArticles("spring", List.of(5L))).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("정상 계산 → 스코어 맵 반환")
    void computeSimilarityForArticles_정상계산() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(DUMMY_EMBEDDING);
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{5L, 0.75},
                        new Object[]{6L, 0.63}
                ));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForArticles("spring", List.of(5L, 6L));

        assertThat(result).containsEntry(5L, 0.75).containsEntry(6L, 0.63);
    }

    // ==================== computeSimilarityForArticlesWithEmbedding ====================

    @Test
    @DisplayName("null 임베딩 → 빈 맵")
    void computeSimilarityForArticlesWithEmbedding_null임베딩() {
        assertThat(vectorSearchService.computeSimilarityForArticlesWithEmbedding(null, List.of(1L))).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("null articleIds → 빈 맵")
    void computeSimilarityForArticlesWithEmbedding_null_articleIds() {
        assertThat(vectorSearchService.computeSimilarityForArticlesWithEmbedding(DUMMY_EMBEDDING, null)).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("기본 threshold(0.52) 미만 → 필터링됨")
    void computeSimilarityForArticlesWithEmbedding_threshold미만_제외() {
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{1L, 0.51},   // 미만 → 제외
                        new Object[]{2L, 0.52}    // 경계값 → 포함
                ));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForArticlesWithEmbedding(
                DUMMY_EMBEDDING, List.of(1L, 2L));

        assertThat(result).hasSize(1).containsKey(2L).doesNotContainKey(1L);
    }

    @Test
    @DisplayName("기본 threshold(0.52) 이상 → 모두 포함")
    void computeSimilarityForArticlesWithEmbedding_threshold이상_포함() {
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{7L, 0.85},
                        new Object[]{8L, 0.60}
                ));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForArticlesWithEmbedding(
                DUMMY_EMBEDDING, List.of(7L, 8L));

        assertThat(result).containsEntry(7L, 0.85).containsEntry(8L, 0.60);
    }

    @Test
    @DisplayName("예외 발생 → 빈 맵 반환 (예외 전파 없음)")
    void computeSimilarityForArticlesWithEmbedding_예외발생() {
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB 오류"));

        assertThatNoException().isThrownBy(() ->
                vectorSearchService.computeSimilarityForArticlesWithEmbedding(DUMMY_EMBEDDING, List.of(1L)));
    }

    // ==================== formatVectorForPostgres / toBinaryString (간접 검증) ====================

    @Test
    @DisplayName("임베딩 → PostgreSQL 포맷 변환 후 Repository 호출 (형식 검증)")
    void searchByKeyword_벡터포맷변환검증() {
        float[] embedding = {0.5f, -0.3f, 0.1f};
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(embedding);
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        vectorSearchService.searchByKeyword("test");

        // halfvec 형식: [0.5,-0.3,0.1], binary: 101 (양수→1, 음수→0)
        verify(chunkRepository).findArticlesByTwoStageSearch(
                eq("[0.5,-0.3,0.1]"), eq("101"), anyInt(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("검색 임베딩 조회 중 예외 발생 → 빈 VectorSearchResult 반환 (예외 전파 없음)")
    void searchByKeywordWithEmbedding_임베딩조회예외_빈결과반환() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenThrow(new RuntimeException("embedding api error"));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("java");

        assertThat(result.getScores()).isEmpty();
        assertThat(result.getQueryEmbedding()).isNull();
        verifyNoInteractions(chunkRepository);
    }

    // ==================== Stage 1 후보 점수 (cross-scoring 재활용용) ====================

    @Test
    @DisplayName("is_main=false 행은 본검색 결과에서 제외되고 후보 점수에만 남는다")
    void searchByKeywordWithEmbedding_후보행은_본검색결과에서_제외() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{10L, 0.85, 0.85, true},    // 본검색 결과
                        new Object[]{20L, 0.60, 0.60, false},   // threshold는 통과했으나 limit 밖
                        new Object[]{30L, null, 0.40, false},   // threshold 미만 후보
                        new Object[]{40L, null, null, false}    // 후보 청크가 topK 미만 → 재활용 불가
                ));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("kubernetes");

        assertThat(result.getScores()).containsOnlyKeys(10L).containsEntry(10L, 0.85);
        assertThat(result.getCandidateScores())
                .containsOnlyKeys(10L, 20L, 30L)
                .containsEntry(10L, 0.85)
                .containsEntry(20L, 0.60)
                .containsEntry(30L, 0.40);
    }

    @Test
    @DisplayName("2컬럼 행(구형 결과)도 그대로 처리 — 후보 점수는 본검색 점수와 동일")
    void searchByKeywordWithEmbedding_2컬럼행_하위호환() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 0.85}));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchByKeywordWithEmbedding("kubernetes");

        assertThat(result.getScores()).containsEntry(10L, 0.85);
        assertThat(result.getCandidateScores()).containsEntry(10L, 0.85);
    }

    // ==================== searchForRag ====================

    @Test
    @DisplayName("searchForRag: 공백 쿼리 → 빈 VectorSearchResult, 의존성 미호출")
    void searchForRag_공백쿼리() {
        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchForRag("   ", List.of(), 0.6);

        assertThat(result.getScores()).isEmpty();
        assertThat(result.getQueryEmbedding()).isNull();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("searchForRag: 임베딩 생성 실패 → 빈 VectorSearchResult")
    void searchForRag_임베딩생성실패() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.empty());

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.6);

        assertThat(result.getScores()).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("searchForRag: corporationIds 없음 → findArticlesByTwoStageSearch 호출")
    void searchForRag_corp필터없음() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.6), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 0.8}));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.6);

        assertThat(result.getScores()).containsEntry(10L, 0.8);
        assertThat(result.getQueryEmbedding()).isEqualTo(DUMMY_EMBEDDING);
        verify(chunkRepository, never()).findArticlesByTwoStageSearchWithCorporationFilter(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt(), anyList());
    }

    @Test
    @DisplayName("searchForRag: corporationIds 있음 → findArticlesByTwoStageSearchWithCorporationFilter 호출")
    void searchForRag_corp필터있음() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearchWithCorporationFilter(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.6), anyInt(), eq(List.of(1L, 2L))))
                .thenReturn(List.<Object[]>of(new Object[]{20L, 0.7}));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchForRag("kafka 도입 사례", List.of(1L, 2L), 0.6);

        assertThat(result.getScores()).containsEntry(20L, 0.7);
        verify(chunkRepository, never()).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("searchForRag: Repository 예외 발생 → 빈 VectorSearchResult 반환 (예외 전파 없음)")
    void searchForRag_예외발생() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("DB 오류"));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.6);

        assertThat(result.getScores()).isEmpty();
        assertThat(result.getQueryEmbedding()).isNull();
    }

    @Test
    @DisplayName("searchForRag: useMockEmbedding=true → getEmbeddingWithCacheInfo에 그대로 전달")
    void searchForRag_useMockEmbeddingTrue() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(true)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.6, true);

        verify(searchQueryEmbeddingService).getEmbeddingWithCacheInfo(anyString(), isNull(), eq(true));
    }

    @Test
    @DisplayName("searchForRag: threshold 파라미터가 Repository 호출에 그대로 전달")
    void searchForRag_threshold전달() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.75), anyInt()))
                .thenReturn(List.of());

        vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.75);

        verify(chunkRepository).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.75), anyInt());
    }

    /**
     * 부하테스트 경로 회귀 가드 — mock 임베딩은 의사난수 단위벡터라 RAG 운영 임계값(0.6)에서는
     * 벡터 팔이 항상 0건이 되어 NSF가 BM25 단독으로 축퇴한다. 검색 경로가 이미 같은 이유로
     * 임계값·결과 수 상한을 갈아끼우는데 RAG 경로만 빠져 있었다.
     */
    @Test
    @DisplayName("searchForRag: useMockEmbedding=true → 요청 임계값 0.6이 아닌 부하테스트 값(0.0/30)을 쓴다")
    void searchForRag_부하테스트경로는임계값과결과수상한을갈아끼운다() {
        ReflectionTestUtils.setField(vectorSearchService, "loadTestVectorThreshold", 0.0);
        ReflectionTestUtils.setField(vectorSearchService, "loadTestMaxVectorResults", 30);
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(true)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.0), eq(30)))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 0.03}));

        VectorSearchService.VectorSearchResult result =
                vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.6, true);

        // 요청 임계값 0.6으로 호출됐다면 mock 벡터(유사도 ≈ ±0.03)는 0건이 됐을 것이다
        assertThat(result.getScores()).containsEntry(10L, 0.03);
        verify(chunkRepository).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.0), eq(30));
    }

    @Test
    @DisplayName("searchForRag: useMockEmbedding=false → 요청 임계값과 DEFAULT_MAX_RESULTS(100) 그대로 (실사용자 경로 불변)")
    void searchForRag_실사용자경로는요청값을유지한다() {
        ReflectionTestUtils.setField(vectorSearchService, "loadTestVectorThreshold", 0.0);
        ReflectionTestUtils.setField(vectorSearchService, "loadTestMaxVectorResults", 30);
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.6), eq(100)))
                .thenReturn(List.of());

        vectorSearchService.searchForRag("kafka 도입 사례", List.of(), 0.6, false);

        verify(chunkRepository).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.6), eq(100));
    }

    @Test
    @DisplayName("vectorThresholdFor(useMock, requested): 부하테스트만 갈아끼우고 실사용자는 요청값 유지")
    void vectorThresholdFor_요청값오버로드() {
        ReflectionTestUtils.setField(vectorSearchService, "loadTestVectorThreshold", 0.0);

        assertThat(vectorSearchService.vectorThresholdFor(true, 0.6)).isEqualTo(0.0);
        assertThat(vectorSearchService.vectorThresholdFor(false, 0.6)).isEqualTo(0.6);
        // 1-arg 오버로드는 종전대로 일반 검색 기본 임계값(0.52)
        assertThat(vectorSearchService.vectorThresholdFor(false)).isEqualTo(0.52);
    }

    // ==================== computeSimilarityForArticlesWithEmbedding — 커스텀 threshold ====================

    @Test
    @DisplayName("커스텀 threshold(0.6) 미만 결과는 제외 (기본 threshold와 다르게 동작)")
    void computeSimilarityForArticlesWithEmbedding_커스텀threshold() {
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{1L, 0.55},   // 기본 threshold(0.52)는 넘지만 커스텀(0.6) 미만 → 제외
                        new Object[]{2L, 0.65}    // 커스텀 threshold 이상 → 포함
                ));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForArticlesWithEmbedding(
                DUMMY_EMBEDDING, List.of(1L, 2L), 0.6);

        assertThat(result).hasSize(1).containsKey(2L).doesNotContainKey(1L);
    }

    // ==================== computeSimilarityForSearchCrossScoring — 퍼널 스위치 ====================

    @Test
    @DisplayName("스위치 off면 종전 단일 쿼리를 탄다 (롤백 경로)")
    void 검색_crossScoring_스위치_off면_단일쿼리() {
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringTwoStage", false);
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyString(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 0.70}));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForSearchCrossScoring(
                DUMMY_EMBEDDING, List.of(1L), 0.52);

        assertThat(result).containsEntry(1L, 0.70);
        verify(chunkRepository, never()).computeSimilarityForArticleIdsTwoStage(
                anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("스위치 on이면 퍼널 쿼리를 타고, 하한은 임계값에서 여유를 뺀 값이다")
    void 검색_crossScoring_스위치_on이면_퍼널쿼리() {
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringTwoStage", true);
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringStage2Limit", 20);
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringStage1FloorMargin", 0.25);
        when(chunkRepository.computeSimilarityForArticleIdsTwoStage(
                anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 0.70}));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForSearchCrossScoring(
                DUMMY_EMBEDDING, List.of(1L), 0.52);

        assertThat(result).containsEntry(1L, 0.70);
        verify(chunkRepository).computeSimilarityForArticleIdsTwoStage(
                anyString(), anyString(), anyString(), eq(3), eq(0.27), eq(20));
        verify(chunkRepository, never()).computeSimilarityForArticleIds(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("퍼널 경로도 임계값 필터를 그대로 적용한다 (컷과 별개)")
    void 검색_crossScoring_퍼널도_임계값_필터_유지() {
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringTwoStage", true);
        when(chunkRepository.computeSimilarityForArticleIdsTwoStage(
                anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{1L, 0.45},   // Stage 2까지 왔지만 임계값 미만 → 제외
                        new Object[]{2L, 0.65}
                ));

        Map<Long, Double> result = vectorSearchService.computeSimilarityForSearchCrossScoring(
                DUMMY_EMBEDDING, List.of(1L, 2L), 0.52);

        assertThat(result).containsOnlyKeys(2L);
    }

    @Test
    @DisplayName("부하테스트 임계값 0.0이면 하한이 음수로 내려가 Stage 1 필터가 해제된다")
    void 검색_crossScoring_부하테스트_임계값이면_하한_해제() {
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringTwoStage", true);
        ReflectionTestUtils.setField(vectorSearchService, "crossScoringStage1FloorMargin", 0.25);
        when(chunkRepository.computeSimilarityForArticleIdsTwoStage(
                anyString(), anyString(), anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        vectorSearchService.computeSimilarityForSearchCrossScoring(DUMMY_EMBEDDING, List.of(1L), 0.0);

        verify(chunkRepository).computeSimilarityForArticleIdsTwoStage(
                anyString(), anyString(), anyString(), anyInt(), eq(-0.25), anyInt());
    }

    // ==================== getChunksForRag / getChunksForRagCached ====================

    @Test
    @DisplayName("getChunksForRag: 빈 articleIds → 빈 리스트, 의존성 미호출")
    void getChunksForRag_빈articleIds() {
        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForRag("query", List.of(), DUMMY_EMBEDDING, 3);

        assertThat(chunks).isEmpty();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("getChunksForRag: queryEmbedding 전달되면 임베딩 재조회 없이 chunk 조회")
    void getChunksForRag_임베딩전달시_재조회없음() {
        when(chunkRepository.findFirstAndTopChunksByArticleIds(anyString(), anyList(), eq(3)))
                .thenReturn(List.<Object[]>of(new Object[]{
                        1L, "제목", "https://example.com/1", "내용", null, null, "테스트기업", null}));

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForRag("kafka", List.of(1L), DUMMY_EMBEDDING, 3);

        assertThat(chunks).hasSize(1);
        verifyNoInteractions(searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("getChunksForRag: queryEmbedding null → 임베딩 조회 후 chunk 조회 (fallback)")
    void getChunksForRag_임베딩null_fallback조회() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.hit(DUMMY_EMBEDDING, 5));
        when(chunkRepository.findFirstAndTopChunksByArticleIds(anyString(), anyList(), eq(3)))
                .thenReturn(List.<Object[]>of(new Object[]{
                        1L, "제목", "https://example.com/1", "내용", null, null, "테스트기업", null}));

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForRag("kafka", List.of(1L), null, 3);

        assertThat(chunks).hasSize(1);
        verify(searchQueryEmbeddingService).getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false));
    }

    @Test
    @DisplayName("getChunksForRag: 임베딩 fallback 조회도 실패 → 빈 리스트")
    void getChunksForRag_임베딩fallback실패() {
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.empty());

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForRag("kafka", List.of(1L), null, 3);

        assertThat(chunks).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    @DisplayName("getChunksForRagCached: 캐시 히트 시 2번째 호출부터 chunkRepository 미호출")
    void getChunksForRagCached_캐시히트() {
        org.springframework.cache.concurrent.ConcurrentMapCache cache =
                new org.springframework.cache.concurrent.ConcurrentMapCache("chunkSearchResults");
        when(cacheManager.getCache("chunkSearchResults")).thenReturn(cache);
        when(chunkRepository.findFirstAndTopChunksByArticleIds(anyString(), anyList(), eq(3)))
                .thenReturn(List.<Object[]>of(new Object[]{
                        1L, "제목", "https://example.com/1", "내용", null, null, "테스트기업", null}));

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> first =
                vectorSearchService.getChunksForRagCached("kafka", List.of(1L), DUMMY_EMBEDDING, 3);
        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> second =
                vectorSearchService.getChunksForRagCached("kafka", List.of(1L), DUMMY_EMBEDDING, 3);

        assertThat(second).isEqualTo(first);
        verify(chunkRepository, times(1)).findFirstAndTopChunksByArticleIds(anyString(), anyList(), eq(3));
    }

    // ==================== getChunksForArticlesByIds — 추가 분기 ====================

    @Test
    @DisplayName("getChunksForArticlesByIds: null articleIds → 빈 리스트, 의존성 미호출")
    void getChunksForArticlesByIds_nullArticleIds() {
        assertThat(vectorSearchService.getChunksForArticlesByIds("kafka", null)).isEmpty();
        verifyNoInteractions(chunkRepository, searchQueryEmbeddingService);
    }

    @Test
    @DisplayName("getChunksForArticlesByIds: queryEmbedding 미전달 → getOrCreateEmbedding으로 fallback 조회")
    void getChunksForArticlesByIds_임베딩미전달_fallback조회() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(DUMMY_EMBEDDING);
        when(chunkRepository.findFirstAndBestChunksByArticleIds(anyString(), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        1L, "제목", "https://example.com/1", "내용", null, null, "테스트기업", null}));

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForArticlesByIds("kafka", List.of(1L));

        assertThat(chunks).hasSize(1);
        verify(searchQueryEmbeddingService).getOrCreateEmbedding("kafka");
    }

    @Test
    @DisplayName("getChunksForArticlesByIds: fallback 임베딩 조회도 실패 → 빈 리스트")
    void getChunksForArticlesByIds_fallback임베딩실패() {
        when(searchQueryEmbeddingService.getOrCreateEmbedding(anyString())).thenReturn(null);

        List<com.newcodes7.small_town.search.dto.AiSummaryChunkDto> chunks =
                vectorSearchService.getChunksForArticlesByIds("kafka", List.of(1L));

        assertThat(chunks).isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    // ===== 부하테스트 경로 전용 파라미터 (임계값/결과 수 상한) =====

    @Test
    @DisplayName("부하테스트 경로는 완화된 임계값과 축소된 결과 수 상한을 쓴다")
    void loadTestPath_usesRelaxedThresholdAndCappedMaxResults() {
        ReflectionTestUtils.setField(vectorSearchService, "loadTestVectorThreshold", 0.0);
        ReflectionTestUtils.setField(vectorSearchService, "loadTestMaxVectorResults", 30);

        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(true)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.miss(
                        new float[]{0.1f, 0.2f}, 1L, 1L));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        vectorSearchService.searchByKeywordWithEmbedding("kafka redis", null, null, true);

        // threshold=0.0, maxResults=30으로 2단계 검색이 호출된다
        verify(chunkRepository).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.0), eq(30));
    }

    @Test
    @DisplayName("실사용자 경로는 운영 임계값 0.52와 결과 수 100을 그대로 쓴다")
    void productionPath_keepsDefaultThresholdAndMaxResults() {
        ReflectionTestUtils.setField(vectorSearchService, "loadTestVectorThreshold", 0.0);
        ReflectionTestUtils.setField(vectorSearchService, "loadTestMaxVectorResults", 30);

        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull(), eq(false)))
                .thenReturn(SearchQueryEmbeddingService.CachedEmbeddingResult.miss(
                        new float[]{0.1f, 0.2f}, 1L, 1L));
        when(chunkRepository.findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(List.of());

        vectorSearchService.searchByKeywordWithEmbedding("kafka redis", null, null, false);

        verify(chunkRepository).findArticlesByTwoStageSearch(
                anyString(), anyString(), anyInt(), anyInt(), eq(0.52), eq(100));
    }
}
