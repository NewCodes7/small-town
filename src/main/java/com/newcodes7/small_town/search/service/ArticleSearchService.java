package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.service.ArticleEmbeddingService;
import com.newcodes7.small_town.article.service.TermSynonymService;
import com.newcodes7.small_town.article.service.UserLikeService;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import com.newcodes7.small_town.search.scorer.HybridSearchScorer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
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
                                @Qualifier("searchExecutor") ExecutorService searchExecutor) {
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
     */
    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
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

    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
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

        if (keyword == null || keyword.trim().isEmpty()) {
            return Page.empty();
        }

        long totalStartTime = System.currentTimeMillis();

        // 1. 쿼리 복잡도 감지 → 적응형 BM25 title 배수 및 NSF 가중치 결정
        SemanticTermExpansionService.QueryComplexity complexity =
                semanticExpansionService.classifyQueryComplexity(keyword);
        double titleMultiplier;
        double bm25NsfWeight;
        double vectorNsfWeight;
        switch (complexity) {
            case SIMPLE:
                titleMultiplier = 4.0;
                bm25NsfWeight   = 0.65;
                vectorNsfWeight = 0.35;
                break;
            case MODERATE:
                titleMultiplier = 2.5;
                bm25NsfWeight   = 0.55;
                vectorNsfWeight = 0.45;
                break;
            default: // COMPLEX
                titleMultiplier = 1.5;
                bm25NsfWeight   = 0.5;
                vectorNsfWeight = 0.5;
                break;
        }
        log.info("[검색] keyword='{}' 쿼리 복잡도: {} (titleMultiplier={}, BM25={}, Vector={})",
                keyword, complexity, titleMultiplier, bm25NsfWeight, vectorNsfWeight);

        // 2. BM25 검색 쿼리 생성 (나중에 Vector-only article들의 BM25 점수 계산에 재사용)
        String bm25SearchQuery = (expandedTerms != null && !expandedTerms.isEmpty())
                ? buildBM25SearchQueryFromExpandedTerms(expandedTerms, titleMultiplier)
                : buildBM25SearchQuery(keyword, titleMultiplier);
        if (bm25SearchQuery == null || bm25SearchQuery.isEmpty()) {
            log.warn("BM25 검색 쿼리 생성 실패: '{}'", keyword);
            return Page.empty();
        }

        // 3. BM25와 Vector 검색을 병렬 실행
        // Vector 검색을 별도 스레드에서 비동기 실행 (임베딩 API 호출이 대부분의 시간)
        List<Integer> vectorDomesticTypes = convertRegionsToTypes(regions);
        List<String> vectorCategories = (category != null && !category.isEmpty()) ? category : null;
        AtomicLong vectorElapsedMs = new AtomicLong(0);
        CompletableFuture<VectorSearchService.VectorSearchResult> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        VectorSearchService.VectorSearchResult result = vectorSearchService.searchByKeywordWithEmbedding(keyword, vectorDomesticTypes, vectorCategories);
                        vectorElapsedMs.set(System.currentTimeMillis() - start);
                        return result;
                    } catch (Exception e) {
                        vectorElapsedMs.set(System.currentTimeMillis() - start);
                        log.warn("Vector 검색 실패 (스킵): {}", e.getMessage());
                        return new VectorSearchService.VectorSearchResult(new HashMap<>(), null);
                    }
                }, searchExecutor);

        // BM25 검색은 메인 스레드에서 실행 (트랜잭션 컨텍스트 유지)
        long bm25StartTime = System.currentTimeMillis();
        Map<Long, Double> bm25Results = new HashMap<>();
        Map<Long, LocalDateTime> publishedAtMap = new HashMap<>();

        List<Object[]> bm25RawResults = executeBM25Search(bm25SearchQuery, regions, category);
        for (Object[] row : bm25RawResults) {
            Long articleId = ((Number) row[0]).longValue();
            Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            java.sql.Timestamp timestamp = row.length > 2 ? (java.sql.Timestamp) row[2] : null;
            LocalDateTime publishedAt = timestamp != null ? timestamp.toLocalDateTime() : null;

            bm25Results.put(articleId, score);
            if (publishedAt != null) {
                publishedAtMap.put(articleId, publishedAt);
            }
        }
        long bm25EndTime = System.currentTimeMillis();

        // 3. Vector 검색 결과 대기
        Map<Long, Double> vectorResults = new HashMap<>();
        float[] queryEmbedding = null;
        VectorSearchService.VectorSearchResult vectorSearchResult = new VectorSearchService.VectorSearchResult(new HashMap<>(), null);
        try {
            vectorSearchResult = vectorFuture.get(5, TimeUnit.SECONDS);
            vectorResults = new HashMap<>(vectorSearchResult.getScores());
            queryEmbedding = vectorSearchResult.getQueryEmbedding();
        } catch (Exception e) {
            log.warn("Vector 검색 결과 대기 실패 (스킵): {}", e.getMessage());
        }

        // 원본 검색 결과 수 캡처 (cross-scoring 보충 전)
        int originalBm25Count = bm25Results.size();
        int originalVectorCount = vectorResults.size();

        // 4. 교차 점수 계산: 각 검색 방법에서만 발견된 article에 나머지 점수 보충 (병렬)
        Set<Long> bm25OnlyIds = new HashSet<>(bm25Results.keySet());
        bm25OnlyIds.removeAll(vectorResults.keySet());

        Set<Long> vectorOnlyIds = new HashSet<>(vectorResults.keySet());
        vectorOnlyIds.removeAll(bm25Results.keySet());

        // 4-1. Vector 보충 대상 (BM25-only)
        Set<Long> needVectorIds = new HashSet<>(bm25OnlyIds);

        // 4-2. BM25 보충 대상 (Vector-only)
        Set<Long> needBm25Ids = new HashSet<>(vectorOnlyIds);

        // 병렬 실행: Vector 보충 + BM25 보충
        final float[] cachedEmbedding = queryEmbedding;

        CompletableFuture<Map<Long, Double>> vectorSupplementFuture = CompletableFuture.supplyAsync(() -> {
            if (needVectorIds.isEmpty() || cachedEmbedding == null) return Map.<Long, Double>of();
            try {
                return vectorSearchService.computeSimilarityForArticlesWithEmbedding(cachedEmbedding, new ArrayList<>(needVectorIds));
            } catch (Exception e) {
                log.warn("교차검색 Vector 보충 실패: {}", e.getMessage());
                return Map.<Long, Double>of();
            }
        }, searchExecutor);

        CompletableFuture<List<Object[]>> bm25SupplementFuture = CompletableFuture.supplyAsync(() -> {
            if (needBm25Ids.isEmpty()) return Collections.<Object[]>emptyList();
            return computeBM25ScoreForArticles(bm25SearchQuery, regions, category, new ArrayList<>(needBm25Ids));
        }, searchExecutor);

        // 결과 대기 및 병합
        try {
            Map<Long, Double> supplementVectorScores = vectorSupplementFuture.get(5, TimeUnit.SECONDS);
            vectorResults.putAll(supplementVectorScores);
        } catch (Exception e) {
            log.warn("교차검색 Vector 보충 결과 대기 실패: {}", e.getMessage());
        }

        try {
            List<Object[]> supplementBm25 = bm25SupplementFuture.get(5, TimeUnit.SECONDS);
            for (Object[] row : supplementBm25) {
                Long id = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (score != null) {
                    bm25Results.put(id, score);
                }
            }
        } catch (Exception e) {
            log.warn("교차검색 BM25 보충 결과 대기 실패: {}", e.getMessage());
        }

        // 교차 점수 계산 완료 — 이후 lambda에서 참조할 수 있도록 final 변수 생성
        final Map<Long, Double> finalVectorResults = vectorResults;
        final Map<Long, Double> finalBm25Results = bm25Results;

        // 5-6. Normalized Score Fusion (NSF) 계산 + 순위 계산 (한 번에 수행)
        // NSF: 각 검색 방법의 점수를 min-max 정규화 후 가중 합산
        // RRF 대비 장점: 원본 점수의 크기 정보를 보존하여, 높은 유사도 결과를 더 정확히 반영
        long rerankStartTime = System.currentTimeMillis();
        final Map<Long, Integer> bm25Ranks = calculateRanks(finalBm25Results);
        final Map<Long, Integer> vectorRanks = calculateRanks(finalVectorResults);
        HybridSearchScorer.NSFResult nsfResult = hybridSearchScorer.calculateNSFScores(
                finalBm25Results, finalVectorResults, bm25NsfWeight, vectorNsfWeight);
        Map<Long, Double> nsfScores = nsfResult.getNsfScores();
        final Map<Long, Double> normalizedBm25Map = nsfResult.getNormalizedBm25();
        final Map<Long, Double> normalizedVectorMap = nsfResult.getNormalizedVector();
        final Map<Long, Double> weightSumMap = nsfResult.getWeightSums();
        long rerankEndTime = System.currentTimeMillis();

        if (nsfScores.isEmpty()) {
            log.warn("모든 검색 방법에서 결과가 없습니다: '{}'", keyword);
            return Page.empty();
        }

        long totalEndTime = System.currentTimeMillis();
        String embeddingCacheInfo = vectorSearchResult.isCacheHit()
                ? String.format("hit(%dms)", vectorSearchResult.getCacheLookupMs())
                : String.format("miss(%dms, lookup %dms)", vectorSearchResult.getEmbeddingMs(), vectorSearchResult.getCacheLookupMs());

        log.info("[검색] keyword='{}' | BM25: {}ms ({}개), Vector: {}ms (embedding: {}, query: {}ms) ({}개), Rerank(NSF): {}ms ({}개) | 총: {}ms",
            keyword,
            bm25EndTime - bm25StartTime, originalBm25Count,
            vectorElapsedMs.get(), embeddingCacheInfo, vectorSearchResult.getQueryMs(), originalVectorCount,
            rerankEndTime - rerankStartTime, nsfScores.size(),
            totalEndTime - totalStartTime);

        // 8. 전체 NSF 결과에 대해 (id, publishedAt) 경량 조회
        // - deleted_at 검증 (Materialized View 동기화 지연으로 인한 stale 항목 제거)
        // - vector-only 항목의 publishedAt 보충 (BM25 쿼리에서 수집되지 않은 날짜)
        List<Object[]> idAndDateRows = articleRepository.findIdAndPublishedAtByIdIn(
                new ArrayList<>(nsfScores.keySet()));

        Set<Long> validArticleIds = new HashSet<>();
        for (Object[] row : idAndDateRows) {
            Long id = ((Number) row[0]).longValue();
            validArticleIds.add(id);
            // JPQL 쿼리는 LocalDateTime 그대로 반환 (native SQL의 Timestamp와 다름)
            LocalDateTime publishedAt = row.length > 1 && row[1] != null ? (LocalDateTime) row[1] : null;
            if (publishedAt != null && !publishedAtMap.containsKey(id)) {
                publishedAtMap.put(id, publishedAt);
            }
        }

        log.debug("NSF 결과 {}개 중 유효한 article: {}개", nsfScores.size(), validArticleIds.size());

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

        // 11. 현재 페이지 article만 로딩 (Corporation fetch join으로 N+1 방지)
        Map<Long, Article> pageArticleMap = articleRepository.findByIdInWithCorporation(pageArticleIds)
                .stream()
                .collect(Collectors.toMap(Article::getId, a -> a));

        // 12. 좋아요 상태 batch 조회 (N+1 방지)
        Map<Long, Boolean> likeStatusMap = getLikeStatusMap(pageArticleIds, username, ipAddress);

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
                            bm25Rank, vectorRank, normBm25, normVector, weightSum, isLiked);
                })
                .collect(Collectors.toList());

        return new PageImpl<>(results, PageRequest.of(page, size), totalResults);
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
            java.sql.Timestamp timestamp = row.length > 1 ? (java.sql.Timestamp) row[1] : null;
            if (timestamp != null) {
                publishedAtMap.put(articleId, timestamp.toLocalDateTime());
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
     * 여러 term으로 검색 (유의어 포함)
     *
     * @param termStrings 검색할 term 문자열 목록
     * @param pageable 페이징 정보
     * @return 검색된 Article 목록
     */
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
        List<Long> termIds = new ArrayList<>();
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            // 원래 term으로 검색
            List<Term> terms = termRepository.findByTerm(termInfo.getTerm());

            // 찾지 못했고 영어인 경우, 대소문자 변형도 시도
            if (terms.isEmpty() && termInfo.getTermType().equals("SL")) {
                String term = termInfo.getTerm();
                // 소문자로 시도
                terms = termRepository.findByTerm(term.toLowerCase());
                // 대문자로 시도
                if (terms.isEmpty()) {
                    terms = termRepository.findByTerm(term.toUpperCase());
                }
            }

            // 모든 매칭된 term의 ID 추가
            terms.forEach(term -> termIds.add(term.getId()));
        }

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
     * Controller에서 expandSearchTerms를 이미 호출한 경우, 중복 호출을 방지하기 위해 사용
     *
     * @param expandedTerms 확장된 검색어와 가중치 맵
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQueryFromExpandedTerms(Map<String, Double> expandedTerms) {
        return buildBM25SearchQueryFromExpandedTerms(expandedTerms, 1.5);
    }

    /**
     * 이미 확장된 검색어로 BM25 검색 쿼리 문자열 생성 (적응형 title 배수)
     * Controller에서 expandSearchTerms를 이미 호출한 경우, 중복 호출을 방지하기 위해 사용
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
        log.info("BM25 raw 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, results.size());
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

        log.info("BM25 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, scoreMap.size());
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

            log.info("BM25 검색 완료 - 확장된 Term 수: {}, 결과 수: {}",
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
