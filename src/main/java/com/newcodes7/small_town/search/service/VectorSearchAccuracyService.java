package com.newcodes7.small_town.search.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.newcodes7.small_town.embedding.repository.ArticleChunkAccuracyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 벡터 검색 정확도 측정 서비스
 *
 * Binary HNSW + halfvec 2단계 검색의 정확도 손실을 정량화합니다.
 * - Recall@K: Ground Truth(Exact Search) 대비 커버리지
 * - NDCG@K: 순서 민감 품질 지표
 * - Threshold 손실: 관련 아티클 누락률
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchAccuracyService {

    private final VectorSearchService vectorSearchService;
    private final SearchQueryEmbeddingService searchQueryEmbeddingService;
    private final ArticleChunkAccuracyRepository accuracyRepository;
    private final JdbcTemplate jdbcTemplate;

    // Exact Search 기본 파라미터
    private static final int EXACT_SEARCH_LIMIT = 200;
    private static final int DEFAULT_TOP_K = 3;

    // 기본 도메인 키워드 (SearchLog에 데이터 없을 때 사용)
    private static final List<String> DEFAULT_TEST_QUERIES = List.of(
            "Spring Boot", "Kubernetes", "React", "MSA", "Docker",
            "JPA", "Redis", "Kafka", "GraphQL", "TypeScript",
            "React 상태관리 라이브러리 비교", "검색 성능 최적화",
            "마이크로서비스 아키텍처 설계", "JPA N+1 문제 해결",
            "Kubernetes 운영 노하우", "대용량 트래픽 처리",
            "CI/CD 파이프라인 구축", "데이터베이스 인덱스 최적화",
            "Spring Security JWT 인증", "Docker 컨테이너 최적화"
    );

    /**
     * 벡터 검색 정확도 측정 실행
     *
     * @param keywords 테스트 키워드 목록 (null이면 SearchLog 상위 키워드 또는 기본 키워드 사용)
     * @param topK Recall@K 기준 K값
     * @param candidateLimit Stage1 후보 수
     * @param threshold Stage2 유사도 임계값
     * @return 측정 결과 맵 (JSON 직렬화 가능)
     */
    public Map<String, Object> measure(List<String> keywords, int topK, int candidateLimit, double threshold) {
        List<String> testQueries = resolveTestQueries(keywords);
        log.info("벡터 검색 정확도 측정 시작 - 쿼리 수: {}, topK: {}, candidateLimit: {}, threshold: {}",
                testQueries.size(), topK, candidateLimit, threshold);

        // 측정 누적 결과
        List<Double> stage1RecallAtTopK = new ArrayList<>();
        List<Double> stage1RecallAt20 = new ArrayList<>();
        List<Double> stage1RecallAt50 = new ArrayList<>();
        List<Double> stage1RecallAt100 = new ArrayList<>();

        List<Double> stage2RecallAtTopK = new ArrayList<>();
        List<Double> stage2RecallAt20 = new ArrayList<>();
        List<Double> stage2NdcgAtTopK = new ArrayList<>();
        List<Double> stage2NdcgAt20 = new ArrayList<>();

        List<Integer> exactAboveThresholdCounts = new ArrayList<>();
        List<Integer> stage2ReturnedCounts = new ArrayList<>();

        // 쿼리별 결과 (최악 케이스 선별용)
        List<Map<String, Object>> queryDetails = new ArrayList<>();

        for (String keyword : testQueries) {
            try {
                Map<String, Object> queryResult = measureSingleQuery(
                        keyword, topK, candidateLimit, threshold);
                queryDetails.add(queryResult);

                stage1RecallAtTopK.add((Double) queryResult.get("stage1RecallAtTopK"));
                stage1RecallAt20.add((Double) queryResult.get("stage1RecallAt20"));
                stage1RecallAt50.add((Double) queryResult.get("stage1RecallAt50"));
                stage1RecallAt100.add((Double) queryResult.get("stage1RecallAt100"));

                stage2RecallAtTopK.add((Double) queryResult.get("stage2RecallAtTopK"));
                stage2RecallAt20.add((Double) queryResult.get("stage2RecallAt20"));
                stage2NdcgAtTopK.add((Double) queryResult.get("stage2NdcgAtTopK"));
                stage2NdcgAt20.add((Double) queryResult.get("stage2NdcgAt20"));

                exactAboveThresholdCounts.add((Integer) queryResult.get("exactAboveThreshold"));
                stage2ReturnedCounts.add((Integer) queryResult.get("stage2Returned"));

                log.info("[쿼리] '{}' | s1Recall@{}={}% s2Recall@{}={}% NDCG@{}={}% | exact={} returned={} miss={}%",
                        keyword,
                        topK, pct((Double) queryResult.get("stage1RecallAtTopK")),
                        topK, pct((Double) queryResult.get("stage2RecallAtTopK")),
                        topK, pct((Double) queryResult.get("stage2NdcgAtTopK")),
                        queryResult.get("exactAboveThreshold"),
                        queryResult.get("stage2Returned"),
                        missRatePct((Integer) queryResult.get("exactAboveThreshold"),
                                    (Integer) queryResult.get("stage2Returned")));

            } catch (Exception e) {
                log.warn("쿼리 측정 실패 - '{}': {}", keyword, e.getMessage());
            }
        }

        int measured = queryDetails.size();
        if (measured == 0) {
            return Map.of("error", "측정 가능한 쿼리가 없습니다. 임베딩 데이터를 확인해주세요.");
        }

        // 누락률 계산
        double avgExactAbove = exactAboveThresholdCounts.stream().mapToInt(i -> i).average().orElse(0);
        double avgStage2Returned = stage2ReturnedCounts.stream().mapToInt(i -> i).average().orElse(0);
        double missRate = avgExactAbove > 0
                ? Math.max(0, (avgExactAbove - avgStage2Returned) / avgExactAbove)
                : 0.0;

        // 최악 케이스 Top 5 (stage2RecallAtTopK 기준 오름차순)
        List<Map<String, Object>> worstQueries = queryDetails.stream()
                .sorted(Comparator.comparingDouble(m -> (Double) m.get("stage2RecallAtTopK")))
                .limit(5)
                .map(m -> {
                    Map<String, Object> worst = new LinkedHashMap<>();
                    worst.put("keyword", m.get("keyword"));
                    worst.put("stage2RecallAtTopK", round2(((Double) m.get("stage2RecallAtTopK"))));
                    worst.put("stage1RecallAtTopK", round2(((Double) m.get("stage1RecallAtTopK"))));
                    worst.put("exactAboveThreshold", m.get("exactAboveThreshold"));
                    worst.put("stage2Returned", m.get("stage2Returned"));
                    return worst;
                })
                .collect(Collectors.toList());

        // 응답 구성
        Map<String, Object> stage1 = new LinkedHashMap<>();
        stage1.put("recallAt" + topK, round2(avg(stage1RecallAtTopK)));
        stage1.put("recallAt20", round2(avg(stage1RecallAt20)));
        stage1.put("recallAt50", round2(avg(stage1RecallAt50)));
        stage1.put("recallAt100", round2(avg(stage1RecallAt100)));

        Map<String, Object> stage2 = new LinkedHashMap<>();
        stage2.put("recallAt" + topK, round2(avg(stage2RecallAtTopK)));
        stage2.put("recallAt20", round2(avg(stage2RecallAt20)));
        stage2.put("ndcgAt" + topK, round2(avg(stage2NdcgAtTopK)));
        stage2.put("ndcgAt20", round2(avg(stage2NdcgAt20)));
        stage2.put("avgRelevantArticles", round2(avgExactAbove));
        stage2.put("avgReturnedArticles", round2(avgStage2Returned));
        stage2.put("missRate", round2(missRate));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("queryCount", measured);
        response.put("topK", topK);
        response.put("candidateLimit", candidateLimit);
        response.put("threshold", threshold);
        response.put("stage1", stage1);
        response.put("stage2", stage2);
        response.put("worstQueries", worstQueries);
        response.put("queriesUsed", testQueries.stream().limit(measured).collect(Collectors.toList()));

        logSummary(measured, topK, candidateLimit, threshold,
                stage1RecallAtTopK, stage2RecallAtTopK, stage2NdcgAtTopK,
                avgExactAbove, avgStage2Returned, missRate, queryDetails);

        return response;
    }

    /**
     * 단일 쿼리에 대한 정확도 측정
     */
    private Map<String, Object> measureSingleQuery(
            String keyword, int topK, int candidateLimit, double threshold) {

        // 1. 임베딩 획득
        float[] embedding = searchQueryEmbeddingService.getOrCreateEmbedding(keyword);
        if (embedding == null) {
            throw new IllegalStateException("임베딩 획득 실패: " + keyword);
        }

        String vectorString = formatVector(embedding);
        String binaryString = toBinaryString(embedding);

        // 2. Ground Truth: Exact Search (threshold 없이 상위 200개)
        Map<Long, Double> exactResults = accuracyRepository.exactSearch(vectorString, DEFAULT_TOP_K, EXACT_SEARCH_LIMIT);
        List<Long> exactRanked = new ArrayList<>(exactResults.keySet()); // 유사도 내림차순

        // 3. Stage1: Binary HNSW 후보 (reranking 전)
        List<Long> stage1Ids = accuracyRepository.stage1CandidateIds(binaryString, candidateLimit);
        Set<Long> stage1Set = new HashSet<>(stage1Ids);

        // 4. Stage2: 2단계 전체 검색 (threshold 적용)
        Map<Long, Double> stage2Results = vectorSearchService.searchByKeyword(keyword, threshold, DEFAULT_TOP_K, 100);
        List<Long> stage2Ranked = stage2Results.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        Set<Long> stage2Set = new HashSet<>(stage2Ranked);

        // 5. Exact에서 threshold 이상인 아티클 수 계산
        int exactAboveThreshold = (int) exactResults.values().stream()
                .filter(sim -> sim >= threshold)
                .count();

        // 6. Recall@K 계산
        double s1RecallAtTopK = calculateRecall(exactRanked, stage1Set, topK);
        double s1RecallAt20 = calculateRecall(exactRanked, stage1Set, 20);
        double s1RecallAt50 = calculateRecall(exactRanked, stage1Set, 50);
        double s1RecallAt100 = calculateRecall(exactRanked, stage1Set, 100);

        double s2RecallAtTopK = calculateRecall(exactRanked, stage2Set, topK);
        double s2RecallAt20 = calculateRecall(exactRanked, stage2Set, 20);

        // 7. NDCG@K 계산 (Ground Truth 유사도를 relevance score로 사용)
        double s2NdcgAtTopK = calculateNdcg(exactResults, stage2Ranked, topK);
        double s2NdcgAt20 = calculateNdcg(exactResults, stage2Ranked, 20);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("stage1RecallAtTopK", s1RecallAtTopK);
        result.put("stage1RecallAt20", s1RecallAt20);
        result.put("stage1RecallAt50", s1RecallAt50);
        result.put("stage1RecallAt100", s1RecallAt100);
        result.put("stage2RecallAtTopK", s2RecallAtTopK);
        result.put("stage2RecallAt20", s2RecallAt20);
        result.put("stage2NdcgAtTopK", s2NdcgAtTopK);
        result.put("stage2NdcgAt20", s2NdcgAt20);
        result.put("exactAboveThreshold", exactAboveThreshold);
        result.put("stage2Returned", stage2Set.size());
        return result;
    }

    /**
     * Recall@K 계산
     * groundTruthRanked의 상위 K개 중 results에 포함된 비율
     */
    private double calculateRecall(List<Long> groundTruthRanked, Set<Long> results, int k) {
        if (groundTruthRanked.isEmpty() || k <= 0) {
            return 0.0;
        }
        int effectiveK = Math.min(k, groundTruthRanked.size());
        if (effectiveK == 0) {
            return 0.0;
        }
        long hits = groundTruthRanked.subList(0, effectiveK).stream()
                .filter(results::contains)
                .count();
        return (double) hits / effectiveK;
    }

    /**
     * NDCG@K 계산
     * groundTruthScores의 유사도를 relevance score로 사용
     */
    private double calculateNdcg(Map<Long, Double> groundTruthScores, List<Long> rankedResults, int k) {
        if (groundTruthScores.isEmpty() || rankedResults.isEmpty() || k <= 0) {
            return 0.0;
        }

        // DCG@K 계산
        double dcg = 0.0;
        int limit = Math.min(k, rankedResults.size());
        for (int i = 0; i < limit; i++) {
            Long articleId = rankedResults.get(i);
            double relevance = groundTruthScores.getOrDefault(articleId, 0.0);
            // relevance를 0~1 범위 그대로 사용 (cosine similarity)
            dcg += relevance / (Math.log(i + 2) / Math.log(2));
        }

        // IDCG@K 계산 (이상적인 순서: 유사도 내림차순 상위 K개)
        double idcg = 0.0;
        List<Double> idealRelevances = groundTruthScores.values().stream()
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .collect(Collectors.toList());
        for (int i = 0; i < idealRelevances.size(); i++) {
            idcg += idealRelevances.get(i) / (Math.log(i + 2) / Math.log(2));
        }

        if (idcg == 0.0) {
            return 0.0;
        }
        return dcg / idcg;
    }

    /**
     * 테스트 쿼리 목록 결정
     * 1순위: 파라미터로 전달된 keywords
     * 2순위: search_log 테이블 상위 빈도 키워드
     * 3순위: 하드코딩된 기본 키워드
     */
    private List<String> resolveTestQueries(List<String> keywords) {
        if (keywords != null && !keywords.isEmpty()) {
            return keywords;
        }

        try {
            String sql = """
                    SELECT search_keyword, COUNT(*) as cnt
                    FROM search_log
                    WHERE search_keyword IS NOT NULL AND LENGTH(TRIM(search_keyword)) > 0
                    GROUP BY search_keyword
                    ORDER BY cnt DESC
                    LIMIT 30
                    """;
            List<String> logKeywords = jdbcTemplate.queryForList(sql, String.class);
            if (!logKeywords.isEmpty()) {
                log.debug("SearchLog에서 {}개 키워드 사용", logKeywords.size());
                return logKeywords;
            }
        } catch (Exception e) {
            log.debug("SearchLog 조회 실패, 기본 키워드 사용: {}", e.getMessage());
        }

        return DEFAULT_TEST_QUERIES;
    }

    /**
     * float[] 임베딩을 PostgreSQL halfvec 포맷으로 변환
     */
    private String formatVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * float[] 임베딩을 Binary String으로 변환
     */
    private String toBinaryString(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length);
        for (float val : embedding) {
            sb.append(val >= 0 ? '1' : '0');
        }
        return sb.toString();
    }

    private double avg(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private double round2(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** 0~1 값을 소수점 1자리 % 문자열로 변환 */
    private String pct(double value) {
        return String.format("%.1f", value * 100);
    }

    /** exact/returned로 누락률 % 문자열 계산 */
    private String missRatePct(int exact, int returned) {
        if (exact == 0) return "0.0";
        return String.format("%.1f", Math.max(0, (exact - returned) * 100.0 / exact));
    }

    /** 표준편차 계산 */
    private double stdDev(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double mean = avg(values);
        double variance = values.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average()
                .orElse(0.0);
        return Math.sqrt(variance);
    }

    /**
     * 측정 완료 요약 로그 출력
     *
     * - avg/min/max/std for stage1/stage2 Recall@K, NDCG@K
     * - Recall 분포 (≥90%, 70~90%, <70%)
     * - Threshold 손실 요약
     * - 최악 쿼리 Top 5
     */
    private void logSummary(int measured, int topK, int candidateLimit, double threshold,
                            List<Double> stage1RecallAtTopK,
                            List<Double> stage2RecallAtTopK,
                            List<Double> stage2NdcgAtTopK,
                            double avgExactAbove, double avgStage2Returned, double missRate,
                            List<Map<String, Object>> queryDetails) {

        DoubleSummaryStatistics s1Stats = stage1RecallAtTopK.stream()
                .mapToDouble(v -> v).summaryStatistics();
        DoubleSummaryStatistics s2Stats = stage2RecallAtTopK.stream()
                .mapToDouble(v -> v).summaryStatistics();
        DoubleSummaryStatistics ndcgStats = stage2NdcgAtTopK.stream()
                .mapToDouble(v -> v).summaryStatistics();

        long high = stage2RecallAtTopK.stream().filter(v -> v >= 0.9).count();
        long mid  = stage2RecallAtTopK.stream().filter(v -> v >= 0.7 && v < 0.9).count();
        long low  = stage2RecallAtTopK.stream().filter(v -> v < 0.7).count();

        List<Map<String, Object>> worst = queryDetails.stream()
                .sorted(Comparator.comparingDouble(m -> (Double) m.get("stage2RecallAtTopK")))
                .limit(5)
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n=== 벡터 검색 정확도 측정 결과 ===%n"));
        sb.append(String.format("쿼리: %d개 | topK=%d | candidateLimit=%d | threshold=%.2f%n",
                measured, topK, candidateLimit, threshold));

        sb.append(String.format("%n[Stage1 Binary HNSW Recall@%d]%n", topK));
        sb.append(String.format("  avg=%.1f%%  min=%.1f%%  max=%.1f%%  std=±%.1f%%%n",
                s1Stats.getAverage() * 100, s1Stats.getMin() * 100,
                s1Stats.getMax() * 100, stdDev(stage1RecallAtTopK) * 100));

        sb.append(String.format("%n[Stage2 최종 결과 Recall@%d]%n", topK));
        sb.append(String.format("  avg=%.1f%%  min=%.1f%%  max=%.1f%%  std=±%.1f%%%n",
                s2Stats.getAverage() * 100, s2Stats.getMin() * 100,
                s2Stats.getMax() * 100, stdDev(stage2RecallAtTopK) * 100));

        sb.append(String.format("%n[NDCG@%d]%n", topK));
        sb.append(String.format("  avg=%.1f%%  min=%.1f%%  max=%.1f%%%n",
                ndcgStats.getAverage() * 100, ndcgStats.getMin() * 100, ndcgStats.getMax() * 100));

        sb.append(String.format("%n[Recall@%d 분포]%n", topK));
        sb.append(String.format("  ≥90%%: %d개 (%.0f%%)  70~90%%: %d개 (%.0f%%)  <70%%: %d개 (%.0f%%)%n",
                high, high * 100.0 / measured,
                mid,  mid  * 100.0 / measured,
                low,  low  * 100.0 / measured));

        sb.append(String.format("%n[Threshold 손실 (threshold=%.2f)]%n", threshold));
        sb.append(String.format("  exact 관련 avg=%.1f개  stage2 반환 avg=%.1f개  누락률=%.1f%%%n",
                avgExactAbove, avgStage2Returned, missRate * 100));

        sb.append(String.format("%n[최악 쿼리 Top5 (stage2Recall@%d 기준)]%n", topK));
        for (Map<String, Object> q : worst) {
            sb.append(String.format("  %-30s s1=%.1f%% s2=%.1f%% ndcg=%.1f%% exact=%d ret=%d%n",
                    "\"" + q.get("keyword") + "\"",
                    (Double) q.get("stage1RecallAtTopK") * 100,
                    (Double) q.get("stage2RecallAtTopK") * 100,
                    (Double) q.get("stage2NdcgAtTopK") * 100,
                    (Integer) q.get("exactAboveThreshold"),
                    (Integer) q.get("stage2Returned")));
        }

        log.info(sb.toString());
    }
}
