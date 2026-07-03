package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

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

    @Mock
    private ExecutorService searchExecutor;

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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        when(searchQueryEmbeddingService.getEmbeddingWithCacheInfo(anyString(), isNull()))
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
        verify(searchQueryEmbeddingService, times(1)).getEmbeddingWithCacheInfo(anyString(), isNull());
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
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyList(), anyInt()))
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
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyList(), anyInt()))
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
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyList(), anyInt()))
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
        when(chunkRepository.computeSimilarityForArticleIds(anyString(), anyList(), anyInt()))
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
}
