package com.newcodes7.small_town.search.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.service.SearchQueryEmbeddingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
    private static final int SUMMARY_CHUNK_LIMIT = 6;
    private static final int SUMMARY_MAX_CHUNKS_PER_ARTICLE = 2;
    private static final int SUMMARY_FETCH_MULTIPLIER = 4;

    // 기본 유사도 임계값
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.52;

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
    public VectorSearchResult searchByKeywordWithEmbedding(String keyword, List<Integer> domesticTypes, List<String> categories) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return new VectorSearchResult(Map.of(), null);
        }

        try {
            SearchQueryEmbeddingService.CachedEmbeddingResult cachedEmbedding =
                    searchQueryEmbeddingService.getEmbeddingWithCacheInfo(keyword, null);
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
            Map<Long, Double> scores = searchTwoStage(queryEmbedding, DEFAULT_SIMILARITY_THRESHOLD, DEFAULT_TOP_K, DEFAULT_MAX_RESULTS, domesticTypes, categories);
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

        Map<Long, Integer> articleChunkCount = new LinkedHashMap<>();
        List<AiSummaryChunkDto> chunks = new ArrayList<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
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
                    logoUrl
            ));
            articleChunkCount.merge(articleId, 1, Integer::sum);
            if (chunks.size() >= limit) break;
        }
        return chunks;
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
    public Map<Long, Double> computeSimilarityForArticlesWithEmbedding(float[] queryEmbedding, List<Long> articleIds) {
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
                if (similarity != null && similarity >= DEFAULT_SIMILARITY_THRESHOLD) {
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
