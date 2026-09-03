package com.newcodes7.small_town.search.service;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.service.ArticleEmbeddingService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.like.service.UserLikeService;
import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import com.newcodes7.small_town.search.scorer.HybridSearchScorer;
import com.newcodes7.small_town.term.repository.ArticleTermRepository;
import com.newcodes7.small_town.term.repository.TermRepository;
import com.newcodes7.small_town.term.service.TermSynonymService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class ArticleSearchService {

    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final TermRepository termRepository;
    private final HybridSearchScorer hybridSearchScorer;
    private final VectorSearchService vectorSearchService;
    private final SemanticTermExpansionService semanticExpansionService;
    private final ArticleEmbeddingService embeddingService;
    private final TermSynonymService termSynonymService;
    private final UserLikeService userLikeService;
    private final MorphemeAnalyzer morphemeAnalyzer;
    private final ExecutorService searchExecutor;
    private final SearchWeightConfigService weightConfig;
    private final CacheManager cacheManager;
    private final ObservationRegistry observationRegistry;

    /**
     * 하이브리드 검색 경로(computeHybridCore, getTopArticleIdsForRag) 전용 read-only 트랜잭션 템플릿.
     * 메서드 전체를 하나의 @Transactional로 묶는 대신, 실제 DB 접근 구간만 짧게 감싸서
     * Vector future 대기(최대 5초) 동안 커넥션을 점유하지 않도록 한다.
     * REQUIRED 전파(기본값)이므로 테스트의 앰비언트 트랜잭션에는 그대로 합류한다.
     */
    private final TransactionTemplate readOnlyTx;

    /**
     * cross-scoring Vector 보충 시 2단계 검색의 Stage 1 후보 점수를 재활용할지 여부. 기본 true.
     *
     * 본검색이 threshold/limit로 버린 후보 점수를 그대로 쓰면 그만큼 cross-scoring DB 왕복
     * (computeSimilarityForArticleIds, 검색 1건당 DB 예산의 33%)이 사라진다.
     * false로 두면 종전처럼 전부 DB로 보충한다 — 같은 빌드에서 core-s/req A/B를 재기 위한 스위치다
     * (docs/operations/PGSS_SEARCH_COST.md 항목 A).
     */
    @Value("${search.hybrid.reuse-stage1-candidates:true}")
    private boolean reuseStage1Candidates;

    /**
     * single-flight 조인자가 리더의 결과를 기다리는 상한.
     *
     * 리더의 worst case는 Phase A 트랜잭션(커넥션 획득 3s + statement_timeout 5s)
     * + vectorFuture.get(5s) + cross-scoring 트랜잭션(3s + 5s) ≈ 21s이므로 정상 동작에서는
     * 걸리지 않는다. 반대쪽 상한인 nginx proxy_read_timeout(60s, `^~ /api/`)보다는 낮게 둬,
     * 리더가 끝내 응답하지 못해도 조인자가 504 대신 실제 응답을 받게 한다.
     */
    private static final long HYBRID_CORE_JOIN_TIMEOUT_SECONDS = 30;

    private static final String HYBRID_TOP_ARTICLES_CACHE_NAME = "hybridTopArticles";
    private static final String RAG_TOP_ARTICLES_CACHE_NAME = "ragTopArticles";

    /**
     * 무필터 하이브리드 코어 single-flight 캐시.
     * 검색 API와 AI 요약이 같은 키워드로 동시 진입하면 한쪽만 계산하고 나머지는 future에 합류한다.
     * 실패(빈 결과 포함)한 future는 Caffeine이 자동 제거하므로 다음 요청이 재계산한다.
     */
    private final AsyncCache<String, HybridCoreResult> hybridCoreCache;

    public ArticleSearchService(ArticleRepository articleRepository,
                                ArticleTermRepository articleTermRepository,
                                TermRepository termRepository,
                                HybridSearchScorer hybridSearchScorer,
                                VectorSearchService vectorSearchService,
                                SemanticTermExpansionService semanticExpansionService,
                                ArticleEmbeddingService embeddingService,
                                TermSynonymService termSynonymService,
                                UserLikeService userLikeService,
                                MorphemeAnalyzer morphemeAnalyzer,
                                @Qualifier("searchExecutor") ExecutorService searchExecutor,
                                SearchWeightConfigService weightConfig,
                                CacheManager cacheManager,
                                ObservationRegistry observationRegistry,
                                PlatformTransactionManager transactionManager) {
        this.articleRepository = articleRepository;
        this.articleTermRepository = articleTermRepository;
        this.termRepository = termRepository;
        this.hybridSearchScorer = hybridSearchScorer;
        this.vectorSearchService = vectorSearchService;
        this.semanticExpansionService = semanticExpansionService;
        this.embeddingService = embeddingService;
        this.termSynonymService = termSynonymService;
        this.userLikeService = userLikeService;
        this.morphemeAnalyzer = morphemeAnalyzer;
        this.searchExecutor = searchExecutor;
        this.weightConfig = weightConfig;
        this.cacheManager = cacheManager;
        this.observationRegistry = observationRegistry;
        // 계산은 첫 진입자의 호출 스레드에서 직접 수행하고(트랜잭션 컨텍스트 유지),
        // 캐시는 in-flight future 공유(합류)에만 쓰인다 — getHybridCoreShared 참고
        this.hybridCoreCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .buildAsync();
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /**
     * 확장된 검색어를 사용한 BM25 검색
     * expandedTerms의 모든 term에 대해 BM25 검색을 수행하고, term weight와 BM25 score를 조합하여 정렬
     *
     * @param expandedTerms 확장된 검색어와 가중치 맵
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 방식
     * @return BM25 검색 결과
     */
    // TODO(cleanup): unused - no controller/service caller as of 2026-08
    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
    public Page<ArticleSearchResultDto> searchArticlesWithExpandedTerms(
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort) {

        if (expandedTerms == null || expandedTerms.isEmpty()) {
            return Page.empty();
        }

        Map<Long, Double> bm25Scores = new HashMap<>();

        // expandedTerms를 사용한 BM25 검색
        Map<Long, Double> bm25Results = performBM25SearchWithExpandedTerms(expandedTerms, regions, category);
        if (bm25Results != null && !bm25Results.isEmpty()) {
            bm25Scores.putAll(bm25Results);
        }

        if (bm25Scores.isEmpty()) {
            return Page.empty();
        }

        // Article 조회
        List<Article> allArticles = articleRepository.findAllById(bm25Scores.keySet());

        // 필터 적용
        allArticles = filterArticles(allArticles, regions, category);

        // DTO 생성
        List<ArticleSearchResultDto> results = allArticles.stream()
                .map(article -> {
                    Double bm25Score = bm25Scores.get(article.getId());
                    return new ArticleSearchResultDto(article, false, bm25Score, null);
                })
                .collect(Collectors.toList());

        // 정렬 (BM25 점수 기준)
        results = sortResults(results, sort);

        // 페이징
        return paginateResults(results, page, size);
    }

    /**
     * Hybrid 검색 + NSF (Normalized Score Fusion) 스코어링
     * BM25 (키워드 정확도) + Vector (의미 검색) → NSF로 통합
     *
     * 검색 방법:
     * - BM25: ArticleTerm 기반 키워드 검색 (정확도 높음)
     * - Vector: 의미적 유사도 검색 (시맨틱 검색)
     *
     * 모든 검색 결과는 BM25와 Vector 점수를 모두 가짐:
     * - BM25로만 검색된 article: Vector 점수 추가 계산
     * - Vector로만 검색된 article: BM25 점수 추가 계산
     *
     * @param keyword 검색 키워드
     * @param regions 지역 필터 (domestic, overseas)
     * @param category 카테고리 필터
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 방식 (relevance: RRF 스코어순, latest: 최신순, oldest: 오래된순)
     * @param ipAddress 사용자 IP 주소 (좋아요 상태 조회용)
     * @return 검색 결과 (RRF 스코어 기반 정렬, 좋아요 상태 포함)
     *
     * 이 오버로드(expandedTerms 없음)가 핫 경로용 기본형이다 — 검색어 확장을 코어 안에서,
     * Clova 임베딩 호출이 뜬 뒤에 실행해 임베딩 대기 구간에 겹친다.
     * expandedTerms를 받는 오버로드는 호출자가 이미 확장 결과를 갖고 있을 때만 쓸 것
     * (docs/search/SEARCH_TRACE_ANALYSIS.md A-1).
     */
    public Page<ArticleSearchResultDto> searchArticlesHybrid(
            String keyword,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort,
            String ipAddress,
            String username) {
        return searchArticlesHybrid(keyword, null, regions, category, page, size, sort, ipAddress, username);
    }

    public Page<ArticleSearchResultDto> searchArticlesHybrid(
            String keyword,
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort,
            String ipAddress,
            String username) {
        return searchArticlesHybrid(keyword, expandedTerms, regions, category, page, size, sort,
                ipAddress, username, false);
    }

    /**
     * useMockEmbedding=true(부하테스트 전용 경로)면 쿼리 임베딩을 mock 엔드포인트로 생성한다
     * — 실 Clova 과금 없이, search_query_embedding DB 캐시를 오염시키지 않고 캐시 미스 경로를 측정하기 위함.
     * 실사용자 경로는 이 값이 항상 false다 (ArticleSearchLoadTestController 참고).
     */
    public Page<ArticleSearchResultDto> searchArticlesHybrid(
            String keyword,
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort,
            String ipAddress,
            String username,
            boolean useMockEmbedding) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return Page.empty();
        }

        // 하이브리드 코어(BM25 + Vector + cross-scoring + NSF) 계산.
        // 무필터 검색은 AI 요약(getTopArticleIdsByHybrid)과 같은 계산이므로 single-flight 캐시로 공유한다.
        boolean unfiltered = (regions == null || regions.isEmpty())
                && (category == null || category.isEmpty());
        HybridCoreResult core = unfiltered
                ? getHybridCoreShared(keyword, expandedTerms, useMockEmbedding)
                : computeHybridCore(keyword, expandedTerms, regions, category, useMockEmbedding);

        Map<Long, Double> nsfScores = core.nsfScores();
        if (nsfScores.isEmpty()) {
            return Page.empty();
        }

        final Map<Long, Double> finalBm25Results = core.bm25Scores();
        final Map<Long, Double> finalVectorResults = core.vectorScores();
        final Map<Long, Integer> bm25Ranks = core.bm25Ranks();
        final Map<Long, Integer> vectorRanks = core.vectorRanks();
        final Map<Long, Integer> sourceBm25Ranks = core.sourceBm25Ranks();
        final Map<Long, Integer> sourceVectorRanks = core.sourceVectorRanks();
        final Map<Long, Double> normalizedBm25Map = core.normalizedBm25();
        final Map<Long, Double> normalizedVectorMap = core.normalizedVector();
        final Map<Long, Double> weightSumMap = core.weightSums();
        Set<Long> vectorOnlyIds = core.vectorOnlyIds();
        Map<Long, LocalDateTime> publishedAtMap = core.publishedAtMap();
        Set<Long> validArticleIds = core.validArticleIds();

        // 9-10. 정렬 → 페이징 (sort 파라미터에 따라 NSF 정렬 또는 날짜 정렬)
        int offset = page * size;
        List<Long> sortedValidIds;

        if ("latest".equals(sort) || "oldest".equals(sort)) {
            // 날짜 정렬: NSF 정렬 없이 바로 날짜 기준으로 정렬
            sortedValidIds = nsfScores.keySet().stream()
                    .filter(validArticleIds::contains)
                    .sorted((id1, id2) -> {
                        LocalDateTime date1 = publishedAtMap.getOrDefault(id1, LocalDateTime.MIN);
                        LocalDateTime date2 = publishedAtMap.getOrDefault(id2, LocalDateTime.MIN);
                        return "latest".equals(sort) ? date2.compareTo(date1) : date1.compareTo(date2);
                    })
                    .collect(Collectors.toList());
        } else {
            // 적합도순 (NSF 스코어순)
            sortedValidIds = nsfScores.entrySet().stream()
                    .filter(e -> validArticleIds.contains(e.getKey()))
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        int totalResults = sortedValidIds.size();
        if (offset >= totalResults) {
            return Page.empty(PageRequest.of(page, size));
        }

        List<Long> pageArticleIds = sortedValidIds.stream()
                .skip(offset)
                .limit(size)
                .collect(Collectors.toList());

        // 11-12. Phase C: 현재 페이지 article 로딩(Corporation fetch join으로 N+1 방지) + 좋아요 상태
        // batch 조회를 짧은 트랜잭션 하나로 묶는다 (getLikeStatusMap도 자체 @Transactional(REQUIRED)이라 합류)
        Map<Long, Article> pageArticleMap = new HashMap<>();
        Map<Long, Boolean> likeStatusMap = new HashMap<>();
        readOnlyTx.executeWithoutResult(status -> {
            pageArticleMap.putAll(articleRepository.findByIdInWithCorporation(pageArticleIds)
                    .stream()
                    .collect(Collectors.toMap(Article::getId, a -> a)));
            likeStatusMap.putAll(getLikeStatusMap(pageArticleIds, username, ipAddress));
        });

        // 13. DTO 생성 (페이지 순서 유지, 순위 정보 + 정규화 점수 포함)
        List<ArticleSearchResultDto> results = pageArticleIds.stream()
                .map(pageArticleMap::get)
                .filter(a -> a != null)
                .map(article -> {
                    Long articleId = article.getId();
                    Double bm25Score = finalBm25Results.get(articleId);
                    Double vectorScore = finalVectorResults.get(articleId);
                    Double nsfScore = nsfScores.get(articleId);
                    Boolean isLiked = likeStatusMap.getOrDefault(articleId, false);
                    boolean foundByVector = vectorOnlyIds.contains(articleId);
                    Integer bm25Rank = bm25Ranks.get(articleId);
                    Integer vectorRank = vectorRanks.get(articleId);
                    Double normBm25 = normalizedBm25Map.get(articleId);
                    Double normVector = normalizedVectorMap.get(articleId);
                    Double weightSum = weightSumMap.get(articleId);
                    return new ArticleSearchResultDto(article, foundByVector, bm25Score, vectorScore, nsfScore, null,
                            bm25Rank, vectorRank, normBm25, normVector, weightSum, isLiked,
                            sourceBm25Ranks.get(articleId), sourceVectorRanks.get(articleId));
                })
                .collect(Collectors.toList());

        return new PageImpl<>(results, PageRequest.of(page, size), totalResults);
    }

    /**
     * 하이브리드 코어 파이프라인의 전체 산출물.
     * 검색 API(페이징/DTO 조립)와 AI 요약(top-N 추출)이 공유한다.
     */
    public record HybridCoreResult(
            Map<Long, Double> bm25Scores,
            Map<Long, Double> vectorScores,
            Map<Long, Double> nsfScores,
            Map<Long, Integer> bm25Ranks,
            Map<Long, Integer> vectorRanks,
            Map<Long, Integer> sourceBm25Ranks,
            Map<Long, Integer> sourceVectorRanks,
            Map<Long, Double> normalizedBm25,
            Map<Long, Double> normalizedVector,
            Map<Long, Double> weightSums,
            Set<Long> vectorOnlyIds,
            Map<Long, LocalDateTime> publishedAtMap,
            Set<Long> validArticleIds,
            float[] queryEmbedding) {
        static HybridCoreResult empty() {
            return new HybridCoreResult(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), Set.of(), null);
        }
    }

    /**
     * 무필터 하이브리드 코어 single-flight 공유 진입점.
     * 같은 키워드의 동시 요청(검색 API + AI 요약)은 첫 요청만 계산하고 나머지는 future에 합류한다.
     * 빈 결과(검색 실패 포함)는 예외로 완료시켜 캐시에 남기지 않는다 — 다음 요청이 재계산.
     *
     * @param expandedTerms 호출자가 이미 확장한 검색어 (null이면 첫 진입자가 keyword로부터 확장;
     *                      keyword로부터 결정적이므로 캐시 정합성에 영향 없음)
     */
    private HybridCoreResult getHybridCoreShared(String keyword, Map<String, Double> expandedTerms) {
        return getHybridCoreShared(keyword, expandedTerms, false);
    }

    /**
     * useMockEmbedding=true(부하테스트 경로)면 캐시 키를 분리해 mock 임베딩 기반 결과가
     * 실사용자 검색에 서빙되지 않게 한다 (VectorSearchService의 "lt:mock:" 키 분리와 같은 이유).
     */
    private HybridCoreResult getHybridCoreShared(
            String keyword, Map<String, Double> expandedTerms, boolean useMockEmbedding) {
        // 캐시 키만 정규화하고 계산에는 원본 키워드를 사용한다
        // (BM25/Vector 검색과 SearchQueryEmbedding 캐시가 원본 표기를 기준으로 동작)
        String cacheKey = (useMockEmbedding ? "lt:mock:" : "") + keyword.toLowerCase().trim();
        CompletableFuture<HybridCoreResult> myFuture = new CompletableFuture<>();
        CompletableFuture<HybridCoreResult> existing =
                hybridCoreCache.asMap().putIfAbsent(cacheKey, myFuture);
        if (existing != null) {
            log.debug("[검색] 하이브리드 코어 합류 - keyword='{}' (in-flight 또는 캐시 히트)", cacheKey);
            try {
                return existing.get(HYBRID_CORE_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // 리더가 future를 완료시키지 못한 상태다. 그대로 두면 TTL(5분)이 끝날 때까지
                // 이 키워드의 모든 요청이 같은 대기를 반복하므로 캐시에서 걷어내 다음 요청이
                // 새 리더가 되게 한다. remove(key, value)는 원자적이라, 그사이 정상 완료한
                // 리더의 결과를 잘못 지우지 않는다(다른 future면 no-op).
                hybridCoreCache.asMap().remove(cacheKey, existing);
                log.warn("하이브리드 코어 결과 대기 타임아웃({}초) - keyword='{}': 캐시에서 축출",
                        HYBRID_CORE_JOIN_TIMEOUT_SECONDS, cacheKey);
                return HybridCoreResult.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("하이브리드 코어 결과 대기 인터럽트 - keyword='{}'", cacheKey);
                return HybridCoreResult.empty();
            } catch (Exception e) {
                String cause = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                log.warn("하이브리드 코어 결과 대기 실패 - keyword='{}': {}", cacheKey, cause);
                return HybridCoreResult.empty();
            }
        }

        // 첫 진입자는 호출자 스레드에서 직접 계산한다 — 별도 스레드로 옮기면 호출자
        // 트랜잭션의 미커밋 데이터가 보이지 않는다 (@Transactional 통합 테스트 포함)
        try {
            HybridCoreResult result = computeHybridCore(keyword, expandedTerms, null, null, useMockEmbedding);
            if (result.nsfScores().isEmpty()) {
                // 빈 결과는 캐시에 남기지 않는다 — 예외 완료된 future는 Caffeine이 자동 제거
                myFuture.completeExceptionally(
                        new IllegalStateException("하이브리드 코어 결과 없음: " + cacheKey));
            } else {
                myFuture.complete(result);
            }
            return result;
        } catch (RuntimeException e) {
            myFuture.completeExceptionally(e);
            throw e;
        } finally {
            // 위 catch가 놓치는 Throwable(OutOfMemoryError, StackOverflowError 등)로 빠져나가도
            // future를 반드시 종료시킨다. 미완료 future가 캐시에 남으면 같은 키워드로 들어오는
            // 후속 요청이 위 조인 경로에서 무한정 대기한다 — 힙 상한이 512m이고 앱 호스트가
            // 상시 스왑 중이라 OOME는 가정이 아니다.
            if (!myFuture.isDone()) {
                myFuture.completeExceptionally(
                        new IllegalStateException("하이브리드 코어 계산 비정상 종료: " + cacheKey));
            }
        }
    }

    /**
     * 하이브리드 코어 캐시 전체 무효화.
     * 검색 가중치 변경 등 캐시된 NSF 점수가 무효해지는 시점, 그리고 롤백 트랜잭션의
     * article ID가 캐시에 남는 통합 테스트(IntegrationTestBase)에서 호출한다.
     */
    public void invalidateHybridCoreCache() {
        hybridCoreCache.synchronous().invalidateAll();
    }

    /**
     * 하이브리드 코어 파이프라인: 쿼리 복잡도 분류 → BM25 쿼리 생성 → BM25/Vector 병렬 검색
     * → cross-scoring 보충 + deleted_at 유효성 검증(한 트랜잭션) → NSF/순위 계산(트랜잭션 밖).
     *
     * single-flight 첫 진입자의 호출 스레드에서 실행된다 (호출자 트랜잭션 컨텍스트 유지).
     * Vector 검색만 searchExecutor에서 병렬 수행.
     */
    private HybridCoreResult computeHybridCore(
            String keyword,
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category) {
        return computeHybridCore(keyword, expandedTerms, regions, category, false);
    }

    private HybridCoreResult computeHybridCore(
            String keyword,
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category,
            boolean useMockEmbedding) {

        long totalStartTime = System.currentTimeMillis();

        // 1. Vector 검색을 가장 먼저 띄운다 — 아래 검색어 확장 쿼리를 Clova 대기에 겹쳐 숨기기 위해.
        //
        // 이 람다가 캡처하는 값은 전부 메서드 파라미터에서 바로 나온다(keyword / regions / category /
        // useMockEmbedding). 아래의 복잡도 분류·검색어 확장·BM25 쿼리 생성 결과에는 전혀 의존하지 않으므로
        // 메서드 맨 앞으로 올릴 수 있다. convertRegionsToTypes도 순수 CPU라 함께 올린다.
        //
        // 이득의 근거: 벡터 경로의 지배 항목은 Clova 임베딩 HTTP 호출(실측 246~658ms)이고, 그 구간에는
        // DB 커넥션을 잡지 않는다(SearchQueryEmbeddingService.getEmbeddingWithCacheInfo에 @Transactional을
        // 일부러 붙이지 않은 이유 — 해당 Javadoc 참고). 그래서 그 뒤에서 도는 유의어 확장 DB 쿼리
        // (실측 150~165ms)와는 DB작업 ∥ HTTP대기로 겹칠 뿐 자원을 다투지 않는다.
        // 요청당 커넥션 체크아웃 횟수와 각 점유 시간은 그대로고 wall time만 줄어든다.
        //
        // 주의: 이 이득은 벡터 경로가 getEmbeddingWithCacheInfo(비트랜잭셔널)를 쓰는 동안에만 성립한다.
        // @Transactional이 붙은 getOrCreateEmbedding으로 바꾸면 Clova 호출 내내 커넥션을 물게 되어
        // 확장 쿼리와 5개짜리 풀을 두고 경합한다(connection-timeout 3초).
        // 또한 vectorSearchService 호출은 반드시 searchExecutor(virtual thread)에 남겨야 한다 —
        // 요청 스레드나 readOnlyTx 안으로 옮기면 앰비언트 트랜잭션에 합류해 같은 회귀가 난다.
        // 트레이스 근거: docs/search/SEARCH_TRACE_ANALYSIS.md A-1.
        List<Integer> vectorDomesticTypes = convertRegionsToTypes(regions);
        List<String> vectorCategories = (category != null && !category.isEmpty()) ? category : null;
        AtomicLong vectorElapsedMs = new AtomicLong(0);
        CompletableFuture<VectorSearchService.VectorSearchResult> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        VectorSearchService.VectorSearchResult result = vectorSearchService.searchByKeywordWithEmbedding(keyword, vectorDomesticTypes, vectorCategories, useMockEmbedding);
                        vectorElapsedMs.set(System.currentTimeMillis() - start);
                        return result;
                    } catch (Exception e) {
                        vectorElapsedMs.set(System.currentTimeMillis() - start);
                        log.warn("Vector 검색 실패 (스킵): {}", e.getMessage());
                        return new VectorSearchService.VectorSearchResult(new HashMap<>(), null);
                    }
                }, searchExecutor);

        // 2. 쿼리 복잡도 감지 → 적응형 BM25 title 배수 및 NSF 가중치 결정 (순수 CPU)
        SemanticTermExpansionService.QueryComplexity complexity =
                semanticExpansionService.classifyQueryComplexity(keyword);
        SearchWeightConfigService.WeightEntry weights = weightConfig.getWeights(complexity);
        double titleMultiplier = weights.titleMultiplier();
        double bm25NsfWeight   = weights.bm25NsfWeight();
        double vectorNsfWeight = weights.vectorNsfWeight();
        log.info("[검색] keyword='{}' 쿼리 복잡도: {} (titleMultiplier={}, BM25={}, Vector={})",
                keyword, complexity, titleMultiplier, bm25NsfWeight, vectorNsfWeight);

        // 3. BM25 검색 쿼리 생성 (나중에 Vector-only article들의 BM25 점수 계산에 재사용)
        // expandedTerms가 null이면 여기 buildBM25SearchQuery 안에서 유의어 확장 DB 쿼리가 돈다 —
        // 위 vectorFuture가 이미 Clova 호출 중이라 이 구간은 임베딩 대기에 겹쳐 숨는다.
        String bm25SearchQuery = (expandedTerms != null && !expandedTerms.isEmpty())
                ? buildBM25SearchQueryFromExpandedTerms(expandedTerms, titleMultiplier)
                : buildBM25SearchQuery(keyword, titleMultiplier);
        if (bm25SearchQuery == null || bm25SearchQuery.isEmpty()) {
            // vectorFuture는 취소하지 않고 흘려보낸다. cancel(true)는 이미 실행 중인 supplyAsync 태스크를
            // 중단시키지 못하고(virtual thread executor라 태스크가 즉시 시작한다) 이름만 그럴싸한 no-op이 된다.
            // 게다가 낭비도 아니다 — 무필터 경로면 vectorSearchResults 캐시를, 어느 경로든
            // search_query_embedding 행을 채워 다음 요청이 Clova를 건너뛴다.
            // 람다에 catch-all이 있어 예외로 완료될 일도 없다(hybridCoreCache 시맨틱 영향 없음).
            log.warn("BM25 검색 쿼리 생성 실패: '{}'", keyword);
            return HybridCoreResult.empty();
        }

        // 4. BM25 검색은 현재 스레드에서 실행 (Vector가 별도 스레드에서 도는 동안 병렬 수행)
        // Phase A: BM25 쿼리만 짧게 트랜잭션으로 감싼다 — 뒤이은 vectorFuture.get() 대기 구간에는
        // 커넥션을 물고 있지 않도록 여기서 트랜잭션을 닫는다
        long bm25StartTime = System.currentTimeMillis();
        // DB가 반환한 점수순을 동점 tie-breaker로 보존한다.
        Map<Long, Double> bm25Results = new LinkedHashMap<>();
        Map<Long, LocalDateTime> publishedAtMap = new HashMap<>();

        readOnlyTx.executeWithoutResult(status -> {
            List<Object[]> bm25RawResults = executeBM25Search(bm25SearchQuery, regions, category);
            for (Object[] row : bm25RawResults) {
                Long articleId = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                // Hibernate 7부터 native 쿼리도 timestamp 컬럼을 LocalDateTime으로 반환
                LocalDateTime publishedAt = row.length > 2 ? (LocalDateTime) row[2] : null;

                if (score != null) {
                    bm25Results.put(articleId, score);
                }
                if (publishedAt != null) {
                    publishedAtMap.put(articleId, publishedAt);
                }
            }
        });
        long bm25EndTime = System.currentTimeMillis();

        // 5. Vector 검색 결과 대기
        // VectorSearchService의 원본 반환 순서를 동점 tie-breaker로 보존한다.
        Map<Long, Double> vectorResults = new LinkedHashMap<>();
        float[] queryEmbedding = null;
        VectorSearchService.VectorSearchResult vectorSearchResult = new VectorSearchService.VectorSearchResult(new HashMap<>(), null);
        try {
            vectorSearchResult = vectorFuture.get(5, TimeUnit.SECONDS);
            vectorResults.putAll(vectorSearchResult.getScores());
            queryEmbedding = vectorSearchResult.getQueryEmbedding();
        } catch (Exception e) {
            log.warn("Vector 검색 결과 대기 실패 (스킵): {}", e.getMessage());
        }

        // 원본 검색 결과 수 캡처 (cross-scoring 보충 전)
        int originalBm25Count = bm25Results.size();
        int originalVectorCount = vectorResults.size();
        Map<Long, Double> sourceBm25Scores = new LinkedHashMap<>(bm25Results);
        Map<Long, Double> sourceVectorScores = new LinkedHashMap<>(vectorResults);

        // 4. 교차 점수 계산: 각 검색 방법에서만 발견된 article에 나머지 점수 보충 (병렬)
        Set<Long> bm25OnlyIds = new HashSet<>(bm25Results.keySet());
        bm25OnlyIds.removeAll(vectorResults.keySet());

        Set<Long> vectorOnlyIds = new HashSet<>(vectorResults.keySet());
        vectorOnlyIds.removeAll(bm25Results.keySet());

        // 4-1. Vector 보충 대상 (BM25-only)
        Set<Long> needVectorIds = new HashSet<>(bm25OnlyIds);

        // 4-2. BM25 보충 대상 (Vector-only)
        Set<Long> needBm25Ids = new HashSet<>(vectorOnlyIds);

        // 4-3. Stage 1 후보 점수 재활용 — DB 왕복 없이 채울 수 있는 만큼 needVectorIds에서 뺀다.
        //
        // 2단계 벡터 검색은 Stage 1 후보(HNSW 200청크)에 등장한 *모든* 아티클의 점수를 이미
        // 계산해놓고 threshold/limit로 잘린 것을 버린다. 그런데 바로 그 아티클들이 BM25-only로
        // 다시 나타나 cross-scoring 대상이 된다 — 같은 값을 두 번째로 계산하려고 청크당 12블록
        // (TOAST 간접 참조)을 읽는 것이다.
        //
        // 재활용은 한쪽 방향으로만 안전하다. 후보 점수는 "그 아티클의 후보 청크 중 상위 topK 평균"
        // 이고 DB 보충값은 "전 청크 중 상위 topK 평균"이라, 후보가 전체의 부분집합인 이상
        // 후보 점수 <= DB 보충값이 항상 성립한다 — 단, 후보 청크가 topK개 이상일 때만.
        // topK 미만이면 AVG의 분모가 1~2로 줄어 오히려 더 높게 나오므로(약한 매칭이 부풀어 오른다)
        // 그런 아티클은 아예 candidateScores에 담기지 않는다(ArticleChunkRepository의 CASE 참고).
        //
        // 그래서 임계값 미만인 후보는 탈락시키지 않고 DB 보충으로 넘긴다: 후보 점수가 과소평가일
        // 수 있어 DB에서는 임계값을 넘길 수 있기 때문이다. 반대로 임계값 이상이면 DB 값은 그보다
        // 크거나 같으므로 통과 여부가 바뀌지 않는다 — 점수만 살짝 보수적으로 잡힐 뿐이고,
        // 그 방향은 본검색 결과(같은 후보 집합에서 계산)와 같아 NSF 정규화에서 일관적이다.
        int reusedCandidateCount = 0;
        if (reuseStage1Candidates && !needVectorIds.isEmpty()) {
            Map<Long, Double> candidateScores = vectorSearchResult.getCandidateScores();
            if (!candidateScores.isEmpty()) {
                double vectorThreshold = vectorSearchService.vectorThresholdFor(useMockEmbedding);
                Iterator<Long> it = needVectorIds.iterator();
                while (it.hasNext()) {
                    Long id = it.next();
                    Double candidateScore = candidateScores.get(id);
                    if (candidateScore == null || candidateScore < vectorThreshold) {
                        continue;   // 후보 밖이거나 과소평가 가능 → DB 보충으로
                    }
                    it.remove();
                    reusedCandidateCount++;
                    vectorResults.put(id, candidateScore);
                }
            }
        }
        int crossScoreDbCount = needVectorIds.size();

        // cross-scoring 보충 → deleted_at 유효성 검증을 하나의 짧은 트랜잭션으로 묶는다
        // (요청당 커넥션 체크아웃은 Phase A + 여기, 총 2회).
        //
        // Phase A와는 별개(재진입)로 연다 — 그 경계는 그대로 유지한다: 이 아래에서 열리는
        // 트랜잭션은 이미 vectorFuture.get()(최대 5초 대기)이 끝난 *뒤*에 시작되므로, 그 대기
        // 구간에는 커넥션을 물지 않는다.
        //
        // 트랜잭션 안에는 DB 작업만 둔다. NSF/순위 계산(순수 CPU)과 [검색] 로그(prod는
        // AsyncAppender 없이 RollingFileAppender 직결 — appender 락 경합 + 파일 write/flush)는
        // 트랜잭션이 닫힌 뒤에 수행한다: 커넥션 풀 총 점유량 = (체크아웃 횟수) × (한 번 머무는
        // 시간)이므로, DB와 무관한 구간을 점유 구간 안에 두면 체크아웃을 줄여 아낀 만큼을 뒤쪽
        // 항이 도로 까먹는다 — 특히 앱 호스트가 CPU 포화 상태면 이 구간이 수십~수백 ms로
        // 늘어난다(SearchQueryEmbeddingService.getEmbeddingWithCacheInfo에서 @Transactional을
        // 제거한 것과 같은 이유 — load-test/results/2026-08-06-search-ramp-limit-finder.md 참고).
        //
        // 유효성 검사 대상 id는 NSF 계산을 기다릴 필요가 없다: NSF 결과의 키셋은
        // bm25Results ∪ vectorResults이고(HybridSearchScorer.calculateNSFScores가 두 맵의 키
        // 합집합을 그대로 채운다), cross-scoring은 이미 반대쪽 맵에 있는 id에만 점수를 보충하므로
        // (needVectorIds ⊆ bm25Results.keySet(), needBm25Ids ⊆ vectorResults.keySet()) 이 합집합을
        // 늘리지 않는다. 즉 후보 집합은 이 시점에 이미 확정돼 있어, 유효성 쿼리를 NSF 앞으로
        // 끌어올려 cross-scoring과 같은 트랜잭션에 넣을 수 있다.
        //
        // Vector 보충 → BM25 보충은 순차 실행: 별도 스레드로 병렬화하면 커넥션을 추가로
        // 열게 되어 HikariCP 풀(5개)을 요청 1건이 더 많이 점유하게 됨 — 둘 다 필요한 경우는 드문
        // edge case이므로 그 지연시간보다 커넥션 풀 절약을 우선한다.
        final float[] cachedEmbedding = queryEmbedding;

        final Set<Long> candidateIds = new HashSet<>(bm25Results.keySet());
        candidateIds.addAll(vectorResults.keySet());
        Set<Long> validArticleIds = new HashSet<>();

        // DB 보충 대상 중 실제로 임계값을 통과한 수. 로그는 트랜잭션 밖에서 찍어야 하므로 밖으로 꺼낸다.
        // 이 값이 cross-scoring 2단계화(항목 B')의 Stage 2 컷을 정하는 근거다 — 보충 대상 N건 중
        // P건만 임계값을 넘긴다면 컷은 P의 상위 분위수만 덮으면 충분하다.
        AtomicInteger supplementPassedCount = new AtomicInteger();

        readOnlyTx.executeWithoutResult(status -> {
            if (!needVectorIds.isEmpty() && cachedEmbedding != null) {
                try {
                    // 임계값은 2단계 벡터 검색과 동일하게 맞춘다 (부하테스트 경로는 완화된 값).
                    // 검색 전용 진입점 — 스위치에 따라 binary 퍼널을 타고, RAG 경로는 여기 안 온다.
                    Map<Long, Double> supplementVectorScores =
                            vectorSearchService.computeSimilarityForSearchCrossScoring(
                                    cachedEmbedding, new ArrayList<>(needVectorIds),
                                    vectorSearchService.vectorThresholdFor(useMockEmbedding));
                    supplementPassedCount.set(supplementVectorScores.size());
                    vectorResults.putAll(supplementVectorScores);
                } catch (Exception e) {
                    log.warn("교차검색 Vector 보충 실패: {}", e.getMessage());
                }
            }

            if (!needBm25Ids.isEmpty()) {
                try {
                    List<Object[]> supplementBm25 =
                            computeBM25ScoreForArticles(bm25SearchQuery, regions, category, new ArrayList<>(needBm25Ids));
                    for (Object[] row : supplementBm25) {
                        Long id = ((Number) row[0]).longValue();
                        Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                        if (score != null) {
                            bm25Results.put(id, score);
                        }
                    }
                } catch (Exception e) {
                    log.warn("교차검색 BM25 보충 실패: {}", e.getMessage());
                }
            }

            // 8. 전체 후보에 대해 (id, publishedAt) 경량 조회
            // - deleted_at 검증 (동기화 지연으로 인한 stale 항목 제거)
            // - vector-only 항목의 publishedAt 보충 (BM25 쿼리에서 수집되지 않은 날짜)
            if (!candidateIds.isEmpty()) {
                List<Object[]> idAndDateRows = articleRepository.findIdAndPublishedAtByIdIn(
                        new ArrayList<>(candidateIds));
                for (Object[] row : idAndDateRows) {
                    Long id = ((Number) row[0]).longValue();
                    validArticleIds.add(id);
                    LocalDateTime publishedAt = row.length > 1 && row[1] != null ? (LocalDateTime) row[1] : null;
                    if (publishedAt != null && !publishedAtMap.containsKey(id)) {
                        publishedAtMap.put(id, publishedAt);
                    }
                }
            }
        });

        // 5-6. Normalized Score Fusion (NSF) 계산 + 순위 계산 (한 번에 수행) — 트랜잭션 밖에서 수행
        // NSF: 각 검색 방법의 점수를 min-max 정규화 후 가중 합산
        // RRF 대비 장점: 원본 점수의 크기 정보를 보존하여, 높은 유사도 결과를 더 정확히 반영
        long rerankStartTime = System.currentTimeMillis();
        Map<Long, Integer> bm25Ranks = calculateRanks(bm25Results);
        Map<Long, Integer> vectorRanks = calculateRanks(vectorResults);
        Map<Long, Integer> sourceBm25Ranks = calculateRanks(sourceBm25Scores.entrySet().stream()
                .filter(e -> validArticleIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new)));
        Map<Long, Integer> sourceVectorRanks = calculateRanks(sourceVectorScores.entrySet().stream()
                .filter(e -> validArticleIds.contains(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, LinkedHashMap::new)));
        HybridSearchScorer.NSFResult nsfResult = hybridSearchScorer.calculateNSFScores(
                bm25Results, vectorResults, bm25NsfWeight, vectorNsfWeight);
        Map<Long, Double> nsfScores = nsfResult.getNsfScores();
        long rerankEndTime = System.currentTimeMillis();

        if (nsfScores.isEmpty()) {
            log.warn("모든 검색 방법에서 결과가 없습니다: '{}'", keyword);
            return HybridCoreResult.empty();
        }

        // 총 소요시간에는 위 유효성 검증 쿼리까지 포함된다 (구조상 로그가 그 뒤로 이동)
        long totalEndTime = System.currentTimeMillis();
        String embeddingCacheInfo = vectorSearchResult.isCacheHit()
                ? String.format("hit(%dms)", vectorSearchResult.getCacheLookupMs())
                : String.format("miss(%dms, lookup %dms)", vectorSearchResult.getEmbeddingMs(), vectorSearchResult.getCacheLookupMs());

        log.info("[검색] keyword='{}' | BM25: {}ms ({}개), Vector: {}ms (embedding: {}, query: {}ms) ({}개), Rerank(NSF): {}ms ({}개), 후보재활용: {}/{}건 (DB보충 {}건, 보충통과 {}건) | 총: {}ms",
            keyword,
            bm25EndTime - bm25StartTime, originalBm25Count,
            vectorElapsedMs.get(), embeddingCacheInfo, vectorSearchResult.getQueryMs(), originalVectorCount,
            rerankEndTime - rerankStartTime, nsfScores.size(),
            reusedCandidateCount, reusedCandidateCount + crossScoreDbCount, crossScoreDbCount,
            supplementPassedCount.get(),
            totalEndTime - totalStartTime);

        log.debug("NSF 결과 {}개 중 유효한 article: {}개", nsfScores.size(), validArticleIds.size());

        return new HybridCoreResult(bm25Results, vectorResults, nsfScores,
                bm25Ranks, vectorRanks, sourceBm25Ranks, sourceVectorRanks,
                nsfResult.getNormalizedBm25(), nsfResult.getNormalizedVector(), nsfResult.getWeightSums(),
                vectorOnlyIds, publishedAtMap, validArticleIds, cachedEmbedding);
    }

    /**
     * 따옴표 검색 (Exact Match): ILIKE 제목 매칭으로 후보 추출 → Vector 유사도 순위 매김
     *
     * 사용자가 "키워드"로 검색 시, 제목에 해당 키워드가 포함된 글만 반환.
     * sort=relevance일 때 Vector 유사도순, 그 외 날짜순 정렬.
     */
    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
    public Page<ArticleSearchResultDto> searchArticlesExactMatch(
            String keyword,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort,
            String ipAddress,
            String username) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return Page.empty();
        }

        long startTime = System.currentTimeMillis();

        // 1. ILIKE 검색으로 제목 매칭 article ID 추출
        List<Object[]> ilikeRawResults = performILIKESearchRaw(keyword.trim(), regions, category);
        if (ilikeRawResults == null || ilikeRawResults.isEmpty()) {
            log.info("[따옴표 검색] keyword='{}' | 결과 없음", keyword);
            return Page.empty();
        }

        List<Long> matchedIds = ilikeRawResults.stream()
                .map(row -> ((Number) row[0]).longValue())
                .collect(Collectors.toList());

        Map<Long, LocalDateTime> publishedAtMap = new HashMap<>();
        for (Object[] row : ilikeRawResults) {
            Long articleId = ((Number) row[0]).longValue();
            LocalDateTime publishedAt = row.length > 1 ? (LocalDateTime) row[1] : null;
            if (publishedAt != null) {
                publishedAtMap.put(articleId, publishedAt);
            }
        }

        // 2. Vector 유사도 계산 (relevance 정렬용)
        Map<Long, Double> vectorScores = new HashMap<>();
        if ("relevance".equals(sort)) {
            try {
                float[] queryEmbedding = embeddingService.generateEmbedding(keyword);
                if (queryEmbedding != null) {
                    vectorScores = vectorSearchService.computeSimilarityForArticlesWithEmbedding(queryEmbedding, matchedIds);
                }
            } catch (Exception e) {
                log.warn("[따옴표 검색] Vector 유사도 계산 실패 (날짜순 fallback): {}", e.getMessage());
            }
        }

        // 3. Article 조회 (Corporation fetch join)
        List<Article> articles = articleRepository.findByIdInWithCorporation(matchedIds);
        Map<Long, Article> articleMap = articles.stream()
                .filter(a -> a.getDeletedAt() == null)
                .collect(Collectors.toMap(Article::getId, a -> a));

        // 4. 정렬
        final Map<Long, Double> finalVectorScores = vectorScores;
        List<Long> sortedIds;

        if ("relevance".equals(sort) && !finalVectorScores.isEmpty()) {
            sortedIds = matchedIds.stream()
                    .filter(articleMap::containsKey)
                    .sorted((a, b) -> Double.compare(
                            finalVectorScores.getOrDefault(b, 0.0),
                            finalVectorScores.getOrDefault(a, 0.0)))
                    .collect(Collectors.toList());
        } else if ("oldest".equals(sort)) {
            sortedIds = matchedIds.stream()
                    .filter(articleMap::containsKey)
                    .sorted((a, b) -> {
                        LocalDateTime dateA = publishedAtMap.getOrDefault(a, LocalDateTime.MIN);
                        LocalDateTime dateB = publishedAtMap.getOrDefault(b, LocalDateTime.MIN);
                        return dateA.compareTo(dateB);
                    })
                    .collect(Collectors.toList());
        } else {
            // latest (기본)
            sortedIds = matchedIds.stream()
                    .filter(articleMap::containsKey)
                    .sorted((a, b) -> {
                        LocalDateTime dateA = publishedAtMap.getOrDefault(a, LocalDateTime.MIN);
                        LocalDateTime dateB = publishedAtMap.getOrDefault(b, LocalDateTime.MIN);
                        return dateB.compareTo(dateA);
                    })
                    .collect(Collectors.toList());
        }

        // 5. 페이징
        int totalResults = sortedIds.size();
        int offset = page * size;
        if (offset >= totalResults) {
            return Page.empty(PageRequest.of(page, size));
        }

        List<Long> pageIds = sortedIds.stream()
                .skip(offset)
                .limit(size)
                .collect(Collectors.toList());

        // 6. 좋아요 상태
        Map<Long, Boolean> likeStatusMap = getLikeStatusMap(pageIds, username, ipAddress);

        // 7. DTO 생성
        List<ArticleSearchResultDto> results = pageIds.stream()
                .map(articleMap::get)
                .filter(a -> a != null)
                .map(article -> {
                    Long articleId = article.getId();
                    Double vectorScore = finalVectorScores.get(articleId);
                    Boolean isLiked = likeStatusMap.getOrDefault(articleId, false);
                    return new ArticleSearchResultDto(article, false, null, vectorScore, vectorScore, null, isLiked);
                })
                .collect(Collectors.toList());

        long endTime = System.currentTimeMillis();
        log.info("[따옴표 검색] keyword='{}' | ILIKE: {}개, 유효: {}개 | 총: {}ms",
                keyword, matchedIds.size(), totalResults, endTime - startTime);

        return new PageImpl<>(results, PageRequest.of(page, size), totalResults);
    }

    /**
     * Term 기반 검색 (유의어 포함)
     * 특정 term으로 검색할 때 유의어도 함께 검색
     *
     * @param termString 검색할 term 문자열
     * @param pageable 페이징 정보
     * @return 검색된 Article 목록
     */
    @Transactional(readOnly = true)
    public Page<Article> searchByTermWithSynonyms(String termString, Pageable pageable) {
        // 1. term 문자열로 Term 엔티티 찾기
        Optional<Term> termOpt = termRepository.findByTermAndTermType(termString, "NNG");
        if (termOpt.isEmpty()) {
            // term이 없으면 빈 결과 반환
            return Page.empty(pageable);
        }

        Term term = termOpt.get();

        // 2. 유의어 포함한 모든 term ID 조회
        List<Long> termIds = termSynonymService.getSynonymTermIds(term.getId());

        // 3. term ID들로 article 검색
        List<Long> articleIds = articleTermRepository.findArticleIdsByTermIds(termIds);

        if (articleIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 4. article ID들로 실제 Article 조회 (페이징 적용)
        List<Article> articles = articleRepository.findAllById(articleIds).stream()
            .filter(article -> article.getDeletedAt() == null)
            .sorted((a1, a2) -> a2.getPublishedAt().compareTo(a1.getPublishedAt()))
            .collect(Collectors.toList());

        // 5. 페이징 처리
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), articles.size());

        if (start > articles.size()) {
            return Page.empty(pageable);
        }

        List<Article> pagedArticles = articles.subList(start, end);
        return new PageImpl<>(pagedArticles, pageable, articles.size());
    }

    /**
     * AI 요약용 하이브리드 상위 아티클 결과: NSF 순 아티클 ID + 재사용 가능한 쿼리 임베딩
     * queryEmbedding은 벡터 검색 실패 시 null (호출부에서 fallback)
     */
    public record HybridTopArticles(List<Long> articleIds, float[] queryEmbedding) {
        public static HybridTopArticles empty() {
            return new HybridTopArticles(List.of(), null);
        }
    }

    /**
     * AI 요약용: 하이브리드 검색(BM25 + Vector NSF 리랭킹) 기준 상위 limit개 아티클 ID 반환
     * Article 로딩, 좋아요, 시간 감쇠 등 불필요한 처리 없이 NSF 스코어 순으로만 반환
     * 결과는 짧은 TTL 캐시로 공유되어 같은 키워드의 반복 요청에서 재계산을 생략
     * (실제 계산은 검색 API와 공유하는 하이브리드 코어 single-flight 캐시를 경유)
     */
    public HybridTopArticles getTopArticleIdsByHybrid(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) return HybridTopArticles.empty();

        String cacheKey = keyword.toLowerCase().trim() + ":" + limit;
        Cache cache = cacheManager != null ? cacheManager.getCache(HYBRID_TOP_ARTICLES_CACHE_NAME) : null;
        if (cache != null) {
            HybridTopArticles cached = cache.get(cacheKey, HybridTopArticles.class);
            if (cached != null) return cached;
        }

        HybridTopArticles result = computeTopArticleIdsByHybrid(keyword, limit);
        if (cache != null && !result.articleIds().isEmpty()) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    private HybridTopArticles computeTopArticleIdsByHybrid(String keyword, int limit) {
        HybridCoreResult core = getHybridCoreShared(keyword, null);
        if (core.nsfScores().isEmpty()) return HybridTopArticles.empty();

        List<Long> topIds = core.nsfScores().entrySet().stream()
                .filter(e -> core.validArticleIds().contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        return new HybridTopArticles(topIds, core.queryEmbedding());
    }

    /**
     * 공개 챗봇용: getTopArticleIdsForRag 결과를 짧은 TTL 캐시(ragTopArticles) 경유로 반환하는 wrapper.
     * 검색 파라미터 전부를 캐시 키에 포함하므로 파라미터가 바뀌어도 안전하다.
     * admin 테스트 페이지 경로는 이 wrapper를 거치지 않고 비캐시 메서드를 직접 호출한다.
     */
    public HybridTopArticles getTopArticleIdsForRagCached(
            String bm25Keywords, String vectorQuery, List<Long> corporationIds,
            int limit, double vectorThreshold) {
        return getTopArticleIdsForRagCached(bm25Keywords, vectorQuery, corporationIds, limit, vectorThreshold, false);
    }

    /** useMockEmbedding=true(부하테스트 경로)면 캐시 키를 분리해 mock 기반 결과가 실사용자에게 서빙되지 않게 한다. */
    public HybridTopArticles getTopArticleIdsForRagCached(
            String bm25Keywords, String vectorQuery, List<Long> corporationIds,
            int limit, double vectorThreshold, boolean useMockEmbedding) {
        String corpKey = (corporationIds == null || corporationIds.isEmpty())
                ? ""
                : corporationIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        String cacheKey = normalizeRagCacheKeyPart(bm25Keywords) + "|" + normalizeRagCacheKeyPart(vectorQuery)
                + "|" + corpKey + "|" + limit + "|" + vectorThreshold + (useMockEmbedding ? "|mock" : "");
        Cache cache = cacheManager != null ? cacheManager.getCache(RAG_TOP_ARTICLES_CACHE_NAME) : null;
        if (cache != null) {
            HybridTopArticles cached = cache.get(cacheKey, HybridTopArticles.class);
            if (cached != null) return cached;
        }

        HybridTopArticles result = getTopArticleIdsForRag(
                bm25Keywords, vectorQuery, corporationIds, limit, vectorThreshold, useMockEmbedding);
        if (cache != null && !result.articleIds().isEmpty()) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    private String normalizeRagCacheKeyPart(String value) {
        return value == null ? "" : value.toLowerCase().trim();
    }

    /**
     * RAG용: 이중 쿼리(BM25 키워드 + 벡터 재작성 문장) 하이브리드 검색 상위 limit개 아티클 ID 반환
     * computeTopArticleIdsByHybrid와 동일한 NSF 병합·cross-scoring 구조에
     * corporationIds 프리필터와 요청별 벡터 임계값을 추가 적용. 캐시는 사용하지 않음 (파라미터 실험용)
     * cross-scoring 보충 + deleted_at 유효성 검증이 한 트랜잭션이고, NSF 계산은 트랜잭션 밖에서 수행된다.
     *
     * @param bm25Keywords    BM25 검색용 키워드 (전처리에서 추출)
     * @param vectorQuery     벡터 검색용 재작성 문장 (전처리에서 생성)
     * @param corporationIds  기업 프리필터 (null/빈이면 전체 검색)
     * @param limit           상위 아티클 수
     * @param vectorThreshold 벡터 유사도 임계값
     */
    public HybridTopArticles getTopArticleIdsForRag(
            String bm25Keywords, String vectorQuery, List<Long> corporationIds,
            int limit, double vectorThreshold) {
        return getTopArticleIdsForRag(bm25Keywords, vectorQuery, corporationIds, limit, vectorThreshold, false);
    }

    /** useMockEmbedding=true(부하테스트 경로)면 벡터 검색 임베딩을 mock 엔드포인트로 생성한다. */
    public HybridTopArticles getTopArticleIdsForRag(
            String bm25Keywords, String vectorQuery, List<Long> corporationIds,
            int limit, double vectorThreshold, boolean useMockEmbedding) {
        if (bm25Keywords == null || bm25Keywords.trim().isEmpty()) return HybridTopArticles.empty();

        SemanticTermExpansionService.QueryComplexity complexity =
                semanticExpansionService.classifyQueryComplexity(bm25Keywords);
        SearchWeightConfigService.WeightEntry weights = weightConfig.getWeights(complexity);

        String bm25SearchQuery = buildBM25SearchQuery(bm25Keywords, weights.titleMultiplier());
        if (bm25SearchQuery == null || bm25SearchQuery.isEmpty()) return HybridTopArticles.empty();

        boolean hasCorpFilter = corporationIds != null && !corporationIds.isEmpty();
        long totalStartTime = System.currentTimeMillis();

        CompletableFuture<VectorSearchService.VectorSearchResult> vectorFuture =
                CompletableFuture.supplyAsync(
                        () -> vectorSearchService.searchForRag(
                                vectorQuery, corporationIds, vectorThreshold, useMockEmbedding),
                        searchExecutor);

        // Phase A: BM25 쿼리만 짧게 트랜잭션으로 감싼다 — 뒤이은 vectorFuture.get() 대기 구간에는
        // 커넥션을 물고 있지 않도록 여기서 트랜잭션을 닫는다
        Map<Long, Double> bm25Results = new HashMap<>();
        readOnlyTx.executeWithoutResult(status -> {
            List<Object[]> bm25Rows;
            try {
                bm25Rows = hasCorpFilter
                        ? articleRepository.searchByBM25WithCorporations(bm25SearchQuery, corporationIds, 100)
                        : articleRepository.searchByBM25(bm25SearchQuery, 100);
            } catch (Exception e) {
                log.error("RAG BM25 검색 실행 실패: {}", e.getMessage(), e);
                bm25Rows = Collections.emptyList();
            }
            for (Object[] row : bm25Rows) {
                Long id = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (score != null) bm25Results.put(id, score);
            }
        });

        Map<Long, Double> vectorResults = new HashMap<>();
        float[] queryEmbedding = null;
        String embeddingInfo = "n/a";
        long vectorQueryMs = -1;
        int vectorCandidateArticles = -1;
        try {
            VectorSearchService.VectorSearchResult vsr = vectorFuture.get(5, TimeUnit.SECONDS);
            vectorResults.putAll(vsr.getScores());
            vectorCandidateArticles = vsr.getCandidateArticles();
            queryEmbedding = vsr.getQueryEmbedding();
            embeddingInfo = vsr.isCacheHit()
                    ? String.format("hit(%dms)", vsr.getCacheLookupMs())
                    : String.format("miss(%dms, lookup %dms)", vsr.getEmbeddingMs(), vsr.getCacheLookupMs());
            vectorQueryMs = vsr.getQueryMs();
        } catch (Exception e) {
            log.warn("RAG Vector 검색 실패: {}", e.getMessage());
        }
        int originalBm25Count = bm25Results.size();
        int originalVectorCount = vectorResults.size();

        Set<Long> bm25OnlyIds = new HashSet<>(bm25Results.keySet());
        bm25OnlyIds.removeAll(vectorResults.keySet());
        Set<Long> vectorOnlyIds = new HashSet<>(vectorResults.keySet());
        vectorOnlyIds.removeAll(bm25Results.keySet());

        // cross-scoring — 대상 id들이 이미 corp 필터를 통과했으므로 필터 없는 보충 쿼리 재사용.
        // computeHybridCore와 동일한 이유로 Vector 보충 → BM25 보충을 순차 실행하며, Phase A와는
        // 별개(재진입)의 트랜잭션으로 연다(Vector future 대기 구간에는 커넥션을 물지 않기 위해 — 이 경계는
        // 그대로 유지).
        //
        // 유효성 검사도 같은 트랜잭션에서 이어서 수행해 요청당 커넥션 체크아웃을 2회로 유지하되,
        // NSF 계산(순수 CPU)은 트랜잭션 밖으로 뺀다 — 후보 id 집합(bm25Results ∪ vectorResults)은
        // cross-scoring이 늘리지 않으므로 NSF 결과를 기다릴 필요가 없다(computeHybridCore와 동일한
        // 근거, load-test/results/2026-08-06-search-ramp-limit-finder.md 참고).
        final float[] cachedEmbedding = queryEmbedding;

        final Set<Long> candidateIds = new HashSet<>(bm25Results.keySet());
        candidateIds.addAll(vectorResults.keySet());
        Set<Long> validIds = new HashSet<>();

        readOnlyTx.executeWithoutResult(status -> {
            if (!bm25OnlyIds.isEmpty() && cachedEmbedding != null) {
                try {
                    // 보충도 본검색(searchForRag)과 같은 기준을 써야 한다 — 부하테스트 경로에서는
                    // VectorSearchService가 요청 임계값 대신 부하테스트 임계값으로 갈아끼운다.
                    vectorResults.putAll(vectorSearchService.computeSimilarityForArticlesWithEmbedding(
                            cachedEmbedding, new ArrayList<>(bm25OnlyIds), vectorThreshold, useMockEmbedding));
                } catch (Exception e) {
                    log.warn("RAG 교차검색 Vector 보충 실패: {}", e.getMessage());
                }
            }

            if (!vectorOnlyIds.isEmpty()) {
                try {
                    for (Object[] row : computeBM25ScoreForArticles(bm25SearchQuery, null, null, new ArrayList<>(vectorOnlyIds))) {
                        Long id = ((Number) row[0]).longValue();
                        Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                        if (score != null) bm25Results.put(id, score);
                    }
                } catch (Exception e) {
                    log.warn("RAG 교차검색 BM25 보충 실패: {}", e.getMessage());
                }
            }

            // 유효성 검사 — cross-scoring과 이어지는 같은 트랜잭션 안에서 수행(사이에 인메모리 연산·
            // async 대기·외부 호출 없음)
            if (!candidateIds.isEmpty()) {
                for (Object[] row : articleRepository.findIdAndPublishedAtByIdIn(new ArrayList<>(candidateIds))) {
                    validIds.add(((Number) row[0]).longValue());
                }
            }
        });

        // NSF 계산 — 순수 CPU 연산이므로 트랜잭션(커넥션 점유) 밖에서 수행한다
        HybridSearchScorer.NSFResult nsfResult = hybridSearchScorer.calculateNSFScores(
                bm25Results, vectorResults, weights.bm25NsfWeight(), weights.vectorNsfWeight());
        Map<Long, Double> nsfScores = nsfResult.getNsfScores();

        // 검색 경로의 [검색] 로그와 같은 역할 — "무엇이 실제로 돌았는가"를 한 줄로 드러낸다.
        // 검색은 이 로그가 있었는데도 안 봐서 벡터가 꺼진 채 5회를 측정했고(9.7 교훈 2항),
        // RAG는 아예 이 줄이 없었다. Vector가 (0개)면 임계값·mock 기동을 먼저 의심할 것.
        // 프리픽스를 [RAG검색]으로 둬 Grafana의 [검색] Loki 파서와 섞이지 않게 한다.
        //
        // corp / 후보 두 항목은 "기업 지목 질의에서 벡터 후보가 굶는가"를 운영에서 가르기 위한 축이다.
        // 이게 없던 동안은 로그만으로 필터 걸린 요청과 안 걸린 요청을 구분할 수 없었고,
        // Vector가 적을 때 임계값 탓인지 후보 고갈 탓인지도 갈리지 않았다.
        // 필터가 HNSW 후보 추출(ORDER BY ... LIMIT candidateLimit) 위에 놓여 있어,
        // 기업 청크가 코퍼스의 몇 %인지에 따라 후보가 굶는다 — 그래서 corp id를 그대로 찍는다
        // (사후에 그 기업의 코퍼스 점유율과 대조해야 띠 안인지 밖인지 판정할 수 있다).
        // 후보 < Vector 로 보이면 임계값이 아니라 후보 쪽을 의심할 것.
        log.info("[RAG검색] keywords='{}' | mock={} threshold={} corp={}{} | BM25: {}개, "
                        + "Vector: {}개 (후보 {}개, embedding: {}, query: {}ms), "
                        + "보충: BM25-only {}건/Vector-only {}건, NSF: {}개 | 총: {}ms",
                bm25Keywords, useMockEmbedding, vectorThreshold,
                hasCorpFilter ? corporationIds.size() : 0,
                hasCorpFilter ? corporationIds : "",
                originalBm25Count, originalVectorCount, vectorCandidateArticles,
                embeddingInfo, vectorQueryMs,
                bm25OnlyIds.size(), vectorOnlyIds.size(),
                nsfScores.size(), System.currentTimeMillis() - totalStartTime);

        if (nsfScores.isEmpty()) {
            // nsfScores가 비어도 embedding은 보존한다 — computeHybridCore(HybridCoreResult.empty())와
            // 달리 이 경로는 호출자가 이미 계산한 cachedEmbedding을 재사용할 수 있게 남겨둔다.
            return new HybridTopArticles(List.of(), cachedEmbedding);
        }

        List<Long> topIds = nsfScores.entrySet().stream()
                .filter(e -> validIds.contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        return new HybridTopArticles(topIds, cachedEmbedding);
    }

    /**
     * 여러 term으로 검색 (유의어 포함)
     *
     * @param termStrings 검색할 term 문자열 목록
     * @param pageable 페이징 정보
     * @return 검색된 Article 목록
     */
    @Transactional(readOnly = true)
    public Page<Article> searchByTermsWithSynonyms(List<String> termStrings, Pageable pageable) {
        // 1. term 문자열들로 Term 엔티티 찾기
        List<Long> allTermIds = new ArrayList<>();
        for (String termString : termStrings) {
            Optional<Term> termOpt = termRepository.findByTermAndTermType(termString, "NNG");
            if (termOpt.isPresent()) {
                // 유의어 포함한 term ID들 추가
                allTermIds.addAll(termSynonymService.getSynonymTermIds(termOpt.get().getId()));
            }
        }

        if (allTermIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 2. 중복 제거
        List<Long> uniqueTermIds = allTermIds.stream().distinct().collect(Collectors.toList());

        // 3. term ID들로 article 검색
        List<Long> articleIds = articleTermRepository.findArticleIdsByTermIds(uniqueTermIds);

        if (articleIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 4. article ID들로 실제 Article 조회 (페이징 적용)
        List<Article> articles = articleRepository.findAllById(articleIds).stream()
            .filter(article -> article.getDeletedAt() == null)
            .sorted((a1, a2) -> a2.getPublishedAt().compareTo(a1.getPublishedAt()))
            .collect(Collectors.toList());

        // 5. 페이징 처리
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), articles.size());

        if (start > articles.size()) {
            return Page.empty(pageable);
        }

        List<Article> pagedArticles = articles.subList(start, end);
        return new PageImpl<>(pagedArticles, pageable, articles.size());
    }

    /**
     * Helper method to get like status map based on user authentication
     * If username is provided (logged in), use UserLikeService
     * Otherwise, use LikeService with IP address
     */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> getLikeStatusMap(List<Long> articleIds, String username, String ipAddress) {
        if (username != null && !username.trim().isEmpty()) {
            // Logged in user - use UserLikeService
            return userLikeService.getLikeStatusBatchByUser(articleIds, username);
        } else {
            // Anonymous user - use LikeService with IP
            return userLikeService.getLikeStatusBatchByIp(articleIds, ipAddress);
        }
    }

    /**
     * 키워드로부터 유의어를 포함한 Term 기반 Article ID 목록 조회
     *
     * @param keyword 검색 키워드
     * @return 유의어를 포함한 Term과 연결된 Article ID 목록
     */
    @Transactional(readOnly = true)
    public List<Long> getArticleIdsByKeywordWithSynonyms(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }

        // 1. 키워드를 형태소 분석하여 Term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap = morphemeAnalyzer.extractTerms(keyword);

        if (termMap.isEmpty()) {
            return null;
        }

        // 2. 추출된 Term들의 ID 조회 (termType 무관하게 모든 매칭되는 term 찾기)
        // term별로 쿼리를 날리는 대신 대소문자 변형까지 모아 IN 조회 1회로 처리한다.
        // (기존은 정확 매칭이 비어야 lower → upper 순으로 fallback했으나, IN 조회는 매칭되는 변형을
        //  모두 가져온다. term 테이블은 V1_16에서 전부 소문자화됐고 이후 삽입도 소문자이므로
        //  실데이터에서는 동일 결과 — 대소문자가 섞인 행이 있으면 재현율이 넓어지는 방향이다.)
        Set<String> termCandidates = new LinkedHashSet<>();
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            String term = termInfo.getTerm();
            termCandidates.add(term);

            if (termInfo.getTermType().equals("SL")) {
                termCandidates.add(term.toLowerCase());
                termCandidates.add(term.toUpperCase());
            }
        }

        List<Long> termIds = termRepository.findByTermIn(termCandidates).stream()
                .map(Term::getId)
                .toList();

        if (termIds.isEmpty()) {
            return null;
        }

        // 3. 유의어를 포함한 전체 Term ID 목록 조회
        List<Long> expandedTermIds = termSynonymService.expandTermIdsWithSynonyms(termIds);

        if (expandedTermIds.isEmpty()) {
            return null;
        }

        // 4. Term ID 목록으로 Article ID 조회
        List<Long> articleIds = articleTermRepository.findArticleIdsByTermIds(expandedTermIds);

        return articleIds.isEmpty() ? null : articleIds;
    }

    /**
     * BM25 검색 쿼리 문자열 생성 (기본 title 배수 사용)
     * 검색어를 의미적으로 확장하여 BM25 쿼리 문자열 반환
     *
     * @param keyword 검색 키워드
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQuery(String keyword) {
        return buildBM25SearchQuery(keyword, 1.5);
    }

    /**
     * BM25 검색 쿼리 문자열 생성 (적응형 title 배수)
     * 검색어를 의미적으로 확장하여 BM25 쿼리 문자열 반환
     *
     * @param keyword         검색 키워드
     * @param titleMultiplier title_terms 부스트 배수
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQuery(String keyword, double titleMultiplier) {
        try {
            Map<String, Double> expandedTerms = semanticExpansionService.expandSearchTerms(keyword);

            if (expandedTerms.isEmpty()) {
                log.warn("검색어 '{}' 확장 결과가 비어있습니다.", keyword);
                return null;
            }

            return buildBM25SearchQueryFromExpandedTerms(expandedTerms, titleMultiplier);

        } catch (Exception e) {
            log.error("BM25 검색 쿼리 생성 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 이미 확장된 검색어로 BM25 검색 쿼리 문자열 생성 (기본 title 배수)
     * 호출자가 다른 이유로 확장 결과를 이미 갖고 있는 경우(ArticlePageController의 관리자 디버그 패널,
     * SearchPrewarmScheduler의 프리워밍 Step 2)에만 쓴다 — 중복 호출 방지용.
     * 핫 API 경로(ArticleSearchController)는 일부러 null을 넘긴다: 확장을 컨트롤러에서 미리 부르면
     * computeHybridCore의 Clova 임베딩 호출보다 먼저 돌아 직렬 구간이 되기 때문이다
     * (docs/search/SEARCH_TRACE_ANALYSIS.md A-1).
     *
     * @param expandedTerms 확장된 검색어와 가중치 맵
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQueryFromExpandedTerms(Map<String, Double> expandedTerms) {
        return buildBM25SearchQueryFromExpandedTerms(expandedTerms, 1.5);
    }

    /**
     * 이미 확장된 검색어로 BM25 검색 쿼리 문자열 생성 (적응형 title 배수)
     * 호출자가 다른 이유로 확장 결과를 이미 갖고 있는 경우(ArticlePageController의 관리자 디버그 패널,
     * SearchPrewarmScheduler의 프리워밍 Step 2)에만 쓴다 — 중복 호출 방지용.
     * 핫 API 경로(ArticleSearchController)는 일부러 null을 넘긴다: 확장을 컨트롤러에서 미리 부르면
     * computeHybridCore의 Clova 임베딩 호출보다 먼저 돌아 직렬 구간이 되기 때문이다
     * (docs/search/SEARCH_TRACE_ANALYSIS.md A-1).
     *
     * @param expandedTerms   확장된 검색어와 가중치 맵
     * @param titleMultiplier title_terms 부스트 배수
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQueryFromExpandedTerms(Map<String, Double> expandedTerms, double titleMultiplier) {
        String query = hybridSearchScorer.buildBM25Query(expandedTerms, titleMultiplier);
        log.debug("BM25 검색 쿼리 생성 완료 - Term 수: {}", expandedTerms != null ? expandedTerms.size() : 0);
        return query;
    }

    /**
     * 생성된 BM25 쿼리로 검색 실행
     *
     * @param searchQuery BM25 검색 쿼리
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @return 검색 결과 (id, score, published_at)
     */
    private List<Object[]> executeBM25Search(String searchQuery, List<String> regions, List<String> category) {
        // private 메서드라 @Observed 프록시가 닿지 않아 수동 Observation으로 구간 기록
        return Observation.createNotStarted("search.bm25", observationRegistry)
                .contextualName("bm25-search")
                .observe(() -> doExecuteBM25Search(searchQuery, regions, category));
    }

    private List<Object[]> doExecuteBM25Search(String searchQuery, List<String> regions, List<String> category) {
        try {
            List<Integer> domesticTypes = convertRegionsToTypes(regions);
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

            List<Object[]> results;
            boolean hasDomesticTypes = domesticTypes != null && !domesticTypes.isEmpty();
            boolean hasCategory = safeCategory != null && !safeCategory.isEmpty();

            if (hasDomesticTypes && hasCategory) {
                results = articleRepository.searchByBM25WithBothFilters(searchQuery, domesticTypes, safeCategory, 100);
            } else if (hasDomesticTypes) {
                results = articleRepository.searchByBM25WithDomesticTypes(searchQuery, domesticTypes, 100);
            } else if (hasCategory) {
                results = articleRepository.searchByBM25WithCategory(searchQuery, safeCategory, 100);
            } else {
                results = articleRepository.searchByBM25(searchQuery, 100);
            }

            return results;

        } catch (Exception e) {
            log.error("BM25 검색 실행 실패: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 특정 Article ID들에 대한 BM25 점수 계산 (executeBM25Search와 동일한 쿼리 구조)
     * regions/category 필터도 동일하게 적용
     */
    private List<Object[]> computeBM25ScoreForArticles(String searchQuery, List<String> regions, List<String> category, List<Long> articleIds) {
        return Observation.createNotStarted("search.bm25", observationRegistry)
                .contextualName("bm25-supplement")
                .observe(() -> doComputeBM25ScoreForArticles(searchQuery, regions, category, articleIds));
    }

    private List<Object[]> doComputeBM25ScoreForArticles(String searchQuery, List<String> regions, List<String> category, List<Long> articleIds) {
        try {
            List<Integer> domesticTypes = convertRegionsToTypes(regions);
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

            List<Object[]> results;
            boolean hasDomesticTypes = domesticTypes != null && !domesticTypes.isEmpty();
            boolean hasCategory = safeCategory != null && !safeCategory.isEmpty();

            if (hasDomesticTypes && hasCategory) {
                results = articleRepository.computeBM25ScoreForArticleIdsWithBothFilters(searchQuery, domesticTypes, safeCategory, articleIds);
            } else if (hasDomesticTypes) {
                results = articleRepository.computeBM25ScoreForArticleIdsWithDomesticTypes(searchQuery, domesticTypes, articleIds);
            } else if (hasCategory) {
                results = articleRepository.computeBM25ScoreForArticleIdsWithCategory(searchQuery, safeCategory, articleIds);
            } else {
                results = articleRepository.computeBM25ScoreForArticleIds(searchQuery, articleIds);
            }

            return results;

        } catch (Exception e) {
            log.error("BM25 보충 점수 계산 실패: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 스코어 맵을 순위 맵으로 변환
     * 스코어 내림차순으로 순위 부여 (1부터 시작)
     */
    private Map<Long, Integer> calculateRanks(Map<Long, Double> scoreMap) {
        Map<Long, Integer> ranks = new HashMap<>();

        List<Map.Entry<Long, Double>> sortedEntries = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .toList();

        int rank = 1;
        for (Map.Entry<Long, Double> entry : sortedEntries) {
            ranks.put(entry.getKey(), rank++);
        }

        return ranks;
    }

    /**
     * 키워드 검색 수행 (BM25 + ILIKE 폴백)
     * BM25: ArticleTerm 기반 정제 키워드 검색 (주 검색)
     * ILIKE: 제목 직접 매칭 (보조 검색)
     */
    private List<Article> performKeywordSearch(String keyword, List<String> regions, List<String> category) {
        Set<Long> articleIds = new HashSet<>();

        // 1. BM25 검색 (ArticleTerm 기반)
        List<Long> bm25Ids = performBM25Search(keyword, regions, category);
        if (bm25Ids != null && !bm25Ids.isEmpty()) {
            articleIds.addAll(bm25Ids);
        }

        // 2. ILIKE 폴백 (BM25로 못 찾은 경우 또는 보완)
        // 제목에 키워드가 직접 포함된 경우 (고유명사, ArticleTerm에 없는 단어)
        List<Object[]> ilikeRawResults = performILIKESearchRaw(keyword, regions, category);
        if (ilikeRawResults != null && !ilikeRawResults.isEmpty()) {
            for (Object[] row : ilikeRawResults) {
                Long articleId = ((Number) row[0]).longValue();
                articleIds.add(articleId);
            }
        }

        // 3. Article 조회
        if (articleIds.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(articleRepository.findAllById(articleIds));
    }

    /**
     * BM25 검색 수행 (ArticleTerm 기반)
     */
    private List<Long> performBM25Search(String keyword, List<String> regions, List<String> category) {
        Map<Long, Double> results = performBM25SearchWithScores(keyword, regions, category);
        return new ArrayList<>(results.keySet());
    }

    /**
     * BM25 검색 수행 (raw 결과 반환: id, score, published_at)
     * latest/oldest 정렬 시 published_at 정보가 필요할 때 사용
     */
    private List<Object[]> performBM25SearchRaw(String keyword, List<String> regions, List<String> category) {
        String searchQuery = buildBM25SearchQuery(keyword);
        if (searchQuery == null || searchQuery.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object[]> results = executeBM25Search(searchQuery, regions, category);
        log.debug("BM25 raw 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, results.size());
        return results;
    }

    /**
     * BM25 검색 수행 (스코어 포함, ArticleTerm 기반)
     * 의미적 Term 확장 + Boost 가중치 적용
     */
    private Map<Long, Double> performBM25SearchWithScores(String keyword, List<String> regions, List<String> category) {
        String searchQuery = buildBM25SearchQuery(keyword);
        if (searchQuery == null || searchQuery.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Object[]> results = executeBM25Search(searchQuery, regions, category);

        // ID, 스코어 추출
        Map<Long, Double> scoreMap = new HashMap<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            scoreMap.put(articleId, score);
        }

        log.debug("BM25 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, scoreMap.size());
        return scoreMap;
    }

    /**
     * BM25 검색 수행 (확장된 검색어 직접 사용, 스코어 포함)
     * expandedTerms를 직접 받아서 BM25 검색 수행
     */
    private Map<Long, Double> performBM25SearchWithExpandedTerms(
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category) {
        try {
            if (expandedTerms == null || expandedTerms.isEmpty()) {
                log.warn("expandedTerms가 비어있습니다.");
                return Collections.emptyMap();
            }

            // 1. 직접 매칭 Term과 확장 Term 분리
            List<String> directMatchTerms = new ArrayList<>();
            Map<String, Double> expandedOnlyTerms = new LinkedHashMap<>();

            for (Map.Entry<String, Double> entry : expandedTerms.entrySet()) {
                if (entry.getValue() == 1.0) {
                    directMatchTerms.add(entry.getKey());
                } else {
                    expandedOnlyTerms.put(entry.getKey(), entry.getValue());
                }
            }

            // 2. 가중치 기반 BM25 쿼리 생성
            StringBuilder queryBuilder = new StringBuilder();

            // 2-1. 개별 직접 매칭 Term (OR 절, 높은 우선순위)
            for (String term : directMatchTerms) {
                hybridSearchScorer.appendBoostedTerm(queryBuilder, term, "2.0");
            }

            // 2-2. 유의어 및 임베딩 유사어 (OR 절, 낮은 우선순위)
            for (Map.Entry<String, Double> entry : expandedOnlyTerms.entrySet()) {
                String boostValue = String.format("%.1f", entry.getValue() * 2.0);
                hybridSearchScorer.appendBoostedTerm(queryBuilder, entry.getKey(), boostValue);
            }

            String searchQuery = queryBuilder.toString();
            log.debug("BM25 검색 쿼리 생성 - 직접 Term: {}, 확장 Term: {}", directMatchTerms, expandedOnlyTerms.keySet());
            log.debug("BM25 검색 쿼리: {}", searchQuery);

            // 3. 지역 필터 변환
            List<Integer> domesticTypes = convertRegionsToTypes(regions);
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

            // 4. BM25 검색 실행 (필터 조합에 따라 적절한 쿼리 호출)
            List<Object[]> results;
            boolean hasDomesticTypes = domesticTypes != null && !domesticTypes.isEmpty();
            boolean hasCategory = safeCategory != null && !safeCategory.isEmpty();

            if (hasDomesticTypes && hasCategory) {
                // 두 필터 모두 사용
                results = articleRepository.searchByBM25WithBothFilters(searchQuery, domesticTypes, safeCategory, 100);
            } else if (hasDomesticTypes) {
                // domesticTypes만 사용
                results = articleRepository.searchByBM25WithDomesticTypes(searchQuery, domesticTypes, 100);
            } else if (hasCategory) {
                // category만 사용
                results = articleRepository.searchByBM25WithCategory(searchQuery, safeCategory, 100);
            } else {
                // 필터 없음
                results = articleRepository.searchByBM25(searchQuery, 100);
            }

            // 5. ID와 스코어 추출
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                scoreMap.put(articleId, score);
            }

            log.debug("BM25 검색 완료 - 확장된 Term 수: {}, 결과 수: {}",
                    expandedTerms.size(), scoreMap.size());

            return scoreMap;

        } catch (Exception e) {
            log.error("BM25 검색 실패: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * ILIKE 검색 수행 (폴백)
     * 제목에 키워드가 직접 포함된 경우 검색 (고유명사, 회사명 등)
     *
     * NOTE: BM25가 이미 Term 기반 검색을 수행하므로,
     *       이 메서드는 순수하게 title/translatedTitle LIKE 매칭만 수행합니다.
     *
     * 메모리 최적화: ID와 published_at만 조회 (전체 Article 엔티티 로드 안 함)
     * 성능 최적화: 최대 100개로 제한
     *
     * @param keyword 검색 키워드
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @return [Article ID, published_at] 형태의 Object[] 리스트
     */
    private List<Object[]> performILIKESearchRaw(String keyword, List<String> regions, List<String> category) {
        // 순수한 제목 직접 매칭만 수행 (ILIKE 본연의 역할)
        List<Integer> domesticTypes = convertRegionsToTypes(regions);
        String searchPattern = "%" + keyword + "%";
        List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

        // Safe 리스트 생성 (빈 리스트 대신 null 방지)
        List<Integer> safeDomesticTypes = domesticTypes != null ? domesticTypes : new ArrayList<>();
        int domesticTypesSize = safeDomesticTypes.size();

        List<String> safeCategorySized = safeCategory != null ? safeCategory : new ArrayList<>();
        int categorySize = safeCategorySized.size();

        // 경량 쿼리 사용: ID와 published_at만 조회 (최대 100개)
        return articleRepository.findArticleIdsWithPublishedAtByFilters(
                searchPattern, safeDomesticTypes, domesticTypesSize, safeCategorySized, categorySize);
    }

    /**
     * 지역 필터를 domesticType으로 변환
     */
    private List<Integer> convertRegionsToTypes(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }

        List<Integer> domesticTypes = new ArrayList<>();
        if (regions.contains("domestic")) {
            domesticTypes.add(1);
        }
        if (regions.contains("overseas")) {
            domesticTypes.add(0);
        }

        return domesticTypes.isEmpty() ? null : domesticTypes;
    }

    /**
     * Article 리스트에 필터 적용
     */
    private List<Article> filterArticles(List<Article> articles, List<String> regions, List<String> category) {
        return articles.stream()
                .filter(article -> matchesRegion(article, regions))
                .filter(article -> matchesCategory(article, category))
                .collect(Collectors.toList());
    }

    /**
     * 지역 필터 매칭
     */
    private boolean matchesRegion(Article article, List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return true;
        }

        boolean isDomestic = article.getCorporation().getIsDomestic();
        return (regions.contains("domestic") && isDomestic) ||
               (regions.contains("overseas") && !isDomestic);
    }

    /**
     * 카테고리 필터 매칭
     */
    private boolean matchesCategory(Article article, List<String> category) {
        if (category == null || category.isEmpty()) {
            return true;
        }

        if (article.getCategory() == null) {
            return false;
        }

        return category.contains(article.getCategory().getName());
    }


    /**
     * 검색 결과 정렬
     */
    private List<ArticleSearchResultDto> sortResults(List<ArticleSearchResultDto> results, String sort) {
        if (sort == null || sort.equals("latest")) {
            // 최신순: 발행일 내림차순
            results.sort((a, b) -> {
                LocalDateTime aDate = ((ArticleListResponseDto) a).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) a).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                LocalDateTime bDate = ((ArticleListResponseDto) b).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) b).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                return bDate.compareTo(aDate);
            });
        } else if (sort.equals("relevance")) {
            // 적합도순: BM25 스코어 내림차순, 스코어 없으면 최신순
            results.sort((a, b) -> {
                Double aScore = a.getBm25Score();
                Double bScore = b.getBm25Score();

                // 둘 다 BM25 스코어가 있으면 스코어로 비교 (높을수록 우선)
                if (aScore != null && bScore != null) {
                    return Double.compare(bScore, aScore);
                }

                // a만 스코어가 있으면 a가 우선
                if (aScore != null) {
                    return -1;
                }

                // b만 스코어가 있으면 b가 우선
                if (bScore != null) {
                    return 1;
                }

                // 둘 다 스코어가 없으면 최신순으로 정렬
                LocalDateTime aDate = ((ArticleListResponseDto) a).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) a).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                LocalDateTime bDate = ((ArticleListResponseDto) b).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) b).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                return bDate.compareTo(aDate);
            });
        } else if (sort.equals("popular")) {
            // 인기순: 조회수와 좋아요 기반
            results.sort((a, b) -> {
                double aScore = (a.getViewCount() != null ? a.getViewCount() : 0) * 0.6 +
                               (a.getLikeCount() != null ? a.getLikeCount() : 0) * 0.3;
                double bScore = (b.getViewCount() != null ? b.getViewCount() : 0) * 0.6 +
                               (b.getLikeCount() != null ? b.getLikeCount() : 0) * 0.3;
                return Double.compare(bScore, aScore);
            });
        } else if (sort.equals("oldest")) {
            // 오래된순: 발행일 오름차순
            results.sort((a, b) -> {
                LocalDateTime aDate = ((ArticleListResponseDto) a).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) a).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MAX;
                LocalDateTime bDate = ((ArticleListResponseDto) b).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) b).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MAX;
                return aDate.compareTo(bDate);
            });
        }

        return results;
    }

    /**
     * 결과 페이징
     */
    private Page<ArticleSearchResultDto> paginateResults(List<ArticleSearchResultDto> results, int page, int size) {
        int start = page * size;
        int end = Math.min(start + size, results.size());

        if (start >= results.size()) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), results.size());
        }

        List<ArticleSearchResultDto> pageContent = results.subList(start, end);
        return new PageImpl<>(pageContent, PageRequest.of(page, size), results.size());
    }
}
