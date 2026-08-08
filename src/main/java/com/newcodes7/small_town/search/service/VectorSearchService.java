package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import io.micrometer.observation.annotation.Observed;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * 벡터 검색 서비스
 *
 * 2단계 검색:
 * - Stage 1: Binary HNSW (빠른 후보 필터링)
 * - Stage 2: halfvec Reranking (정밀 유사도 계산)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchService {

    private final ArticleChunkRepository chunkRepository;
    private final SearchQueryEmbeddingService searchQueryEmbeddingService;
    private final CacheManager cacheManager;
    @Qualifier("searchExecutor")
    private final ExecutorService searchExecutor;

    private static final String CHUNK_CACHE_NAME = "chunkSearchResults";
    private static final String VECTOR_SEARCH_CACHE_NAME = "vectorSearchResults";
    private static final int SUMMARY_CHUNK_LIMIT = 10;
    private static final int SUMMARY_MAX_CHUNKS_PER_ARTICLE = 2;
    private static final int SUMMARY_FETCH_MULTIPLIER = 4;

    private static final int SINGLE_TERM_MAX_CORPS = 3;
    private static final int SINGLE_TERM_FETCH_LIMIT = 20;

    // 기본 유사도 임계값
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.52;

    /**
     * 부하테스트 경로(useMockEmbedding=true) 전용 유사도 임계값. 기본 0.0.
     *
     * mock Clova(load-test/mock ClovaHandler)는 텍스트 시드 기반 의사난수 단위벡터를 반환하므로
     * 실제 문서 임베딩과의 코사인 유사도가 N(0, 1/sqrt(1024)) ≈ N(0, 0.031)에 몰린다 —
     * 코퍼스 전체 최대값도 0.1 남짓이라 운영 임계값 0.52로는 **항상 0건**이 나온다.
     * 그러면 cross-scoring의 BM25 보충 분기(vectorOnlyIds)가 아예 실행되지 않고 NSF 입력도
     * BM25 단독이 되어, 정작 측정하려는 코드 경로의 절반이 부하에서 안 돈다.
     *
     * 0.0으로 두면 필터가 사실상 해제돼 stage-1 후보와 DEFAULT_MAX_RESULTS가 개수를 결정하므로
     * 실행 간 결과 수가 안정적이다. HNSW 순회·halfvec 재랭킹 비용은 임계값과 무관하게 이미
     * 수행되므로 쿼리 비용 자체는 왜곡되지 않는다.
     *
     * 주의: 어떤 문서가 돌아오는지는 여전히 무작위다 — 커넥션 점유·쿼리 비용·처리량 측정에만
     * 쓸 수 있고 검색 품질·랭킹·가중치 튜닝의 근거로는 쓸 수 없다.
     */
    @Value("${search.loadtest.vector-threshold:0.0}")
    private double loadTestVectorThreshold;

    /**
     * 부하테스트 경로 전용 벡터 결과 수 상한. 기본 30.
     *
     * 임계값을 0.0으로 풀면 벡터 결과가 항상 DEFAULT_MAX_RESULTS(100)를 꽉 채우는데, 운영은
     * 임계값 0.52가 의미적으로 걸러 훨씬 적다 — 실측(2026-08-08, 벡터 성공 12건 표본) 중앙값 28건.
     * 100건이 그대로 흘러가면 cross-scoring BM25 보충 대상(vectorOnlyIds)이 운영 중앙값 14건 대비
     * 100건으로 부풀고 NSF 합집합도 200건이 되어(운영 127~173) 테스트가 실제보다 무거워진다.
     *
     * 30(운영 중앙값 28 기준)으로 제한하면 needBm25Ids와 합집합 크기가 운영 분포에 맞춰진다.
     * 보충 리스트를 사후에 자르지 않고 벡터 결과 수 자체를 줄이므로 코드 경로가 왜곡되지 않는다.
     */
    @Value("${search.loadtest.max-vector-results:30}")
    private int loadTestMaxVectorResults;

    // 최대 결과 수
    private static final int DEFAULT_MAX_RESULTS = 100;

    // 평균 계산에 사용할 상위 청크 수
    private static final int DEFAULT_TOP_K = 3;

    // Binary HNSW 후보 수 (Stage 1) — hnsw.ef_search=250보다 작게 설정하여 recall 향상
    private static final int DEFAULT_CANDIDATE_LIMIT = 200;

    /**
     * 키워드를 임베딩하여 유사한 Article 검색
     *
     * @param keyword 검색 키워드
     * @param threshold 유사도 임계값 (0.0 ~ 1.0)
     * @param topK 평균 계산에 사용할 상위 청크 수
     * @param maxResults 최대 결과 수
     * @return Article ID -> 유사도 스코어 맵
     */
    public Map<Long, Double> searchByKeyword(String keyword, double threshold, int topK, int maxResults) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Map.of();
        }

        try {
            float[] queryEmbedding = searchQueryEmbeddingService.getOrCreateEmbedding(keyword);
            if (queryEmbedding == null) {
                log.warn("키워드 임베딩 생성 실패: {}", keyword);
                return Map.of();
            }
            log.debug("키워드 임베딩 생성 완료 - 차원: {}", queryEmbedding.length);

            return searchTwoStage(queryEmbedding, threshold, topK, maxResults, null, null);

        } catch (Exception e) {
            log.error("벡터 검색 실패: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 키워드를 임베딩하여 유사한 Article 검색 (기본 topK 사용)
     */
    public Map<Long, Double> searchByKeyword(String keyword, double threshold, int maxResults) {
        return searchByKeyword(keyword, threshold, DEFAULT_TOP_K, maxResults);
    }

    /**
     * 기본 설정으로 키워드 검색
     */
    public Map<Long, Double> searchByKeyword(String keyword) {
        return searchByKeyword(keyword, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_MAX_RESULTS);
    }

    /**
     * 키워드 검색 결과와 생성된 임베딩을 함께 반환
     * 임베딩을 재사용하여 중복 API 호출을 방지
     *
     * @param keyword 검색 키워드
     * @return 검색 결과 (스코어 맵 + 쿼리 임베딩)
     */
    @Observed(name = "search.vector", contextualName = "vector-search")
    public VectorSearchResult searchByKeywordWithEmbedding(String keyword) {
        return searchByKeywordWithEmbedding(keyword, null, null);
    }

    /**
     * 키워드 검색 결과와 생성된 임베딩을 함께 반환 (해외/국내 + 카테고리 필터 지원)
     *
     * @param keyword 검색 키워드
     * @param domesticTypes 허용할 is_domestic 값 목록 (null이면 필터 없음, 1=국내, 0=해외)
     * @param categories 허용할 카테고리 이름 목록 (null이면 필터 없음)
     * @return 검색 결과 (스코어 맵 + 쿼리 임베딩)
     */
    @Observed(name = "search.vector", contextualName = "vector-search-filtered")
    public VectorSearchResult searchByKeywordWithEmbedding(String keyword, List<Integer> domesticTypes, List<String> categories) {
        return searchByKeywordWithEmbedding(keyword, domesticTypes, categories, false);
    }

    /**
     * useMockEmbedding=true(부하테스트 경로)면 쿼리 임베딩을 mock 엔드포인트로 생성하고
     * (search_query_embedding DB 캐시 저장도 건너뜀), 결과 캐시 키를 분리해 mock 벡터 기반 결과가
     * 실사용자 검색에 서빙되지 않게 한다 — searchForRag의 "rag:mock:" 키 분리와 같은 이유.
     */
    @Observed(name = "search.vector", contextualName = "vector-search-filtered")
    public VectorSearchResult searchByKeywordWithEmbedding(
            String keyword, List<Integer> domesticTypes, List<String> categories, boolean useMockEmbedding) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new VectorSearchResult(Map.of(), null);
        }

        boolean unfiltered = (domesticTypes == null || domesticTypes.isEmpty())
                && (categories == null || categories.isEmpty());
        if (unfiltered) {
            Cache cache = cacheManager.getCache(VECTOR_SEARCH_CACHE_NAME);
            if (cache != null) {
                String cacheKey = (useMockEmbedding ? "lt:mock:" : "") + keyword.toLowerCase().trim();
                try {
                    // sync 로딩: 같은 키워드의 동시 요청(메인 검색 + AI 요약)은 한쪽만 실제 실행
                    return cache.get(cacheKey, () -> {
                        VectorSearchResult result =
                                executeSearchByKeywordWithEmbedding(keyword, null, null, useMockEmbedding);
                        if (result.getQueryEmbedding() == null) {
                            // 임베딩 실패 결과는 캐시하지 않음
                            throw new IllegalStateException("벡터 검색 임베딩 생성 실패");
                        }
                        return result;
                    });
                } catch (Cache.ValueRetrievalException e) {
                    log.warn("벡터 검색 결과 캐시 로딩 실패: {}", e.getMessage());
                    return new VectorSearchResult(Map.of(), null);
                }
            }
        }

        return executeSearchByKeywordWithEmbedding(keyword, domesticTypes, categories, useMockEmbedding);
    }

    /**
     * RAG용 벡터 검색: 기업 프리필터 + 조절 가능한 유사도 임계값
     * corporationIds가 null/빈이면 필터 없이 전체 검색
     * 결과 캐시(VECTOR_SEARCH_CACHE_NAME)는 사용하지 않음 (threshold/필터가 요청마다 달라 캐시 부적합)
     */
    @Observed(name = "search.vector", contextualName = "vector-search-rag")
    public VectorSearchResult searchForRag(String vectorQuery, List<Long> corporationIds, double threshold) {
        return searchForRag(vectorQuery, corporationIds, threshold, false);
    }

    /** useMockEmbedding=true(부하테스트 경로)면 임베딩을 mock 엔드포인트로 생성한다 (DB 캐시 저장 안 함). */
    @Observed(name = "search.vector", contextualName = "vector-search-rag")
    public VectorSearchResult searchForRag(
            String vectorQuery, List<Long> corporationIds, double threshold, boolean useMockEmbedding) {
        if (vectorQuery == null || vectorQuery.trim().isEmpty()) {
            return new VectorSearchResult(Map.of(), null);
        }

        try {
            SearchQueryEmbeddingService.CachedEmbeddingResult cachedEmbedding =
                    searchQueryEmbeddingService.getEmbeddingWithCacheInfo(vectorQuery, null, useMockEmbedding);
            float[] queryEmbedding = cachedEmbedding.getEmbedding();
            if (queryEmbedding == null) {
                log.warn("RAG 벡터 검색 임베딩 생성 실패: {}", vectorQuery);
                return new VectorSearchResult(Map.of(), null);
            }

            String vectorString = formatVectorForPostgres(queryEmbedding);
            String binaryString = toBinaryString(queryEmbedding);

            List<Object[]> results;
            if (corporationIds != null && !corporationIds.isEmpty()) {
                results = chunkRepository.findArticlesByTwoStageSearchWithCorporationFilter(
                        vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, DEFAULT_TOP_K,
                        threshold, DEFAULT_MAX_RESULTS, corporationIds);
            } else {
                results = chunkRepository.findArticlesByTwoStageSearch(
                        vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, DEFAULT_TOP_K,
                        threshold, DEFAULT_MAX_RESULTS);
            }

            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null) {
                    scoreMap.put(articleId, similarity);
                }
            }
            return new VectorSearchResult(scoreMap, queryEmbedding);

        } catch (Exception e) {
            log.error("RAG 벡터 검색 실패: {}", e.getMessage(), e);
            return new VectorSearchResult(Map.of(), null);
        }
    }

    private VectorSearchResult executeSearchByKeywordWithEmbedding(String keyword, List<Integer> domesticTypes, List<String> categories) {
        return executeSearchByKeywordWithEmbedding(keyword, domesticTypes, categories, false);
    }

    private VectorSearchResult executeSearchByKeywordWithEmbedding(
            String keyword, List<Integer> domesticTypes, List<String> categories, boolean useMockEmbedding) {
        try {
            SearchQueryEmbeddingService.CachedEmbeddingResult cachedEmbedding =
                    searchQueryEmbeddingService.getEmbeddingWithCacheInfo(keyword, null, useMockEmbedding);
            float[] queryEmbedding = cachedEmbedding.getEmbedding();
            long embeddingMs = cachedEmbedding.getEmbeddingMs();
            boolean cacheHit = cachedEmbedding.isCacheHit();
            long cacheLookupMs = cachedEmbedding.getCacheLookupMs();

            if (queryEmbedding == null) {
                log.warn("키워드 임베딩 생성 실패: {}", keyword);
                return new VectorSearchResult(Map.of(), null);
            }
            log.debug("키워드 임베딩 생성 완료 - 차원: {}, 토큰: {}",
                    queryEmbedding.length, 0);

            long queryStart = System.currentTimeMillis();
            Map<Long, Double> scores = searchTwoStage(queryEmbedding, vectorThresholdFor(useMockEmbedding), DEFAULT_TOP_K, maxVectorResultsFor(useMockEmbedding), domesticTypes, categories);
            long queryMs = System.currentTimeMillis() - queryStart;

            warmChunkCacheAsync(keyword, queryEmbedding);

            return new VectorSearchResult(scores, queryEmbedding, embeddingMs, queryMs, cacheHit, cacheLookupMs);

        } catch (Exception e) {
            log.error("벡터 검색 실패: {}", e.getMessage(), e);
            return new VectorSearchResult(Map.of(), null);
        }
    }

    /**
     * 2단계 검색: Binary HNSW → halfvec Reranking (해외/국내 + 카테고리 필터 지원)
     *
     * @param domesticTypes 허용할 is_domestic 값 목록 (null이면 필터 없음)
     * @param categories    허용할 카테고리 이름 목록 (null이면 필터 없음)
     */
    private Map<Long, Double> searchTwoStage(float[] queryEmbedding, double threshold, int topK, int maxResults,
                                              List<Integer> domesticTypes, List<String> categories) {
        long startTime = System.currentTimeMillis();

        String vectorString = formatVectorForPostgres(queryEmbedding);
        String binaryString = toBinaryString(queryEmbedding);

        boolean hasDomestic = domesticTypes != null && !domesticTypes.isEmpty();
        boolean hasCategory = categories != null && !categories.isEmpty();

        List<Object[]> results;
        if (hasDomestic && hasCategory) {
            results = chunkRepository.findArticlesByTwoStageSearchWithBothFilters(
                    vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, topK, threshold, maxResults, domesticTypes, categories);
        } else if (hasDomestic) {
            results = chunkRepository.findArticlesByTwoStageSearchWithDomesticFilter(
                    vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, topK, threshold, maxResults, domesticTypes);
        } else if (hasCategory) {
            results = chunkRepository.findArticlesByTwoStageSearchWithCategoryFilter(
                    vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, topK, threshold, maxResults, categories);
        } else {
            results = chunkRepository.findArticlesByTwoStageSearch(
                    vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, topK, threshold, maxResults);
        }

        long totalTime = System.currentTimeMillis() - startTime;

        Map<Long, Double> scoreMap = new HashMap<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            if (similarity != null) {
                scoreMap.put(articleId, similarity);
            }
        }

        log.debug("2단계 벡터 검색 완료 - 총: {}ms, 결과: {}개", totalTime, scoreMap.size());

        return scoreMap;
    }

    /**
     * 벡터 검색 결과 + 쿼리 임베딩 래퍼
     */
    public static class VectorSearchResult {
        private final Map<Long, Double> scores;
        private final float[] queryEmbedding;
        private final long embeddingMs;
        private final long queryMs;
        private final boolean cacheHit;
        private final long cacheLookupMs;

        public VectorSearchResult(Map<Long, Double> scores, float[] queryEmbedding,
                                   long embeddingMs, long queryMs, boolean cacheHit, long cacheLookupMs) {
            this.scores = scores;
            this.queryEmbedding = queryEmbedding;
            this.embeddingMs = embeddingMs;
            this.queryMs = queryMs;
            this.cacheHit = cacheHit;
            this.cacheLookupMs = cacheLookupMs;
        }

        public VectorSearchResult(Map<Long, Double> scores, float[] queryEmbedding) {
            this(scores, queryEmbedding, 0, 0, false, 0);
        }

        public Map<Long, Double> getScores() {
            return scores;
        }

        public float[] getQueryEmbedding() {
            return queryEmbedding;
        }

        public long getEmbeddingMs() {
            return embeddingMs;
        }

        public long getQueryMs() {
            return queryMs;
        }

        public boolean isCacheHit() {
            return cacheHit;
        }

        public long getCacheLookupMs() {
            return cacheLookupMs;
        }
    }

    /**
     * AI 요약용 상위 N개 chunk 조회 (article 정보 + 텍스트 포함)
     * 검색 시 워밍된 Caffeine 캐시 우선 조회, 미스 시 2단계 벡터 검색 실행
     *
     * @param keyword 검색 키워드
     * @param limit 조회할 최대 chunk 수
     * @return chunk 목록 (article_id, title, url, content)
     */
    public List<AiSummaryChunkDto> getTopChunksForSummary(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }

        String cacheKey = keyword.toLowerCase().trim();
        Cache cache = cacheManager.getCache(CHUNK_CACHE_NAME);
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                @SuppressWarnings("unchecked")
                List<AiSummaryChunkDto> cached = (List<AiSummaryChunkDto>) wrapper.get();
                if (cached != null) {
                    log.debug("AI 요약용 chunk 캐시 히트 - 키워드: '{}', {}개", keyword, cached.size());
                    return cached;
                }
            }
        }

        try {
            float[] queryEmbedding = searchQueryEmbeddingService.getOrCreateEmbedding(keyword);
            if (queryEmbedding == null) {
                log.warn("AI 요약용 임베딩 생성 실패: {}", keyword);
                return List.of();
            }

            List<AiSummaryChunkDto> chunks = fetchTopChunks(queryEmbedding, limit);

            if (cache != null && !chunks.isEmpty()) {
                cache.put(cacheKey, chunks);
            }

            log.debug("AI 요약용 chunk 조회 완료 - 키워드: '{}', 결과: {}개", keyword, chunks.size());
            return chunks;

        } catch (Exception e) {
            log.error("AI 요약용 chunk 조회 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 단일 term 쿼리 AI 요약용 청크 조회
     * 기업당 1개 아티클(최대 3개 기업), 아티클당 [최고유사도 + 첫번째 + 마지막] 청크 3종
     * 최대 9개 청크 반환
     */
    public List<AiSummaryChunkDto> getTopChunksForSummarySingleTerm(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }

        String cacheKey = "single:" + keyword.toLowerCase().trim();
        Cache cache = cacheManager.getCache(CHUNK_CACHE_NAME);
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                @SuppressWarnings("unchecked")
                List<AiSummaryChunkDto> cached = (List<AiSummaryChunkDto>) wrapper.get();
                if (cached != null) {
                    log.debug("단일 term AI 요약 chunk 캐시 히트 - 키워드: '{}', {}개", keyword, cached.size());
                    return cached;
                }
            }
        }

        try {
            float[] queryEmbedding = searchQueryEmbeddingService.getOrCreateEmbedding(keyword);
            if (queryEmbedding == null) {
                log.warn("단일 term AI 요약 임베딩 생성 실패: {}", keyword);
                return List.of();
            }

            List<AiSummaryChunkDto> chunks = fetchTopChunksSingleTerm(queryEmbedding);

            if (cache != null && !chunks.isEmpty()) {
                cache.put(cacheKey, chunks);
            }

            log.debug("단일 term AI 요약 chunk 조회 완료 - 키워드: '{}', 결과: {}개", keyword, chunks.size());
            return chunks;

        } catch (Exception e) {
            log.error("단일 term AI 요약 chunk 조회 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private List<AiSummaryChunkDto> fetchTopChunksSingleTerm(float[] queryEmbedding) {
        String vectorString = formatVectorForPostgres(queryEmbedding);
        String binaryString = toBinaryString(queryEmbedding);

        List<Object[]> results = chunkRepository.findTopChunksForAiSummary(
                vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, DEFAULT_SIMILARITY_THRESHOLD, SINGLE_TERM_FETCH_LIMIT);

        // 기업당 1개 아티클, 최대 3개 기업 선택 (최고유사도 청크 기준)
        Set<String> seenCorps = new LinkedHashSet<>();
        Map<Long, AiSummaryChunkDto> bestChunkByArticle = new LinkedHashMap<>();

        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            String corpName = (String) row[6];

            if (bestChunkByArticle.containsKey(articleId)) continue;
            if (corpName != null && seenCorps.contains(corpName)) continue;
            if (seenCorps.size() >= SINGLE_TERM_MAX_CORPS) break;

            String logoS3Url = (String) row[4];
            String logoFilename = (String) row[5];
            String logoUrl = (logoS3Url != null && !logoS3Url.isBlank()) ? logoS3Url
                    : (logoFilename != null && !logoFilename.isBlank()) ? "/images/logos/" + logoFilename
                    : null;
            bestChunkByArticle.put(articleId, new AiSummaryChunkDto(
                    articleId, (String) row[1], (String) row[2], (String) row[3],
                    logoUrl, corpName, (String) row[7]));
            if (corpName != null) seenCorps.add(corpName);
        }

        // 각 아티클에 대해 첫번째 + 마지막 청크 추가
        List<AiSummaryChunkDto> chunks = new ArrayList<>();
        for (Map.Entry<Long, AiSummaryChunkDto> entry : bestChunkByArticle.entrySet()) {
            Long articleId = entry.getKey();
            AiSummaryChunkDto bestChunk = entry.getValue();

            chunks.add(bestChunk);

            Set<String> addedContents = new java.util.HashSet<>();
            addedContents.add(bestChunk.content());

            List<Object[]> positions = chunkRepository.findFirstAndLastChunkContentByArticleId(articleId);
            for (Object[] pos : positions) {
                String content = (String) pos[0];
                if (content != null && addedContents.add(content)) {
                    chunks.add(new AiSummaryChunkDto(
                            articleId, bestChunk.articleTitle(), bestChunk.articleUrl(),
                            content, bestChunk.logoUrl(), bestChunk.corporationName(), bestChunk.thumbnailImage()));
                }
            }
        }

        return chunks;
    }

    private void warmChunkCacheAsync(String keyword, float[] queryEmbedding) {
        String cacheKey = keyword.toLowerCase().trim();
        Cache cache = cacheManager.getCache(CHUNK_CACHE_NAME);
        if (cache == null || cache.get(cacheKey) != null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<AiSummaryChunkDto> chunks = fetchTopChunks(queryEmbedding, SUMMARY_CHUNK_LIMIT);
                if (!chunks.isEmpty()) {
                    cache.put(cacheKey, chunks);
                    log.debug("chunk 캐시 워밍 완료 - 키워드: '{}', {}개", keyword, chunks.size());
                }
            } catch (Exception e) {
                log.debug("chunk 캐시 워밍 실패 (무시): {}", e.getMessage());
            }
        }, searchExecutor);
    }

    private List<AiSummaryChunkDto> fetchTopChunks(float[] queryEmbedding, int limit) {
        String vectorString = formatVectorForPostgres(queryEmbedding);
        String binaryString = toBinaryString(queryEmbedding);
        List<Object[]> results = chunkRepository.findTopChunksForAiSummary(
                vectorString, binaryString, DEFAULT_CANDIDATE_LIMIT, DEFAULT_SIMILARITY_THRESHOLD,
                limit * SUMMARY_FETCH_MULTIPLIER);

        Set<String> seenCorporations = new LinkedHashSet<>();
        Map<Long, Integer> articleChunkCount = new LinkedHashMap<>();
        List<AiSummaryChunkDto> chunks = new ArrayList<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            String corporationName = (String) row[6];

            // 이미 등장한 기업의 다른 아티클은 skip (기업당 1 article만 허용)
            if (corporationName != null && seenCorporations.contains(corporationName)
                    && !articleChunkCount.containsKey(articleId)) {
                continue;
            }
            // 아티클당 청크 수 제한
            if (articleChunkCount.getOrDefault(articleId, 0) >= SUMMARY_MAX_CHUNKS_PER_ARTICLE) {
                continue;
            }
            String logoS3Url = (String) row[4];
            String logoFilename = (String) row[5];
            String logoUrl = (logoS3Url != null && !logoS3Url.isBlank()) ? logoS3Url
                    : (logoFilename != null && !logoFilename.isBlank()) ? "/images/logos/" + logoFilename
                    : null;
            chunks.add(new AiSummaryChunkDto(
                    articleId,
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    logoUrl,
                    corporationName,
                    (String) row[7]
            ));
            articleChunkCount.merge(articleId, 1, Integer::sum);
            if (corporationName != null) seenCorporations.add(corporationName);
            if (chunks.size() >= limit) break;
        }
        return chunks;
    }

    /**
     * AI 요약용: 지정 아티클 목록에서 아티클별 첫 청크 + 최고 유사도 청크 반환
     * 하이브리드 검색으로 선정된 articleIds를 받아 청크 텍스트만 조회
     */
    public List<AiSummaryChunkDto> getChunksForArticlesByIds(String keyword, List<Long> articleIds) {
        return getChunksForArticlesByIds(keyword, articleIds, null);
    }

    /**
     * AI 요약용: 하이브리드 검색에서 이미 확보한 쿼리 임베딩을 재사용하는 오버로드
     * queryEmbedding이 null이면 기존처럼 임베딩을 조회/생성 (fallback)
     */
    public List<AiSummaryChunkDto> getChunksForArticlesByIds(String keyword, List<Long> articleIds, float[] queryEmbedding) {
        if (keyword == null || keyword.trim().isEmpty() || articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        try {
            if (queryEmbedding == null) {
                queryEmbedding = searchQueryEmbeddingService.getOrCreateEmbedding(keyword);
            }
            if (queryEmbedding == null) {
                log.warn("AI 요약 chunk 조회 임베딩 생성 실패: {}", keyword);
                return List.of();
            }
            String vectorString = formatVectorForPostgres(queryEmbedding);
            List<Object[]> results = chunkRepository.findFirstAndBestChunksByArticleIds(vectorString, articleIds);

            return results.stream().map(row -> {
                String logoS3Url   = (String) row[4];
                String logoFilename = (String) row[5];
                String logoUrl = (logoS3Url != null && !logoS3Url.isBlank()) ? logoS3Url
                        : (logoFilename != null && !logoFilename.isBlank()) ? "/images/logos/" + logoFilename
                        : null;
                return new AiSummaryChunkDto(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        (String) row[2],
                        (String) row[3],
                        logoUrl,
                        (String) row[6],
                        (String) row[7]);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("AI 요약 chunk 조회 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 공개 챗봇용: getChunksForRag 결과를 chunkSearchResults 캐시("rag:" prefix 키)로 공유하는 wrapper.
     * 결과 순서는 호출부(RagAnswerService)가 topArticleIds 기준으로 재정렬하므로
     * 캐시 키의 articleIds는 정렬해 순서 무관하게 재사용한다.
     * admin 테스트 페이지 경로는 이 wrapper를 거치지 않고 비캐시 메서드를 직접 호출한다.
     */
    public List<AiSummaryChunkDto> getChunksForRagCached(
            String vectorQuery, List<Long> articleIds, float[] queryEmbedding, int chunksPerArticle) {
        return getChunksForRagCached(vectorQuery, articleIds, queryEmbedding, chunksPerArticle, false);
    }

    /** useMockEmbedding=true(부하테스트 경로)면 캐시 키를 분리해 mock 기반 결과가 실사용자에게 서빙되지 않게 한다. */
    public List<AiSummaryChunkDto> getChunksForRagCached(
            String vectorQuery, List<Long> articleIds, float[] queryEmbedding, int chunksPerArticle,
            boolean useMockEmbedding) {
        if (vectorQuery == null || vectorQuery.trim().isEmpty() || articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        String idsKey = articleIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
        String cacheKey = (useMockEmbedding ? "rag:mock:" : "rag:")
                + vectorQuery.toLowerCase().trim() + ":" + chunksPerArticle + ":" + idsKey;
        Cache cache = cacheManager.getCache(CHUNK_CACHE_NAME);
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                @SuppressWarnings("unchecked")
                List<AiSummaryChunkDto> cached = (List<AiSummaryChunkDto>) wrapper.get();
                if (cached != null) {
                    log.debug("RAG chunk 캐시 히트 - 쿼리: '{}', {}개", vectorQuery, cached.size());
                    return cached;
                }
            }
        }

        List<AiSummaryChunkDto> chunks =
                getChunksForRag(vectorQuery, articleIds, queryEmbedding, chunksPerArticle, useMockEmbedding);
        if (cache != null && !chunks.isEmpty()) {
            cache.put(cacheKey, chunks);
        }
        return chunks;
    }

    /**
     * RAG용: 지정 아티클 목록에서 아티클별 첫 청크 + 유사도 상위 chunksPerArticle개 청크 반환
     * getChunksForArticlesByIds의 확장 — 아티클당 청크 수를 조절 가능
     * queryEmbedding이 null이면 임베딩을 조회/생성 (fallback)
     */
    public List<AiSummaryChunkDto> getChunksForRag(
            String vectorQuery, List<Long> articleIds, float[] queryEmbedding, int chunksPerArticle) {
        return getChunksForRag(vectorQuery, articleIds, queryEmbedding, chunksPerArticle, false);
    }

    /** useMockEmbedding=true(부하테스트 경로)면 null-embedding fallback도 mock 엔드포인트로 생성한다. */
    public List<AiSummaryChunkDto> getChunksForRag(
            String vectorQuery, List<Long> articleIds, float[] queryEmbedding, int chunksPerArticle,
            boolean useMockEmbedding) {
        if (vectorQuery == null || vectorQuery.trim().isEmpty() || articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        try {
            if (queryEmbedding == null) {
                queryEmbedding = searchQueryEmbeddingService
                        .getEmbeddingWithCacheInfo(vectorQuery, null, useMockEmbedding)
                        .getEmbedding();
            }
            if (queryEmbedding == null) {
                log.warn("RAG chunk 조회 임베딩 생성 실패: {}", vectorQuery);
                return List.of();
            }
            String vectorString = formatVectorForPostgres(queryEmbedding);
            List<Object[]> results = chunkRepository.findFirstAndTopChunksByArticleIds(
                    vectorString, articleIds, chunksPerArticle);

            return results.stream().map(row -> {
                String logoS3Url   = (String) row[4];
                String logoFilename = (String) row[5];
                String logoUrl = (logoS3Url != null && !logoS3Url.isBlank()) ? logoS3Url
                        : (logoFilename != null && !logoFilename.isBlank()) ? "/images/logos/" + logoFilename
                        : null;
                return new AiSummaryChunkDto(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        (String) row[2],
                        (String) row[3],
                        logoUrl,
                        (String) row[6],
                        (String) row[7]);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("RAG chunk 조회 실패: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 특정 Article ID들에 대한 vector similarity 계산
     * BM25로만 검색된 article들의 vector score를 채우기 위해 사용
     *
     * @param keyword 검색 키워드
     * @param articleIds vector similarity를 계산할 Article ID 목록
     * @return Article ID -> 유사도 스코어 맵
     */
    public Map<Long, Double> computeSimilarityForArticles(String keyword, List<Long> articleIds) {
        if (keyword == null || keyword.trim().isEmpty() || articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }

        try {
            float[] queryEmbedding = searchQueryEmbeddingService.getOrCreateEmbedding(keyword);
            if (queryEmbedding == null) {
                log.warn("키워드 임베딩 생성 실패: {}", keyword);
                return Map.of();
            }
            String vectorString = formatVectorForPostgres(queryEmbedding);

            List<Object[]> results = chunkRepository.computeSimilarityForArticleIds(
                    vectorString, articleIds, DEFAULT_TOP_K);

            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("특정 Article 유사도 계산 완료 - 키워드: '{}', 요청: {}개, 계산됨: {}개",
                    keyword, articleIds.size(), scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("특정 Article 유사도 계산 실패: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 캐시된 쿼리 임베딩을 사용하여 특정 Article ID들에 대한 vector similarity 계산
     * searchArticlesHybrid에서 이미 생성된 임베딩을 재사용하여 API 호출 절감
     *
     * @param queryEmbedding 이미 생성된 쿼리 임베딩
     * @param articleIds vector similarity를 계산할 Article ID 목록
     * @return Article ID -> 유사도 스코어 맵
     */
    /**
     * 요청 경로에 맞는 벡터 유사도 임계값. 부하테스트 경로만 loadTestVectorThreshold를 쓴다.
     * cross-scoring 보충 호출자(ArticleSearchService)가 2단계 검색과 같은 값을 쓰도록 공개한다.
     */
    public double vectorThresholdFor(boolean useMockEmbedding) {
        return useMockEmbedding ? loadTestVectorThreshold : DEFAULT_SIMILARITY_THRESHOLD;
    }

    /** 요청 경로에 맞는 벡터 결과 수 상한. 부하테스트 경로만 축소된 값을 쓴다. */
    private int maxVectorResultsFor(boolean useMockEmbedding) {
        return useMockEmbedding ? loadTestMaxVectorResults : DEFAULT_MAX_RESULTS;
    }

    public Map<Long, Double> computeSimilarityForArticlesWithEmbedding(float[] queryEmbedding, List<Long> articleIds) {
        return computeSimilarityForArticlesWithEmbedding(queryEmbedding, articleIds, DEFAULT_SIMILARITY_THRESHOLD);
    }

    /**
     * threshold를 지정하는 오버로드 (RAG는 요청별 임계값 사용)
     */
    public Map<Long, Double> computeSimilarityForArticlesWithEmbedding(
            float[] queryEmbedding, List<Long> articleIds, double threshold) {
        if (queryEmbedding == null || articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }

        try {
            String vectorString = formatVectorForPostgres(queryEmbedding);

            List<Object[]> results = chunkRepository.computeSimilarityForArticleIds(
                    vectorString, articleIds, DEFAULT_TOP_K);

            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null && similarity >= threshold) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("특정 Article 유사도 계산 완료 (캐시된 임베딩) - 요청: {}개, 계산됨: {}개",
                    articleIds.size(), scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("특정 Article 유사도 계산 실패: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * float[] 임베딩을 PostgreSQL halfvec 포맷으로 변환
     * 형식: [0.1,0.2,0.3,...,0.9]
     */
    private String formatVectorForPostgres(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * float[] 임베딩을 Binary String으로 변환 (Binary Quantization)
     * 양수 → 1, 음수 → 0
     */
    private String toBinaryString(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length);
        for (float val : embedding) {
            sb.append(val >= 0 ? '1' : '0');
        }
        return sb.toString();
    }
}
