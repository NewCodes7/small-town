package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.service.ArticleEmbeddingService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.like.service.UserLikeService;
import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import com.newcodes7.small_town.search.scorer.HybridSearchScorer;
import com.newcodes7.small_town.term.repository.ArticleTermRepository;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.service.TermSynonymService;
import io.micrometer.observation.ObservationRegistry;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * ArticleSearchService 단위 테스트
 *
 * Hybrid 검색(BM25 + Vector), 따옴표 검색, Term 검색, 필터/정렬/페이징,
 * 좋아요 상태 분기, 교차 점수 보충 등 핵심 비즈니스 로직 검증
 */
@ExtendWith(MockitoExtension.class)
class ArticleSearchServiceTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private ArticleTermRepository articleTermRepository;
    @Mock private TermRepository termRepository;
    @Mock private HybridSearchScorer hybridSearchScorer;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private SemanticTermExpansionService semanticExpansionService;
    @Mock private ArticleEmbeddingService embeddingService;
    @Mock private TermSynonymService termSynonymService;
    @Mock private UserLikeService userLikeService;
    @Mock private MorphemeAnalyzer morphemeAnalyzer;
    @Mock private SearchWeightConfigService weightConfig;
    @Mock private org.springframework.cache.CacheManager cacheManager;
    @Mock private PlatformTransactionManager transactionManager;

    private ArticleSearchService articleSearchService;

    // 동기 executor: 테스트에서 CompletableFuture.supplyAsync가 즉시 실행되도록
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute()가 실제 트랜잭션 없이도 콜백을 실행하도록 최소 스텁
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        articleSearchService = new ArticleSearchService(
                articleRepository,
                articleTermRepository,
                termRepository,
                hybridSearchScorer,
                vectorSearchService,
                semanticExpansionService,
                embeddingService,
                termSynonymService,
                userLikeService,
                morphemeAnalyzer,
                syncExecutor,
                weightConfig,
                cacheManager,
                ObservationRegistry.create(),
                transactionManager
        );
    }

    // ===== searchArticlesHybrid 테스트 =====

    @Test
    @DisplayName("searchArticlesHybrid: null 키워드 → 빈 페이지 반환")
    void searchArticlesHybrid_nullKeyword_returnsEmpty() {
        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                null, List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(semanticExpansionService, vectorSearchService, hybridSearchScorer);
    }

    @Test
    @DisplayName("searchArticlesHybrid: 빈 문자열 키워드 → 빈 페이지 반환")
    void searchArticlesHybrid_emptyKeyword_returnsEmpty() {
        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                "   ", List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchArticlesHybrid: BM25 쿼리 생성 실패 → 빈 페이지 반환")
    void searchArticlesHybrid_bm25QueryBuildFails_returnsEmpty() {
        // given
        when(semanticExpansionService.classifyQueryComplexity("redis"))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));
        when(semanticExpansionService.expandSearchTerms("redis")).thenReturn(Collections.emptyMap());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                "redis", List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchArticlesHybrid: BM25+Vector 모두 결과 있을 때 NSF 스코어 기반 정렬")
    void searchArticlesHybrid_hybridResults_returnsNSFSorted() {
        // given
        String keyword = "redis";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of("redis", 1.0, "캐시", 0.8);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);

        String bm25Query = "title_terms:redis^6.0 OR content_terms:redis^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        // BM25 결과
        List<Object[]> bm25RawResults = List.of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, 5.0, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(bm25RawResults);

        // Vector 결과
        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        VectorSearchService.VectorSearchResult vectorResult =
                new VectorSearchService.VectorSearchResult(Map.of(1L, 0.9, 3L, 0.7), dummyEmbedding);
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(vectorResult);

        // 교차 점수 보충 (빈 결과)
        when(vectorSearchService.computeSimilarityForSearchCrossScoring(any(float[].class), anyList(), anyDouble()))
                .thenReturn(Map.of());
        when(articleRepository.computeBM25ScoreForArticleIds(eq(bm25Query), anyList()))
                .thenReturn(Collections.emptyList());

        // NSF 계산
        Map<Long, Double> nsfScores = Map.of(1L, 0.95, 2L, 0.25, 3L, 0.35);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores,
                Map.of(1L, 1.0, 2L, 0.0),
                Map.of(1L, 1.0, 3L, 0.0),
                Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0)
        );
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);

        // valid article IDs
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)},
                new Object[]{3L, LocalDateTime.of(2024, 3, 1, 0, 0)}
        ));

        // Article 조회
        Corporation corp = Corporation.builder().name("Test Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Redis 입문", corp);
        Article a2 = createArticle(2L, "캐시 전략", corp);
        Article a3 = createArticle(3L, "메모리 DB", corp);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(a1, a2, a3));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), eq("127.0.0.1")))
                .thenReturn(Map.of(1L, true, 2L, false, 3L, false));

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isNotEmpty();
        assertThat(result.getTotalElements()).isEqualTo(3);
        // 첫 번째 결과는 NSF 스코어가 가장 높은 article 1
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("searchArticlesHybrid: latest 정렬 → 발행일 내림차순")
    void searchArticlesHybrid_latestSort_orderedByDateDesc() {
        // given
        String keyword = "test";
        setupBasicHybridSearch(keyword);

        Map<Long, Double> nsfScores = Map.of(1L, 0.8, 2L, 0.6);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(1L, 1.0, 2L, 0.0), Map.of(), Map.of(1L, 1.0, 2L, 1.0));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), anyDouble(), anyDouble()))
                .thenReturn(nsfResult);

        // Article 1은 오래된 글, Article 2는 최신 글
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2023, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Old", corp);
        Article a2 = createArticle(2L, "New", corp);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(a1, a2));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of(), List.of(), 0, 10, "latest", "127.0.0.1", null);

        // then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getId()).isEqualTo(2L); // 최신
    }

    @Test
    @DisplayName("searchArticlesHybrid: 페이지 오프셋이 결과 수 초과 시 빈 페이지")
    void searchArticlesHybrid_offsetExceedsTotalResults_returnsEmpty() {
        // given
        String keyword = "test";
        setupBasicHybridSearch(keyword);

        Map<Long, Double> nsfScores = Map.of(1L, 0.8);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(1L, 1.0), Map.of(), Map.of(1L, 1.0));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), anyDouble(), anyDouble()))
                .thenReturn(nsfResult);

        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)}
        ));

        // when: page=5, size=10 → offset=50 > 1
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of(), List.of(), 5, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
    }

    // ===== computeHybridCore (필터 경유 직접 호출) 테스트 =====
    // regions/category가 비어있지 않으면 searchArticlesHybrid가 getHybridCoreShared의
    // single-flight 캐시를 건너뛰고 computeHybridCore를 직접·동기 호출한다 (캐시 설정 불필요)

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + BM25 쿼리 생성 실패(의미 확장 결과 없음) → 빈 페이지")
    void searchArticlesHybrid_withFilters_bm25QueryBuildFails_returnsEmpty() {
        // given
        when(semanticExpansionService.classifyQueryComplexity("redis"))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));
        when(semanticExpansionService.expandSearchTerms("redis")).thenReturn(Collections.emptyMap());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                "redis", List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(vectorSearchService);
    }

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + Vector 검색 예외 → BM25 단독 결과로 폴백")
    void searchArticlesHybrid_withFilters_vectorFutureThrows_fallsBackToBm25Only() {
        // given
        String keyword = "redis";
        List<Object[]> bm25RawResults = List.of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, 5.0, LocalDateTime.of(2024, 2, 1, 0, 0)}
        );
        setupHybridSearchWithDomesticFilter(keyword, bm25RawResults);

        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenThrow(new RuntimeException("vector down"));

        Map<Long, Double> nsfScores = Map.of(1L, 0.8, 2L, 0.5);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(1L, 1.0, 2L, 0.0), Map.of(), Map.of(1L, 1.0, 2L, 1.0));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 2, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(
                createArticle(1L, "Redis 입문", corp), createArticle(2L, "캐시 전략", corp)));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then: Vector 결과 없이도 BM25 단독으로 정상 반환
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).allSatisfy(dto -> assertThat(dto.getVectorScore()).isNull());
        verify(vectorSearchService, never()).computeSimilarityForSearchCrossScoring(any(float[].class), anyList(), anyDouble());
    }

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + cross-scoring — embedding 있으면 BM25-only id에 Vector 점수 보충")
    void searchArticlesHybrid_withFilters_bm25OnlyIds_supplementedWithVectorWhenEmbeddingPresent() {
        // given
        String keyword = "redis";
        List<Object[]> bm25RawResults = List.of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, 5.0, LocalDateTime.of(2024, 2, 1, 0, 0)}
        );
        setupHybridSearchWithDomesticFilter(keyword, bm25RawResults);

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(3L, 0.7), dummyEmbedding));
        when(vectorSearchService.computeSimilarityForSearchCrossScoring(eq(dummyEmbedding), anyList(), anyDouble()))
                .thenReturn(Map.of(1L, 0.4));

        Map<Long, Double> nsfScores = Map.of(1L, 0.8, 2L, 0.3, 3L, 0.5);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(), Map.of(), Map.of());
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 2, 1, 0, 0)},
                new Object[]{3L, LocalDateTime.of(2024, 3, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(
                createArticle(1L, "Redis", corp), createArticle(2L, "Cache", corp), createArticle(3L, "DB", corp)));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then: BM25-only였던 id 1이 Vector 보충 점수를 받음
        assertThat(result.getContent())
                .filteredOn(dto -> dto.getId().equals(1L))
                .first()
                .satisfies(dto -> assertThat(dto.getVectorScore()).isEqualTo(0.4));
        verify(vectorSearchService).computeSimilarityForSearchCrossScoring(
                eq(dummyEmbedding), argThat(ids -> ids.containsAll(List.of(1L, 2L))), anyDouble());
    }

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + cross-scoring — embedding 없으면 Vector 보충 스킵")
    void searchArticlesHybrid_withFilters_bm25OnlyIds_notSupplementedWhenEmbeddingNull() {
        // given
        String keyword = "redis";
        List<Object[]> bm25RawResults = List.<Object[]>of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}
        );
        setupHybridSearchWithDomesticFilter(keyword, bm25RawResults);

        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));

        Map<Long, Double> nsfScores = Map.of(1L, 0.6);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(), Map.of(), Map.of());
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList()))
                .thenReturn(List.of(createArticle(1L, "Redis", corp)));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        verify(vectorSearchService, never()).computeSimilarityForSearchCrossScoring(any(float[].class), anyList(), anyDouble());
    }

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + cross-scoring — Vector-only id에 BM25 점수 보충")
    void searchArticlesHybrid_withFilters_vectorOnlyIds_supplementedWithBm25() {
        // given
        String keyword = "redis";
        List<Object[]> bm25RawResults = List.<Object[]>of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}
        );
        String bm25Query = setupHybridSearchWithDomesticFilter(keyword, bm25RawResults);

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(2L, 0.9), dummyEmbedding));
        when(vectorSearchService.computeSimilarityForSearchCrossScoring(eq(dummyEmbedding), anyList(), anyDouble()))
                .thenReturn(Map.of());
        when(articleRepository.computeBM25ScoreForArticleIdsWithDomesticTypes(eq(bm25Query), eq(List.of(1)), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 3.0, LocalDateTime.of(2024, 2, 1, 0, 0)}));

        Map<Long, Double> nsfScores = Map.of(1L, 0.5, 2L, 0.7);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(), Map.of(), Map.of());
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 2, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(
                createArticle(1L, "Redis", corp), createArticle(2L, "Cache", corp)));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then: Vector-only였던 id 2가 BM25 보충 점수를 받음
        assertThat(result.getContent())
                .filteredOn(dto -> dto.getId().equals(2L))
                .first()
                .satisfies(dto -> assertThat(dto.getBm25Score()).isEqualTo(3.0));
    }

    @Test
    @DisplayName("searchArticlesHybrid: region+category 모두 지정 → searchByBM25WithBothFilters 사용")
    void searchArticlesHybrid_withFilters_bothRegionAndCategory_usesBothFiltersQuery() {
        // given
        String keyword = "redis";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));
        Map<String, Double> expandedTerms = Map.of(keyword, 1.0);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:" + keyword + "^6.0 OR content_terms:" + keyword + "^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);
        when(articleRepository.searchByBM25WithBothFilters(eq(bm25Query), eq(List.of(1)), eq(List.of("공지")), eq(100)))
                .thenReturn(Collections.emptyList());
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of("공지"), 0, 10, "relevance", "127.0.0.1", null);

        // then
        verify(articleRepository).searchByBM25WithBothFilters(bm25Query, List.of(1), List.of("공지"), 100);
        verify(articleRepository, never()).searchByBM25WithDomesticTypes(anyString(), anyList(), anyInt());
        verify(articleRepository, never()).searchByBM25WithCategory(anyString(), anyList(), anyInt());
        verify(articleRepository, never()).searchByBM25(anyString(), anyInt());
    }

    @Test
    @DisplayName("searchArticlesHybrid: region만 지정 → searchByBM25WithDomesticTypes 사용")
    void searchArticlesHybrid_withFilters_regionOnly_usesDomesticTypesQuery() {
        // given
        String keyword = "redis";
        setupHybridSearchWithDomesticFilter(keyword, Collections.emptyList());
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        verify(articleRepository).searchByBM25WithDomesticTypes(anyString(), eq(List.of(1)), eq(100));
        verify(articleRepository, never()).searchByBM25WithBothFilters(anyString(), anyList(), anyList(), anyInt());
        verify(articleRepository, never()).searchByBM25WithCategory(anyString(), anyList(), anyInt());
        verify(articleRepository, never()).searchByBM25(anyString(), anyInt());
    }

    @Test
    @DisplayName("searchArticlesHybrid: category만 지정 → searchByBM25WithCategory 사용")
    void searchArticlesHybrid_withFilters_categoryOnly_usesCategoryQuery() {
        // given
        String keyword = "redis";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));
        Map<String, Double> expandedTerms = Map.of(keyword, 1.0);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:" + keyword + "^6.0 OR content_terms:" + keyword + "^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);
        when(articleRepository.searchByBM25WithCategory(eq(bm25Query), eq(List.of("공지")), eq(100)))
                .thenReturn(Collections.emptyList());
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, List.of(), List.of("공지"), 0, 10, "relevance", "127.0.0.1", null);

        // then
        verify(articleRepository).searchByBM25WithCategory(bm25Query, List.of("공지"), 100);
        verify(articleRepository, never()).searchByBM25WithBothFilters(anyString(), anyList(), anyList(), anyInt());
        verify(articleRepository, never()).searchByBM25WithDomesticTypes(anyString(), anyList(), anyInt());
        verify(articleRepository, never()).searchByBM25(anyString(), anyInt());
    }

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + BM25/Vector 모두 결과 없음(cross-scoring 후에도) → 빈 페이지")
    void searchArticlesHybrid_withFilters_allNsfScoresEmpty_returnsEmptyPage() {
        // given
        String keyword = "redis";
        setupHybridSearchWithDomesticFilter(keyword, Collections.emptyList());
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(userLikeService);
    }

    @Test
    @DisplayName("searchArticlesHybrid: 필터 있음 + stale article(삭제됨) → 최종 결과에서 제외")
    void searchArticlesHybrid_withFilters_staleArticleExcludedFromValidIds() {
        // given
        String keyword = "redis";
        List<Object[]> bm25RawResults = List.of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, 5.0, LocalDateTime.of(2024, 2, 1, 0, 0)}
        );
        setupHybridSearchWithDomesticFilter(keyword, bm25RawResults);
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));

        Map<Long, Double> nsfScores = Map.of(1L, 0.8, 2L, 0.5);
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(nsfScores, Map.of(), Map.of(), Map.of()));
        // id 2는 삭제되어 DB에서 조회되지 않음 (stale)
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.<Object[]>of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList()))
                .thenReturn(List.of(createArticle(1L, "Redis", corp)));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesHybrid(
                keyword, List.of("domestic"), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(ArticleSearchResultDto::getId).containsExactly(1L);
    }

    // ===== searchArticlesExactMatch 테스트 =====

    @Test
    @DisplayName("searchArticlesExactMatch: null 키워드 → 빈 페이지")
    void searchArticlesExactMatch_nullKeyword_returnsEmpty() {
        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                null, List.of(), List.of(), 0, 10, "latest", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchArticlesExactMatch: ILIKE 결과 없음 → 빈 페이지")
    void searchArticlesExactMatch_noILIKEResults_returnsEmpty() {
        // given
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "nonexistent", List.of(), List.of(), 0, 10, "latest", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchArticlesExactMatch: relevance 정렬 + Vector 실패 → 날짜순 fallback")
    void searchArticlesExactMatch_vectorFails_fallbackToDateSort() {
        // given
        List<Object[]> ilikeResults = List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        // Vector embedding 생성 실패
        when(embeddingService.generateEmbedding("redis")).thenThrow(new RuntimeException("API error"));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Redis 입문", corp);
        Article a2 = createArticle(2L, "Redis 캐시", corp);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(a1, a2));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "redis", List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then: Vector 실패 시 vectorScores가 비어있어 날짜순 fallback
        assertThat(result).isNotEmpty();
        assertThat(result.getContent().get(0).getId()).isEqualTo(2L); // 최신
    }

    @Test
    @DisplayName("searchArticlesExactMatch: oldest 정렬 → 발행일 오름차순")
    void searchArticlesExactMatch_oldestSort_orderedByDateAsc() {
        // given
        List<Object[]> ilikeResults = List.of(
                new Object[]{1L, LocalDateTime.of(2023, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Old", corp);
        Article a2 = createArticle(2L, "New", corp);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(a1, a2));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "test", List.of(), List.of(), 0, 10, "oldest", "127.0.0.1", null);

        // then
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L); // 오래된 글 먼저
    }

    @Test
    @DisplayName("searchArticlesExactMatch: 공백 키워드 → 빈 페이지, 상호작용 없음")
    void searchArticlesExactMatch_blankKeyword_returnsEmpty() {
        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "   ", List.of(), List.of(), 0, 10, "latest", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(articleRepository, vectorSearchService, embeddingService);
    }

    @Test
    @DisplayName("searchArticlesExactMatch: sort=null(기본값) → 발행일 내림차순")
    void searchArticlesExactMatch_defaultSort_orderedByDateDesc() {
        // given
        List<Object[]> ilikeResults = List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Old", corp);
        Article a2 = createArticle(2L, "New", corp);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(a1, a2));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "test", List.of(), List.of(), 0, 10, null, "127.0.0.1", null);

        // then
        assertThat(result.getContent().get(0).getId()).isEqualTo(2L); // 최신
        verifyNoInteractions(embeddingService);
    }

    @Test
    @DisplayName("searchArticlesExactMatch: relevance 정렬 + Vector 유사도 계산 성공 → 유사도 내림차순")
    void searchArticlesExactMatch_relevanceSort_successPath_orderedByVectorScoreDesc() {
        // given
        List<Object[]> ilikeResults = List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(embeddingService.generateEmbedding("redis")).thenReturn(dummyEmbedding);
        when(vectorSearchService.computeSimilarityForArticlesWithEmbedding(dummyEmbedding, List.of(1L, 2L)))
                .thenReturn(Map.of(1L, 0.3, 2L, 0.9));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Redis 입문", corp);
        Article a2 = createArticle(2L, "Redis 캐시", corp);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(a1, a2));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "redis", List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then: 유사도가 더 높은 article 2가 먼저
        assertThat(result.getContent().get(0).getId()).isEqualTo(2L);
        assertThat(result.getContent().get(0).getVectorScore()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("searchArticlesExactMatch: 페이지 오프셋이 결과 수 초과 시 빈 페이지")
    void searchArticlesExactMatch_offsetExceedsTotalResults_returnsEmpty() {
        // given
        List<Object[]> ilikeResults = List.<Object[]>of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList()))
                .thenReturn(List.of(createArticle(1L, "Redis", corp)));

        // when: page=5, size=10 → offset=50 > 1
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "redis", List.of(), List.of(), 5, 10, "latest", "127.0.0.1", null);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(userLikeService);
    }

    @Test
    @DisplayName("searchArticlesExactMatch: 소프트 삭제된 article은 ILIKE 매칭돼도 결과에서 제외")
    void searchArticlesExactMatch_softDeletedArticleExcluded() {
        // given
        List<Object[]> ilikeResults = List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article active = createArticle(1L, "Redis", corp);
        Article deleted = createArticle(2L, "Redis 삭제됨", corp);
        deleted.softDelete();
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(active, deleted));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), any())).thenReturn(Map.of());

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "redis", List.of(), List.of(), 0, 10, "latest", "127.0.0.1", null);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(ArticleSearchResultDto::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("searchArticlesExactMatch: 로그인 유저(username) → UserLikeService batch-by-user 경로")
    void searchArticlesExactMatch_loggedInUsername_usesUserLikeServiceBatchByUser() {
        // given
        List<Object[]> ilikeResults = List.<Object[]>of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)}
        );
        when(articleRepository.findArticleIdsWithPublishedAtByFilters(
                anyString(), anyList(), anyInt(), anyList(), anyInt()))
                .thenReturn(ilikeResults);

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList()))
                .thenReturn(List.of(createArticle(1L, "Redis", corp)));
        when(userLikeService.getLikeStatusBatchByUser(anyList(), eq("testuser")))
                .thenReturn(Map.of(1L, true));

        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesExactMatch(
                "redis", List.of(), List.of(), 0, 10, "latest", "127.0.0.1", "testuser");

        // then
        assertThat(result.getContent().get(0).getIsLiked()).isTrue();
        verify(userLikeService).getLikeStatusBatchByUser(anyList(), eq("testuser"));
        verify(userLikeService, never()).getLikeStatusBatchByIp(any(), any());
    }

    // ===== searchArticlesWithExpandedTerms 테스트 =====

    @Test
    @DisplayName("searchArticlesWithExpandedTerms: null expandedTerms → 빈 페이지")
    void searchArticlesWithExpandedTerms_nullTerms_returnsEmpty() {
        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesWithExpandedTerms(
                null, List.of(), List.of(), 0, 10, "relevance");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchArticlesWithExpandedTerms: 빈 expandedTerms → 빈 페이지")
    void searchArticlesWithExpandedTerms_emptyTerms_returnsEmpty() {
        // when
        Page<ArticleSearchResultDto> result = articleSearchService.searchArticlesWithExpandedTerms(
                Map.of(), List.of(), List.of(), 0, 10, "relevance");

        // then
        assertThat(result).isEmpty();
    }

    // ===== searchByTermWithSynonyms 테스트 =====

    @Test
    @DisplayName("searchByTermWithSynonyms: term 없음 → 빈 페이지")
    void searchByTermWithSynonyms_termNotFound_returnsEmpty() {
        // given
        when(termRepository.findByTermAndTermType("unknown", "NNG")).thenReturn(Optional.empty());

        // when
        Page<Article> result = articleSearchService.searchByTermWithSynonyms(
                "unknown", PageRequest.of(0, 10));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchByTermWithSynonyms: 유의어 포함 검색 → 삭제된 article 제외")
    void searchByTermWithSynonyms_withSynonyms_excludesDeleted() {
        // given
        Term term = Term.builder().term("redis").termType("NNG").build();
        ReflectionTestUtils.setField(term, "id", 1L);
        when(termRepository.findByTermAndTermType("redis", "NNG")).thenReturn(Optional.of(term));
        when(termSynonymService.getSynonymTermIds(1L)).thenReturn(List.of(1L, 2L));
        when(articleTermRepository.findArticleIdsByTermIds(List.of(1L, 2L))).thenReturn(List.of(1L, 2L, 3L));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Active 1", corp);
        Article a2 = createArticle(2L, "Active 2", corp);
        Article a3 = createArticle(3L, "Deleted", corp);
        a3.setDeletedAt(LocalDateTime.now());

        when(articleRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(a1, a2, a3));

        // when
        Page<Article> result = articleSearchService.searchByTermWithSynonyms(
                "redis", PageRequest.of(0, 10));

        // then: 삭제된 article 제외
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).extracting(Article::getId).doesNotContain(3L);
    }

    @Test
    @DisplayName("searchByTermWithSynonyms: articleIds 비어있음 → 빈 페이지")
    void searchByTermWithSynonyms_noArticles_returnsEmpty() {
        // given
        Term term = Term.builder().term("rare").termType("NNG").build();
        ReflectionTestUtils.setField(term, "id", 1L);
        when(termRepository.findByTermAndTermType("rare", "NNG")).thenReturn(Optional.of(term));
        when(termSynonymService.getSynonymTermIds(1L)).thenReturn(List.of(1L));
        when(articleTermRepository.findArticleIdsByTermIds(List.of(1L))).thenReturn(List.of());

        // when
        Page<Article> result = articleSearchService.searchByTermWithSynonyms(
                "rare", PageRequest.of(0, 10));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchByTermWithSynonyms: 페이지 시작이 결과 수 초과 시 빈 페이지")
    void searchByTermWithSynonyms_pageExceedsTotalResults_returnsEmpty() {
        // given
        Term term = Term.builder().term("java").termType("NNG").build();
        ReflectionTestUtils.setField(term, "id", 1L);
        when(termRepository.findByTermAndTermType("java", "NNG")).thenReturn(Optional.of(term));
        when(termSynonymService.getSynonymTermIds(1L)).thenReturn(List.of(1L));
        when(articleTermRepository.findArticleIdsByTermIds(List.of(1L))).thenReturn(List.of(1L));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a1 = createArticle(1L, "Java 기초", corp);
        when(articleRepository.findAllById(List.of(1L))).thenReturn(List.of(a1));

        // when: page=5, size=10 → start=50 > 1개 결과
        Page<Article> result = articleSearchService.searchByTermWithSynonyms(
                "java", PageRequest.of(5, 10));

        // then
        assertThat(result).isEmpty();
    }

    // ===== searchByTermsWithSynonyms 테스트 =====

    @Test
    @DisplayName("searchByTermsWithSynonyms: 모든 term이 DB에 없으면 빈 페이지")
    void searchByTermsWithSynonyms_noTermsFound_returnsEmpty() {
        // given
        when(termRepository.findByTermAndTermType("unknown1", "NNG")).thenReturn(Optional.empty());
        when(termRepository.findByTermAndTermType("unknown2", "NNG")).thenReturn(Optional.empty());

        // when
        Page<Article> result = articleSearchService.searchByTermsWithSynonyms(
                List.of("unknown1", "unknown2"), PageRequest.of(0, 10));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("searchByTermsWithSynonyms: 중복 termId 제거 확인")
    void searchByTermsWithSynonyms_deduplicatesTermIds() {
        // given: 두 term이 같은 synonym을 공유
        Term term1 = Term.builder().term("redis").termType("NNG").build();
        ReflectionTestUtils.setField(term1, "id", 1L);
        Term term2 = Term.builder().term("cache").termType("NNG").build();
        ReflectionTestUtils.setField(term2, "id", 2L);

        when(termRepository.findByTermAndTermType("redis", "NNG")).thenReturn(Optional.of(term1));
        when(termRepository.findByTermAndTermType("cache", "NNG")).thenReturn(Optional.of(term2));
        // 두 term 모두 synonym ID 3을 공유
        when(termSynonymService.getSynonymTermIds(1L)).thenReturn(List.of(1L, 3L));
        when(termSynonymService.getSynonymTermIds(2L)).thenReturn(List.of(2L, 3L));
        when(articleTermRepository.findArticleIdsByTermIds(List.of(1L, 3L, 2L)))
                .thenReturn(List.of(10L));

        Corporation corp = Corporation.builder().name("Corp").build();
        corp.setId(100L);
        Article a10 = createArticle(10L, "Article", corp);
        when(articleRepository.findAllById(List.of(10L))).thenReturn(List.of(a10));

        // when
        Page<Article> result = articleSearchService.searchByTermsWithSynonyms(
                List.of("redis", "cache"), PageRequest.of(0, 10));

        // then
        assertThat(result.getContent()).hasSize(1);
    }

    // ===== getLikeStatusMap 테스트 =====

    @Test
    @DisplayName("getLikeStatusMap: 로그인 유저 → UserLikeService 사용")
    void getLikeStatusMap_loggedInUser_usesUserLikeService() {
        // given
        List<Long> ids = List.of(1L, 2L);
        when(userLikeService.getLikeStatusBatchByUser(ids, "testUser"))
                .thenReturn(Map.of(1L, true, 2L, false));

        // when
        Map<Long, Boolean> result = articleSearchService.getLikeStatusMap(ids, "testUser", "127.0.0.1");

        // then
        assertThat(result).containsEntry(1L, true);
        verify(userLikeService).getLikeStatusBatchByUser(ids, "testUser");
        verify(userLikeService, never()).getLikeStatusBatchByIp(anyList(), anyString());
    }

    @Test
    @DisplayName("getLikeStatusMap: 비로그인 유저 → IP 기반 조회")
    void getLikeStatusMap_anonymousUser_usesIpLookup() {
        // given
        List<Long> ids = List.of(1L);
        when(userLikeService.getLikeStatusBatchByIp(ids, "192.168.1.1"))
                .thenReturn(Map.of(1L, false));

        // when
        Map<Long, Boolean> result = articleSearchService.getLikeStatusMap(ids, null, "192.168.1.1");

        // then
        verify(userLikeService).getLikeStatusBatchByIp(ids, "192.168.1.1");
        verify(userLikeService, never()).getLikeStatusBatchByUser(anyList(), anyString());
    }

    @Test
    @DisplayName("getLikeStatusMap: 빈 username → IP 기반 조회")
    void getLikeStatusMap_emptyUsername_usesIpLookup() {
        // given
        List<Long> ids = List.of(1L);
        when(userLikeService.getLikeStatusBatchByIp(ids, "10.0.0.1"))
                .thenReturn(Map.of(1L, false));

        // when
        Map<Long, Boolean> result = articleSearchService.getLikeStatusMap(ids, "  ", "10.0.0.1");

        // then
        verify(userLikeService).getLikeStatusBatchByIp(ids, "10.0.0.1");
    }

    // ===== getArticleIdsByKeywordWithSynonyms 테스트 =====

    @Test
    @DisplayName("getArticleIdsByKeywordWithSynonyms: null 키워드 → null 반환")
    void getArticleIdsByKeywordWithSynonyms_nullKeyword_returnsNull() {
        // when
        List<Long> result = articleSearchService.getArticleIdsByKeywordWithSynonyms(null);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getArticleIdsByKeywordWithSynonyms: 형태소 분석 결과 없음 → null 반환")
    void getArticleIdsByKeywordWithSynonyms_noTermsExtracted_returnsNull() {
        // given
        when(morphemeAnalyzer.extractTerms("xyz")).thenReturn(Map.of());

        // when
        List<Long> result = articleSearchService.getArticleIdsByKeywordWithSynonyms("xyz");

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getArticleIdsByKeywordWithSynonyms: 영어 term 대소문자 fallback")
    void getArticleIdsByKeywordWithSynonyms_englishTermCaseFallback() {
        // given
        MorphemeAnalyzer.TermInfo termInfo = new MorphemeAnalyzer.TermInfo("Redis", "SL", 1);
        when(morphemeAnalyzer.extractTerms("Redis")).thenReturn(Map.of("Redis", termInfo));

        // SL 타입은 원본/소문자/대문자 변형을 한 번의 IN 조회로 넘긴다 (매칭되는 것은 소문자뿐)
        Term term = Term.builder().term("redis").termType("SL").build();
        ReflectionTestUtils.setField(term, "id", 1L);
        when(termRepository.findByTermIn(Set.of("Redis", "redis", "REDIS"))).thenReturn(List.of(term));

        when(termSynonymService.expandTermIdsWithSynonyms(List.of(1L))).thenReturn(List.of(1L, 2L));
        when(articleTermRepository.findArticleIdsByTermIds(List.of(1L, 2L))).thenReturn(List.of(10L, 20L));

        // when
        List<Long> result = articleSearchService.getArticleIdsByKeywordWithSynonyms("Redis");

        // then
        assertThat(result).containsExactly(10L, 20L);
        // term별 반복 조회(N+1)가 아니라 IN 조회 1회
        verify(termRepository, times(1)).findByTermIn(anyCollection());
        verify(termRepository, never()).findByTerm(anyString());
    }

    // ===== getTopArticleIdsForRag 테스트 =====

    @Test
    @DisplayName("getTopArticleIdsForRag: corporationIds 필터 → corp 필터 BM25/Vector 변형 호출 + NSF 정렬")
    void getTopArticleIdsForRag_withCorporationFilter() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka를 도입한 사례";
        List<Long> corporationIds = List.of(1L, 2L);

        when(semanticExpansionService.classifyQueryComplexity(bm25Keywords))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(bm25Keywords, 1.0);
        when(semanticExpansionService.expandSearchTerms(bm25Keywords)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:kafka^6.0 OR content_terms:kafka^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        // corp 필터 BM25 결과
        when(articleRepository.searchByBM25WithCorporations(bm25Query, corporationIds, 100))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));

        // corp 필터 + threshold 벡터 검색 결과
        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchForRag(vectorQuery, corporationIds, 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(20L, 0.8), dummyEmbedding));

        // cross-scoring (빈 결과)
        when(vectorSearchService.computeSimilarityForArticlesWithEmbedding(any(float[].class), anyList(), eq(0.6)))
                .thenReturn(Map.of());
        when(articleRepository.computeBM25ScoreForArticleIds(eq(bm25Query), anyList()))
                .thenReturn(Collections.emptyList());

        Map<Long, Double> nsfScores = Map.of(10L, 0.9, 20L, 0.5);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(10L, 1.0), Map.of(20L, 1.0), Map.of(10L, 1.0, 20L, 1.0));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);

        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{10L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{20L, LocalDateTime.of(2024, 2, 1, 0, 0)}
        ));

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, corporationIds, 5, 0.6);

        // then: NSF 스코어 내림차순, corp 필터 변형이 호출됨 (무필터 변형 미호출)
        assertThat(result.articleIds()).containsExactly(10L, 20L);
        assertThat(result.queryEmbedding()).isEqualTo(dummyEmbedding);
        verify(articleRepository).searchByBM25WithCorporations(bm25Query, corporationIds, 100);
        verify(articleRepository, never()).searchByBM25(anyString(), anyInt());
        verify(vectorSearchService).searchForRag(vectorQuery, corporationIds, 0.6, false);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: corporationIds 비어 있음 → 무필터 BM25 사용")
    void getTopArticleIdsForRag_withoutCorporationFilter() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka를 도입한 사례";

        when(semanticExpansionService.classifyQueryComplexity(bm25Keywords))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(bm25Keywords, 1.0);
        when(semanticExpansionService.expandSearchTerms(bm25Keywords)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:kafka^6.0 OR content_terms:kafka^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        when(articleRepository.searchByBM25(bm25Query, 100))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));

        Map<Long, Double> nsfScores = Map.of(10L, 0.9);
        HybridSearchScorer.NSFResult nsfResult = new HybridSearchScorer.NSFResult(
                nsfScores, Map.of(10L, 1.0), Map.of(), Map.of(10L, 1.0));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(nsfResult);
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, LocalDateTime.of(2024, 1, 1, 0, 0)}));

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).containsExactly(10L);
        verify(articleRepository).searchByBM25(bm25Query, 100);
        verify(articleRepository, never()).searchByBM25WithCorporations(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: null bm25Keywords → 빈 결과, 상호작용 없음")
    void getTopArticleIdsForRag_nullBm25Keywords_returnsEmpty() {
        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                null, "query", List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).isEmpty();
        assertThat(result.queryEmbedding()).isNull();
        verifyNoInteractions(semanticExpansionService, vectorSearchService);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: 공백 bm25Keywords → 빈 결과")
    void getTopArticleIdsForRag_blankBm25Keywords_returnsEmpty() {
        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                "   ", "query", List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).isEmpty();
        verifyNoInteractions(semanticExpansionService, vectorSearchService);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: 의미 확장 결과 없음 → BM25 쿼리 생성 실패 → 빈 결과")
    void getTopArticleIdsForRag_bm25QueryBuildFails_returnsEmpty() {
        // given
        when(semanticExpansionService.classifyQueryComplexity("kafka"))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));
        when(semanticExpansionService.expandSearchTerms("kafka")).thenReturn(Collections.emptyMap());

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                "kafka", "query", List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).isEmpty();
        verifyNoInteractions(vectorSearchService);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: Vector 검색 예외 → BM25 단독 결과로 폴백")
    void getTopArticleIdsForRag_vectorSearchThrows_fallsBackToBm25Only() {
        // given
        String bm25Keywords = "kafka";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));
        when(vectorSearchService.searchForRag(anyString(), any(), anyDouble(), anyBoolean()))
                .thenThrow(new RuntimeException("vector down"));

        Map<Long, Double> nsfScores = Map.of(10L, 0.9);
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(nsfScores, Map.of(), Map.of(), Map.of()));
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, LocalDateTime.of(2024, 1, 1, 0, 0)}));

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, "query", List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).containsExactly(10L);
        assertThat(result.queryEmbedding()).isNull();
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: useMockEmbedding=true → searchForRag에 그대로 전달")
    void getTopArticleIdsForRag_useMockEmbeddingTrue_plumbsFlagToVectorSearchService() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka 도입 사례";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(Collections.emptyList());
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, true))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.getTopArticleIdsForRag(bm25Keywords, vectorQuery, List.of(), 5, 0.6, true);

        // then
        verify(vectorSearchService).searchForRag(vectorQuery, List.of(), 0.6, true);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: cross-scoring — BM25-only id에 Vector 점수 보충 (threshold 포함 오버로드)")
    void getTopArticleIdsForRag_crossScoring_vectorSupplementsBm25OnlyIds() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka 도입 사례";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(20L, 0.8), dummyEmbedding));
        when(vectorSearchService.computeSimilarityForArticlesWithEmbedding(eq(dummyEmbedding), anyList(), eq(0.6)))
                .thenReturn(Map.of(10L, 0.55));

        Map<Long, Double> nsfScores = Map.of(10L, 0.7, 20L, 0.5);
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(nsfScores, Map.of(), Map.of(), Map.of()));
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{10L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{20L, LocalDateTime.of(2024, 2, 1, 0, 0)}
        ));

        // when
        articleSearchService.getTopArticleIdsForRag(bm25Keywords, vectorQuery, List.of(), 5, 0.6);

        // then
        verify(vectorSearchService).computeSimilarityForArticlesWithEmbedding(
                eq(dummyEmbedding), argThat(ids -> ids.contains(10L)), eq(0.6));
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: cross-scoring — Vector-only id에 BM25 점수 보충 (필터 없는 쿼리 재사용)")
    void getTopArticleIdsForRag_crossScoring_bm25SupplementsVectorOnlyIds() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka 도입 사례";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(20L, 0.8), dummyEmbedding));
        when(vectorSearchService.computeSimilarityForArticlesWithEmbedding(any(float[].class), anyList(), anyDouble()))
                .thenReturn(Map.of());
        when(articleRepository.computeBM25ScoreForArticleIds(eq(bm25Query), argThat(ids -> ids.contains(20L))))
                .thenReturn(List.<Object[]>of(new Object[]{20L, 4.0, LocalDateTime.of(2024, 2, 1, 0, 0)}));

        Map<Long, Double> nsfScores = Map.of(10L, 0.6, 20L, 0.8);
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(nsfScores, Map.of(), Map.of(), Map.of()));
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{10L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{20L, LocalDateTime.of(2024, 2, 1, 0, 0)}
        ));

        // when
        articleSearchService.getTopArticleIdsForRag(bm25Keywords, vectorQuery, List.of(), 5, 0.6);

        // then: regions/category 없는(필터 없는) computeBM25ScoreForArticleIds 변형이 호출됨
        verify(articleRepository).computeBM25ScoreForArticleIds(eq(bm25Query), argThat(ids -> ids.contains(20L)));
        verify(articleRepository, never()).computeBM25ScoreForArticleIdsWithDomesticTypes(anyString(), anyList(), anyList());
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: nsfScores 비어도 embedding은 보존됨 (computeHybridCore와 다른 지점)")
    void getTopArticleIdsForRag_allNsfScoresEmpty_returnsEmptyListButPreservesEmbedding() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka 도입 사례";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(Collections.emptyList());

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), dummyEmbedding));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).isEmpty();
        assertThat(result.queryEmbedding()).isEqualTo(dummyEmbedding);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: 후보가 limit보다 많으면 상위 limit개만 반환")
    void getTopArticleIdsForRag_limitTruncatesResults() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka 도입 사례";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(List.<Object[]>of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, 9.0, LocalDateTime.of(2024, 1, 2, 0, 0)},
                new Object[]{3L, 8.0, LocalDateTime.of(2024, 1, 3, 0, 0)},
                new Object[]{4L, 7.0, LocalDateTime.of(2024, 1, 4, 0, 0)},
                new Object[]{5L, 6.0, LocalDateTime.of(2024, 1, 5, 0, 0)}
        ));
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));

        Map<Long, Double> nsfScores = Map.of(1L, 0.1, 2L, 0.9, 3L, 0.5, 4L, 0.7, 5L, 0.3);
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(nsfScores, Map.of(), Map.of(), Map.of()));
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 1, 2, 0, 0)},
                new Object[]{3L, LocalDateTime.of(2024, 1, 3, 0, 0)},
                new Object[]{4L, LocalDateTime.of(2024, 1, 4, 0, 0)},
                new Object[]{5L, LocalDateTime.of(2024, 1, 5, 0, 0)}
        ));

        // when: 상위 2개만 (점수 내림차순: 2L(0.9), 4L(0.7))
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, List.of(), 2, 0.6);

        // then
        assertThat(result.articleIds()).containsExactly(2L, 4L);
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: BM25 리포지토리 예외 → 빈 BM25 결과로 처리, 예외 전파 안 됨")
    void getTopArticleIdsForRag_bm25RepositoryThrows_treatedAsEmptyBm25() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka 도입 사례";
        String bm25Query = setupRagBm25Query(bm25Keywords);
        when(articleRepository.searchByBM25(bm25Query, 100)).thenThrow(new RuntimeException("db error"));

        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(20L, 0.8), dummyEmbedding));

        Map<Long, Double> nsfScores = Map.of(20L, 0.5);
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(nsfScores, Map.of(), Map.of(), Map.of()));
        when(articleRepository.findIdAndPublishedAtByIdIn(anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{20L, LocalDateTime.of(2024, 2, 1, 0, 0)}));

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, List.of(), 5, 0.6);

        // then: 예외 없이 Vector 결과 기반으로 정상 반환
        assertThat(result.articleIds()).containsExactly(20L);
    }

    // ===== 헬퍼 메서드 =====

    private Article createArticle(Long id, String title, Corporation corporation) {
        Article article = Article.builder()
                .title(title)
                .link("https://example.com/" + id)
                .corporation(corporation)
                .publishedAt(LocalDateTime.now())
                .build();
        article.setId(id);
        return article;
    }

    /**
     * searchArticlesHybrid의 기본 mock 설정 (BM25 쿼리 생성까지)
     */
    private void setupBasicHybridSearch(String keyword) {
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(keyword, 1.0);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);

        String bm25Query = "title_terms:" + keyword + "^6.0 OR content_terms:" + keyword + "^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        // BM25 결과
        List<Object[]> bm25RawResults = List.of(
                new Object[]{1L, 10.0, LocalDateTime.of(2023, 1, 1, 0, 0)},
                new Object[]{2L, 5.0, LocalDateTime.of(2024, 6, 1, 0, 0)}
        );
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(bm25RawResults);

        // Vector 결과 (빈 결과)
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
    }

    /**
     * region=domestic 필터 하이브리드 검색의 기본 mock 설정 (쿼리 생성 + WithDomesticTypes BM25 검색까지)
     */
    private String setupHybridSearchWithDomesticFilter(String keyword, List<Object[]> bm25RawResults) {
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(keyword, 1.0);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);

        String bm25Query = "title_terms:" + keyword + "^6.0 OR content_terms:" + keyword + "^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);
        when(articleRepository.searchByBM25WithDomesticTypes(eq(bm25Query), eq(List.of(1)), eq(100)))
                .thenReturn(bm25RawResults);
        return bm25Query;
    }

    /**
     * getTopArticleIdsForRag의 기본 mock 설정 (BM25 쿼리 생성까지, 필터 없음)
     */
    private String setupRagBm25Query(String bm25Keywords) {
        when(semanticExpansionService.classifyQueryComplexity(bm25Keywords))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(bm25Keywords, 1.0);
        when(semanticExpansionService.expandSearchTerms(bm25Keywords)).thenReturn(expandedTerms);

        String bm25Query = "title_terms:" + bm25Keywords + "^6.0 OR content_terms:" + bm25Keywords + "^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);
        return bm25Query;
    }

    // ===== 커넥션 점유 구간 구조 검증 =====
    // 유효성 검증 쿼리는 cross-scoring과 같은 트랜잭션 안에서(체크아웃 2회 유지) 수행하되,
    // NSF 계산(순수 CPU)은 그 트랜잭션이 닫힌 뒤로 밀어낸다. 이게 가능한 이유는 후보 id 집합이
    // NSF 결과가 아니라 bm25 ∪ vector이고 cross-scoring이 그 집합을 늘리지 않기 때문
    // (HybridSearchScorerTest.calculateNSFScores_결과_키셋은_BM25와_Vector의_합집합이다 참고).

    @Test
    @DisplayName("searchArticlesHybrid: 유효성 검증 쿼리는 NSF 계산 전에 bm25∪vector 합집합으로 1회 실행")
    void searchArticlesHybrid_validityQueryRunsBeforeNsfWithUnionIds() {
        // given
        String keyword = "redis";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(keyword, 1.0);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:redis^6.0 OR content_terms:redis^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        // BM25 전용 2, 양쪽 공통 1, Vector 전용 3 → 합집합 {1, 2, 3}
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(List.<Object[]>of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, 5.0, LocalDateTime.of(2024, 6, 1, 0, 0)}
        ));
        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(1L, 0.9, 3L, 0.7), dummyEmbedding));

        // cross-scoring이 실제로 점수를 채워 넣어도 후보 집합은 늘어나지 않아야 한다
        when(vectorSearchService.computeSimilarityForSearchCrossScoring(any(float[].class), anyList(), anyDouble()))
                .thenReturn(Map.of(2L, 0.55));
        when(articleRepository.computeBM25ScoreForArticleIds(eq(bm25Query), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 4.0}));

        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(
                        Map.of(1L, 0.95, 2L, 0.25, 3L, 0.35),
                        Map.of(1L, 1.0, 2L, 0.0),
                        Map.of(1L, 1.0, 3L, 0.0),
                        Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0)));

        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{1L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{2L, LocalDateTime.of(2024, 6, 1, 0, 0)},
                new Object[]{3L, LocalDateTime.of(2024, 3, 1, 0, 0)}
        ));

        Corporation corp = Corporation.builder().name("Test Corp").build();
        corp.setId(100L);
        when(articleRepository.findByIdInWithCorporation(anyList())).thenReturn(List.of(
                createArticle(1L, "Redis 입문", corp),
                createArticle(2L, "캐시 전략", corp),
                createArticle(3L, "메모리 DB", corp)
        ));
        when(userLikeService.getLikeStatusBatchByIp(anyList(), eq("127.0.0.1")))
                .thenReturn(Map.of(1L, false, 2L, false, 3L, false));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then: 합집합 그대로, 1회만
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.captor();
        verify(articleRepository, times(1)).findIdAndPublishedAtByIdIn(idsCaptor.capture());
        assertThat(Set.copyOf(idsCaptor.getValue())).isEqualTo(Set.of(1L, 2L, 3L));

        // then: 유효성 쿼리(트랜잭션 안) → NSF 계산(트랜잭션 밖) 순서
        InOrder inOrder = inOrder(articleRepository, hybridSearchScorer);
        inOrder.verify(articleRepository).findIdAndPublishedAtByIdIn(anyList());
        inOrder.verify(hybridSearchScorer).calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4));
    }

    @Test
    @DisplayName("getTopArticleIdsForRag: 유효성 검증 쿼리는 NSF 계산 전에 bm25∪vector 합집합으로 1회 실행")
    void getTopArticleIdsForRag_validityQueryRunsBeforeNsfWithUnionIds() {
        // given
        String bm25Keywords = "kafka";
        String vectorQuery = "Kafka를 도입한 사례";

        when(semanticExpansionService.classifyQueryComplexity(bm25Keywords))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(bm25Keywords, 1.0);
        when(semanticExpansionService.expandSearchTerms(bm25Keywords)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:kafka^6.0 OR content_terms:kafka^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        // BM25 전용 10, Vector 전용 20 → 합집합 {10, 20}
        when(articleRepository.searchByBM25(bm25Query, 100))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));
        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchForRag(vectorQuery, List.of(), 0.6, false))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(20L, 0.8), dummyEmbedding));

        when(vectorSearchService.computeSimilarityForArticlesWithEmbedding(any(float[].class), anyList(), eq(0.6)))
                .thenReturn(Map.of(10L, 0.65));
        when(articleRepository.computeBM25ScoreForArticleIds(eq(bm25Query), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{20L, 3.0}));

        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(
                        Map.of(10L, 0.9, 20L, 0.5),
                        Map.of(10L, 1.0), Map.of(20L, 1.0), Map.of(10L, 1.0, 20L, 1.0)));

        when(articleRepository.findIdAndPublishedAtByIdIn(anyList())).thenReturn(List.of(
                new Object[]{10L, LocalDateTime.of(2024, 1, 1, 0, 0)},
                new Object[]{20L, LocalDateTime.of(2024, 2, 1, 0, 0)}
        ));

        // when
        ArticleSearchService.HybridTopArticles result = articleSearchService.getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, List.of(), 5, 0.6);

        // then
        assertThat(result.articleIds()).containsExactly(10L, 20L);

        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.captor();
        verify(articleRepository, times(1)).findIdAndPublishedAtByIdIn(idsCaptor.capture());
        assertThat(Set.copyOf(idsCaptor.getValue())).isEqualTo(Set.of(10L, 20L));

        InOrder inOrder = inOrder(articleRepository, hybridSearchScorer);
        inOrder.verify(articleRepository).findIdAndPublishedAtByIdIn(anyList());
        inOrder.verify(hybridSearchScorer).calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4));
    }

    @Test
    @DisplayName("searchArticlesHybrid(useMockEmbedding=true): 벡터 검색에 mock 플래그를 전달한다 (실 Clova 미호출)")
    void searchArticlesHybrid_mockEmbedding_propagatesFlagToVectorSearch() {
        // given
        String keyword = "kafka redis msa";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of("kafka", 1.0);
        String bm25Query = "title_terms:kafka^6.0 OR content_terms:kafka^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(Collections.emptyList());
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(true)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, expandedTerms, List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null, true);

        // then: mock 플래그가 임베딩 생성 지점까지 내려간다 (3-arg 실사용자 오버로드는 호출되지 않음)
        verify(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(true));
        verify(vectorSearchService, never()).searchByKeywordWithEmbedding(anyString(), any(), any());
    }

    @Test
    @DisplayName("searchArticlesHybrid 기본 호출: useMockEmbedding=false로 위임한다 (실사용자 경로 무변경)")
    void searchArticlesHybrid_default_usesRealEmbeddingPath() {
        // given
        String keyword = "redis";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of(keyword, 1.0);
        when(semanticExpansionService.expandSearchTerms(keyword)).thenReturn(expandedTerms);
        String bm25Query = "title_terms:redis^6.0 OR content_terms:redis^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(Collections.emptyList());
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), null));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null);

        // then
        verify(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(false));
    }

    @Test
    @DisplayName("cross-scoring 벡터 보충은 2단계 검색과 같은 임계값을 쓴다 (부하테스트 경로는 완화값)")
    void crossScoringUsesSameVectorThresholdAsMainSearch() {
        // given
        String keyword = "kafka redis";
        when(semanticExpansionService.classifyQueryComplexity(keyword))
                .thenReturn(SemanticTermExpansionService.QueryComplexity.SIMPLE);
        when(weightConfig.getWeights(SemanticTermExpansionService.QueryComplexity.SIMPLE))
                .thenReturn(new SearchWeightConfigService.WeightEntry(3.0, 0.6, 0.4));

        Map<String, Double> expandedTerms = Map.of("kafka", 1.0);
        String bm25Query = "title_terms:kafka^6.0 OR content_terms:kafka^2.0";
        when(hybridSearchScorer.buildBM25Query(expandedTerms, 3.0)).thenReturn(bm25Query);

        // BM25-only id 1개 → cross-scoring 벡터 보충 대상이 된다
        when(articleRepository.searchByBM25(bm25Query, 100)).thenReturn(List.<Object[]>of(
                new Object[]{1L, 10.0, LocalDateTime.of(2024, 1, 1, 0, 0)}));
        float[] dummyEmbedding = new float[]{0.1f, 0.2f};
        when(vectorSearchService.searchByKeywordWithEmbedding(eq(keyword), any(), any(), eq(true)))
                .thenReturn(new VectorSearchService.VectorSearchResult(Map.of(), dummyEmbedding));
        when(vectorSearchService.vectorThresholdFor(true)).thenReturn(0.0);
        when(vectorSearchService.computeSimilarityForSearchCrossScoring(
                any(float[].class), anyList(), eq(0.0))).thenReturn(Map.of(1L, 0.05));
        when(hybridSearchScorer.calculateNSFScores(anyMap(), anyMap(), eq(0.6), eq(0.4)))
                .thenReturn(new HybridSearchScorer.NSFResult(Map.of(), Map.of(), Map.of(), Map.of()));

        // when
        articleSearchService.searchArticlesHybrid(
                keyword, expandedTerms, List.of(), List.of(), 0, 10, "relevance", "127.0.0.1", null, true);

        // then: 기본 임계값(0.52)이 아니라 완화된 임계값이 명시적으로 전달된다
        verify(vectorSearchService).computeSimilarityForSearchCrossScoring(
                any(float[].class), anyList(), eq(0.0));
        // 검색 경로는 검색 전용 진입점만 쓴다 — RAG가 타는 메서드로 새면 퍼널 스위치가 무시된다
        verify(vectorSearchService, never()).computeSimilarityForArticlesWithEmbedding(
                any(float[].class), anyList(), anyDouble());
    }
}
