package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import io.micrometer.observation.annotation.Observed;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final String CHUNK_CACHE_NAME = "chunkSearchResults";
    private static final String VECTOR_SEARCH_CACHE_NAME = "vectorSearchResults";

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

    /**
     * cross-scoring 보충을 binary → halfvec 2단계로 수행할지 여부. 기본 true.
     *
     * 켜면 대상 아티클의 전 청크를 인라인 embedding_binary로 먼저 스코어링해 상위
     * crossScoringStage2Limit개 아티클만 halfvec 정확 계산에 넣는다 — TOAST 간접 참조가
     * 컷 크기에 비례해 줄어든다 (docs/operations/PGSS_SEARCH_COST.md 항목 B').
     *
     * 항목 A와 달리 이 변경은 <b>랭킹을 바꾸는데, 그 변화량을 잴 수단이 아직 없다</b>
     * (하이브리드 NSF 최종 랭킹 측정 하네스 미구현 — 문서의 "남은 검증 부채" 절).
     * 이상 징후는 [검색] 로그의 "보충통과" 수로 감시하고, false로 두면 코드 변경 없이
     * 종전 단일 쿼리로 즉시 되돌아간다.
     *
     * RAG 경로(getTopArticleIdsForRag)에는 적용되지 않는다 — 답변 품질 판정 기준이 따로 없어
     * 검색 경로의 결과를 먼저 본 뒤 별건으로 다룬다.
     */
    @Value("${search.hybrid.cross-scoring-two-stage:false}")
    private boolean crossScoringTwoStage;

    /**
     * cross-scoring Stage 2로 넘길 아티클 수 상한. 기본 20. <b>실제 비용을 결정하는 값이자
     * 품질 리스크가 전부 몰려 있는 값이다.</b>
     *
     * Stage 1(인라인 binary)은 사실상 공짜지만 Stage 2는 여전히 halfvec을 TOAST에서 꺼내므로
     * 이득은 컷 크기에 비례한다. 현재 10,272블록/93.3ms 기준 컷 20이면 Stage 1까지 합쳐
     * 약 2,400~2,700블록으로 내려간다.
     */
    @Value("${search.hybrid.cross-scoring-stage2-limit:20}")
    private int crossScoringStage2Limit;

    /**
     * Stage 1 추정 유사도 하한을 정할 때 임계값에서 뺄 여유. 기본 0.25.
     *
     * 부호 양자화에서 해밍 h와 각도는 cos θ ≈ cos(π·h/d)이고 h ~ Binomial(d, θ/π)이라
     * d=1024에서 σ_h ≈ 15다. 참 유사도 0.52인 청크의 해밍 기대값이 334이므로 3σ(45비트)를
     * 감안하면 추정치가 0.40까지 내려갈 수 있다 — 여유 없이 임계값을 그대로 하한으로 쓰면
     * 통과했을 아티클을 떨어뜨린다.
     *
     * 다만 이 하한은 <b>레버가 아니다</b>: 무관한 문서쌍의 코사인도 0.3~0.4대에 있어 실제로
     * 걸러지는 양은 적다. 비용을 줄이는 것은 crossScoringStage2Limit이고, 이 값은 병리적
     * 케이스와 Stage 2 폭주에 대한 값싼 안전망이다.
     */
    @Value("${search.hybrid.cross-scoring-stage1-floor-margin:0.25}")
    private double crossScoringStage1FloorMargin;

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

            return searchTwoStage(queryEmbedding, threshold, topK, maxResults, null, null).scores();

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

            TwoStageRows parsed = parseTwoStageRows(results);
            return new VectorSearchResult(parsed.scores(), queryEmbedding,
                    0, 0, false, 0, parsed.candidateScores());

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
            TwoStageRows parsed = searchTwoStage(queryEmbedding, vectorThresholdFor(useMockEmbedding), DEFAULT_TOP_K, maxVectorResultsFor(useMockEmbedding), domesticTypes, categories);
            long queryMs = System.currentTimeMillis() - queryStart;

            return new VectorSearchResult(parsed.scores(), queryEmbedding, embeddingMs, queryMs, cacheHit, cacheLookupMs,
                    parsed.candidateScores());

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
    private TwoStageRows searchTwoStage(float[] queryEmbedding, double threshold, int topK, int maxResults,
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

        TwoStageRows parsed = parseTwoStageRows(results);

        log.debug("2단계 벡터 검색 완료 - 총: {}ms, 결과: {}개 (후보: {}개)",
                totalTime, parsed.scores().size(), parsed.candidateScores().size());

        return parsed;
    }

    /**
     * 2단계 검색 결과 행 파싱.
     *
     * 행 형식은 (article_id, avg_similarity, candidate_similarity, is_main)이며,
     * 앞의 두 컬럼만 있는 구형 행(테스트 스텁 등)도 그대로 받아들인다 — 그 경우 후보 점수는
     * 본검색 점수와 같고 모든 행이 본검색 결과로 취급된다.
     */
    private TwoStageRows parseTwoStageRows(List<Object[]> rows) {
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, Double> candidateScores = new HashMap<>();

        for (Object[] row : rows) {
            Long articleId = ((Number) row[0]).longValue();
            // 두 분기 모두 Double로 맞춘다 — 한쪽이 primitive double이면 삼항 연산자가 반대쪽
            // Double을 언박싱해서, 두 컬럼이 모두 NULL인 행(threshold 미달 + 후보 청크 topK 미만)
            // 에서 NPE가 나고 벡터 검색 전체가 빈 결과로 떨어진다
            Double similarity = row.length > 1 ? toNullableDouble(row[1]) : null;
            Double candidateSimilarity = row.length > 2 ? toNullableDouble(row[2]) : similarity;
            boolean isMain = (row.length > 3 && row[3] != null)
                    ? toBoolean(row[3]) : similarity != null;

            if (isMain && similarity != null) {
                scores.put(articleId, similarity);
            }
            if (candidateSimilarity != null) {
                candidateScores.put(articleId, candidateSimilarity);
            }
        }

        return new TwoStageRows(scores, candidateScores);
    }

    private Double toNullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    /**
     * native 쿼리의 boolean 컬럼 변환.
     *
     * Boolean.TRUE.equals()로 바로 비교하면 드라이버/Hibernate가 다른 표현(1, 't')으로 돌려줬을 때
     * 예외 없이 전부 false가 되어 <b>벡터 검색 결과가 조용히 0건</b>이 된다(검색이 BM25 단독으로
     * 퇴화하고 로그에도 안 남는다). 알려진 표현을 모두 받고, 모르는 타입이면 예외를 던져
     * 조용한 퇴화 대신 상위 catch의 에러 로그로 드러나게 한다.
     */
    private boolean toBoolean(Object value) {
        return switch (value) {
            case Boolean b -> b;
            case Number n -> n.intValue() != 0;
            case String str -> "t".equalsIgnoreCase(str) || "true".equalsIgnoreCase(str);
            default -> throw new IllegalStateException(
                    "2단계 검색 is_main 컬럼 타입을 해석할 수 없음: " + value.getClass().getName());
        };
    }

    /**
     * 2단계 검색의 본검색 점수와 Stage 1 후보 점수 묶음.
     *
     * candidateScores는 threshold/limit로 잘려나간 아티클까지 포함한다 —
     * cross-scoring 보충 대상과 겹치는 만큼 DB 왕복을 없앨 수 있다
     * (docs/operations/PGSS_SEARCH_COST.md 항목 A).
     */
    private record TwoStageRows(Map<Long, Double> scores, Map<Long, Double> candidateScores) {
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
        private final Map<Long, Double> candidateScores;

        public VectorSearchResult(Map<Long, Double> scores, float[] queryEmbedding,
                                   long embeddingMs, long queryMs, boolean cacheHit, long cacheLookupMs,
                                   Map<Long, Double> candidateScores) {
            this.scores = scores;
            this.queryEmbedding = queryEmbedding;
            this.embeddingMs = embeddingMs;
            this.queryMs = queryMs;
            this.cacheHit = cacheHit;
            this.cacheLookupMs = cacheLookupMs;
            // 결과 캐시(vectorSearchResults)에 담겨 스레드 간 공유되므로 불변으로 굳힌다
            this.candidateScores = candidateScores != null ? Map.copyOf(candidateScores) : Map.of();
        }

        public VectorSearchResult(Map<Long, Double> scores, float[] queryEmbedding,
                                   long embeddingMs, long queryMs, boolean cacheHit, long cacheLookupMs) {
            this(scores, queryEmbedding, embeddingMs, queryMs, cacheHit, cacheLookupMs, Map.of());
        }

        public VectorSearchResult(Map<Long, Double> scores, float[] queryEmbedding) {
            this(scores, queryEmbedding, 0, 0, false, 0, Map.of());
        }

        public Map<Long, Double> getScores() {
            return scores;
        }

        /**
         * Stage 1 후보에서 <b>cross-scoring과 같은 깊이(topK)로</b> 점수가 확정된 아티클들
         * (threshold/limit 적용 전, 후보 청크가 topK 미만인 아티클은 제외).
         *
         * cross-scoring 보충 대상과 겹치는 만큼 DB 왕복 없이 채울 수 있다. 값에는 threshold가
         * 적용돼 있지 않으므로 사용하는 쪽에서 걸러야 한다 — 그리고 이 값은 전체 청크 기준
         * 계산값보다 크지 않으므로(부분집합의 상위 topK 평균), 임계값 미만이라고 해서 곧바로
         * 탈락시키면 안 되고 DB 보충으로 넘겨야 한다.
         */
        public Map<Long, Double> getCandidateScores() {
            return candidateScores;
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
                    vectorString, formatIdArray(articleIds), DEFAULT_TOP_K);

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
                    vectorString, formatIdArray(articleIds), DEFAULT_TOP_K);

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
     * 검색(하이브리드) 경로 전용 cross-scoring 보충.
     *
     * <p>crossScoringTwoStage 스위치에 따라 binary → halfvec 퍼널 쿼리와 종전 단일 쿼리 중
     * 하나를 고른다. 어느 쪽이든 <b>반환되는 아티클의 점수 값은 동일</b>하다 — 퍼널은 Stage 2에서
     * 생존 아티클의 전 청크를 그대로 다시 읽기 때문이다. 달라지는 것은 컷에 걸린 아티클이
     * 아예 반환되지 않는다는 점뿐이고, 그래서 임계값 필터도 종전처럼 아티클 평균에 그대로 건다.
     *
     * <p>RAG 경로는 이 메서드를 쓰지 않는다 — computeSimilarityForArticlesWithEmbedding을
     * 그대로 호출해 종전 단일 쿼리를 탄다 (docs/operations/PGSS_SEARCH_COST.md 항목 B' 적용 범위).
     *
     * @param threshold 2단계 벡터 검색과 같은 임계값 (부하테스트 경로는 완화된 값)
     */
    public Map<Long, Double> computeSimilarityForSearchCrossScoring(
            float[] queryEmbedding, List<Long> articleIds, double threshold) {
        if (!crossScoringTwoStage) {
            return computeSimilarityForArticlesWithEmbedding(queryEmbedding, articleIds, threshold);
        }
        if (queryEmbedding == null || articleIds == null || articleIds.isEmpty()) {
            return Map.of();
        }

        try {
            // 부하테스트 경로는 임계값이 0.0이라 하한이 음수로 내려가 사실상 해제된다 —
            // Stage 2가 정확히 컷 크기를 받으므로 최악 비용이 그대로 측정된다.
            double stage1Floor = Math.max(-1.0, threshold - crossScoringStage1FloorMargin);

            List<Object[]> results = chunkRepository.computeSimilarityForArticleIdsTwoStage(
                    formatVectorForPostgres(queryEmbedding),
                    toBinaryString(queryEmbedding),
                    formatIdArray(articleIds),
                    DEFAULT_TOP_K,
                    stage1Floor,
                    crossScoringStage2Limit);

            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 && row[1] != null ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null && similarity >= threshold) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("cross-scoring 2단계 완료 - 대상: {}개, Stage 2 상한: {}, 하한: {}, 통과: {}개",
                    articleIds.size(), crossScoringStage2Limit, stage1Floor, scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("cross-scoring 2단계 계산 실패: {}", e.getMessage(), e);
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
     * Long id 목록을 PostgreSQL 배열 리터럴({@code {1,2,3}})로 변환.
     *
     * <p>cross-scoring 쿼리가 {@code IN (:ids)} 대신 {@code = ANY(CAST(:ids AS bigint[]))}를 쓰는
     * 이유는 ArticleChunkRepository.computeSimilarityForArticleIds 주석 참고 — id 개수와 무관하게
     * 쿼리 텍스트가 하나로 고정돼야 pg_stat_statements에서 단일 queryid로 집계된다.
     * 값은 DB에서 온 Long이라 문자열 조립이지만 주입 위험이 없다.
     */
    private String formatIdArray(Collection<Long> ids) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Long id : ids) {
            if (!first) sb.append(",");
            sb.append(id.longValue());
            first = false;
        }
        return sb.append("}").toString();
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
