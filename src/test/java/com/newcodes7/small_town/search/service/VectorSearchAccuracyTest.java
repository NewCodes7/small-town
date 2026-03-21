package com.newcodes7.small_town.search.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 벡터 검색 정확도 측정 통합 테스트
 *
 * Binary HNSW + halfvec 2단계 검색의 정확도 손실을 정량화합니다.
 * 실제 DB(small_town_test)에서 Exact Search와 2단계 검색을 비교합니다.
 *
 * 실행 방법:
 *   ./gradlew test --tests "*VectorSearchAccuracyTest*"
 *
 * 주의: Clova API 호출이 발생할 수 있습니다 (캐시 미스 시).
 */
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
class VectorSearchAccuracyTest {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchAccuracyTest.class);

    @Autowired
    private VectorSearchAccuracyService accuracyService;

    @Test
    @DisplayName("벡터 검색 정확도 측정 - Recall@K, NDCG@K, Threshold 손실")
    void measureVectorSearchAccuracy() {
        int topK = 10;
        int candidateLimit = 200;
        double threshold = 0.52;

        Map<String, Object> result = accuracyService.measure(null, topK, candidateLimit, threshold);

        printReport(result, topK);
    }

    @Test
    @DisplayName("Stage1 candidateLimit 조정 비교 - 100 vs 200 vs 500")
    void compareCandidateLimits() {
        List<String> testKeywords = List.of(
                "Spring Boot", "Kubernetes", "Redis",
                "마이크로서비스 아키텍처 설계", "JPA N+1 문제 해결", "Docker 컨테이너 최적화"
        );
        int topK = 10;
        double threshold = 0.52;

        log.info("\n=== candidateLimit 조정 비교 ===");
        for (int limit : new int[]{100, 200, 500}) {
            Map<String, Object> result = accuracyService.measure(testKeywords, topK, limit, threshold);
            @SuppressWarnings("unchecked")
            Map<String, Object> stage1 = (Map<String, Object>) result.get("stage1");
            @SuppressWarnings("unchecked")
            Map<String, Object> stage2 = (Map<String, Object>) result.get("stage2");

            log.info("candidateLimit={}: stage1Recall@{}={}, stage2Recall@{}={}, missRate={}",
                    limit, topK, stage1.get("recallAt" + topK),
                    topK, stage2.get("recallAt" + topK),
                    stage2.get("missRate"));
        }
    }

    @Test
    @DisplayName("threshold 조정 영향 분석 - 0.45 vs 0.52 vs 0.60")
    void compareThresholds() {
        List<String> testKeywords = List.of(
                "Spring Boot", "Kubernetes", "Redis",
                "마이크로서비스 아키텍처 설계", "대용량 트래픽 처리"
        );
        int topK = 10;
        int candidateLimit = 200;

        log.info("\n=== threshold 조정 영향 분석 ===");
        for (double thresh : new double[]{0.45, 0.52, 0.60}) {
            Map<String, Object> result = accuracyService.measure(testKeywords, topK, candidateLimit, thresh);
            @SuppressWarnings("unchecked")
            Map<String, Object> stage2 = (Map<String, Object>) result.get("stage2");

            log.info("threshold={}: recall@{}={}, avgRelevant={}, avgReturned={}, missRate={}",
                    thresh, topK, stage2.get("recallAt" + topK),
                    stage2.get("avgRelevantArticles"), stage2.get("avgReturnedArticles"),
                    stage2.get("missRate"));
        }
    }

    private void printReport(Map<String, Object> result, int topK) {
        if (result.containsKey("error")) {
            log.warn("측정 실패: {}", result.get("error"));
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> stage1 = (Map<String, Object>) result.get("stage1");
        @SuppressWarnings("unchecked")
        Map<String, Object> stage2 = (Map<String, Object>) result.get("stage2");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> worstQueries = (List<Map<String, Object>>) result.get("worstQueries");
        @SuppressWarnings("unchecked")
        List<String> queriesUsed = (List<String>) result.get("queriesUsed");

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 벡터 검색 정확도 측정 결과 ===\n");
        sb.append(String.format("쿼리 수: %d개%n", result.get("queryCount")));
        sb.append(String.format("topK: %d, candidateLimit: %d, threshold: %s%n",
                result.get("topK"), result.get("candidateLimit"), result.get("threshold")));
        sb.append("\n");

        sb.append("[Stage 1: Binary HNSW 후보 필터링]\n");
        sb.append(String.format("  Recall@%d:  %.1f%%%n", topK,
                toPercent(stage1.get("recallAt" + topK))));
        sb.append(String.format("  Recall@20: %.1f%%%n", toPercent(stage1.get("recallAt20"))));
        sb.append(String.format("  Recall@50: %.1f%%%n", toPercent(stage1.get("recallAt50"))));
        sb.append(String.format("  Recall@100: %.1f%%%n", toPercent(stage1.get("recallAt100"))));
        sb.append("\n");

        sb.append(String.format("[Stage 2: 2단계 최종 결과 (threshold=%.2f)]%n", result.get("threshold")));
        sb.append(String.format("  Recall@%d:  %.1f%%%n", topK,
                toPercent(stage2.get("recallAt" + topK))));
        sb.append(String.format("  Recall@20: %.1f%%%n", toPercent(stage2.get("recallAt20"))));
        sb.append(String.format("  NDCG@%d:    %.1f%%%n", topK,
                toPercent(stage2.get("ndcgAt" + topK))));
        sb.append(String.format("  NDCG@20:   %.1f%%%n", toPercent(stage2.get("ndcgAt20"))));
        sb.append("\n");

        sb.append("[Threshold 손실]\n");
        sb.append(String.format("  Exact에서 관련 아티클 평균: %.1f개 (similarity >= %.2f)%n",
                toDouble(stage2.get("avgRelevantArticles")), result.get("threshold")));
        sb.append(String.format("  Stage2 반환 평균:           %.1f개%n",
                toDouble(stage2.get("avgReturnedArticles"))));
        sb.append(String.format("  누락률:                     %.1f%%%n",
                toPercent(stage2.get("missRate"))));
        sb.append("\n");

        sb.append("[쿼리별 최악 케이스 Top 5]\n");
        if (worstQueries != null) {
            for (Map<String, Object> worst : worstQueries) {
                sb.append(String.format("  \"%s\" → stage2Recall@%d: %.1f%%, stage1Recall@%d: %.1f%%%n",
                        worst.get("keyword"), topK,
                        toPercent(worst.get("stage2RecallAtTopK")),
                        topK,
                        toPercent(worst.get("stage1RecallAtTopK"))));
            }
        }

        sb.append("\n[사용된 쿼리 목록]\n");
        if (queriesUsed != null) {
            sb.append("  ").append(String.join(", ", queriesUsed)).append("\n");
        }

        log.info(sb.toString());
    }

    private double toPercent(Object value) {
        if (value == null) return 0.0;
        return ((Number) value).doubleValue() * 100;
    }

    private double toDouble(Object value) {
        if (value == null) return 0.0;
        return ((Number) value).doubleValue();
    }
}
