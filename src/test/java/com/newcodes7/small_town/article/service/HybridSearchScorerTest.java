package com.newcodes7.small_town.article.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * HybridSearchScorer (Normalized Score Fusion) 단위 테스트
 *
 * 스코어링 로직이 별도 클래스로 분리되어 Mock 없이 직접 인스턴스화 가능
 */
public class HybridSearchScorerTest {

    private final HybridSearchScorer scorer = new HybridSearchScorer();

    @Test
    public void minMaxNormalize_정상_정규화() {
        // given
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 10.0);
        scores.put(2L, 5.0);
        scores.put(3L, 0.0);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then
        assertEquals(1.0, result.get(1L), 0.001);
        assertEquals(0.5, result.get(2L), 0.001);
        assertEquals(0.0, result.get(3L), 0.001);
    }

    @Test
    public void minMaxNormalize_동일한_점수() {
        // given: 모든 점수가 같으면 원본 점수를 [0,1]로 클램핑
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 5.0);
        scores.put(2L, 5.0);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then: 5.0은 1.0 초과이므로 1.0으로 클램핑
        assertEquals(1.0, result.get(1L), 0.001);
        assertEquals(1.0, result.get(2L), 0.001);
    }

    @Test
    public void minMaxNormalize_빈_맵() {
        // given
        Map<Long, Double> scores = new HashMap<>();

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    public void minMaxNormalize_단일_항목() {
        // given
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 7.0);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then: 단일 항목은 원본 점수를 [0,1]로 클램핑 (7.0 → 1.0)
        assertEquals(1.0, result.get(1L), 0.001);
    }

    @Test
    public void minMaxNormalize_단일_벡터_결과_과대평가_방지() {
        // given: 벡터 유사도 0.52 (threshold 근처)인 단일 결과
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 0.52);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then: 0.52 그대로 보존 (기존에는 1.0으로 과대평가됨)
        assertEquals(0.52, result.get(1L), 0.001);
    }

    @Test
    public void minMaxNormalize_이상치_캡핑() {
        // given: BM25 점수에서 하나가 극단적으로 높은 경우
        // 이상치 없으면 상위 3개가 모두 0에 가까워지는 문제 방지
        Map<Long, Double> scores = new HashMap<>();
        for (long i = 1; i <= 20; i++) {
            scores.put(i, (double) i);
        }
        // 이상치 추가: 100.0
        scores.put(21L, 100.0);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then: 이상치가 캡핑되어 나머지 점수들이 고르게 분포
        // 캡핑 없이는 20.0/100.0 = 0.2, 19.0/100.0 ≈ 0.19 등으로 눌림
        // 캡핑 후에는 20.0이 최대값에 가까워져 상위 점수가 더 높게 정규화됨
        assertTrue(result.get(20L) > 0.8, "20번 항목이 이상치 캡핑 후 높은 정규화 점수를 가져야 함");
        assertTrue(result.get(15L) > 0.5, "15번 항목이 이상치 캡핑 후 중간 정규화 점수를 가져야 함");
    }

    @Test
    public void minMaxNormalize_소규모_결과는_캡핑_미적용() {
        // given: 20개 미만인 경우 캡핑 없이 일반 min-max 정규화
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 1.0);
        scores.put(2L, 2.0);
        scores.put(3L, 3.0);
        scores.put(4L, 100.0);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then: 캡핑 없이 일반 min-max (100.0이 최대이므로 3.0은 낮게 정규화)
        assertEquals(1.0, result.get(4L), 0.001); // 100.0 → 1.0
        assertTrue(result.get(3L) < 0.05, "소규모에서는 캡핑 없이 3.0/99.0 ≈ 0.02");
    }

    @Test
    public void capOutliers_대규모_결과에서_95th_percentile_캡핑() {
        // given
        Map<Long, Double> scores = new HashMap<>();
        for (long i = 1; i <= 20; i++) {
            scores.put(i, (double) i);
        }
        scores.put(21L, 100.0);

        // when
        Map<Long, Double> result = scorer.capOutliers(scores);

        // then: 100.0이 95th percentile (= 20.0)로 캡핑
        assertEquals(20.0, result.get(21L), 0.001);
        assertEquals(15.0, result.get(15L), 0.001); // 정상 점수는 변경 없음
    }

    @Test
    public void capOutliers_소규모_결과는_캡핑_미적용() {
        // given: 20개 미만
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 1.0);
        scores.put(2L, 100.0);

        // when
        Map<Long, Double> result = scorer.capOutliers(scores);

        // then: 캡핑 없이 원본 유지
        assertEquals(100.0, result.get(2L), 0.001);
    }

    @Test
    public void calculateNSFScores_BM25만_있을때() {
        // given
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);
        bm25Scores.put(2L, 5.0);
        Map<Long, Double> vectorScores = new HashMap<>();
        Map<Long, Double> titleScores = new HashMap<>();
        Map<Long, Double> titleWeights = new HashMap<>();

        // when
        Map<Long, Double> result = scorer.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then: BM25만 있을 때, 가중치 합 정규화로 최종 스코어 = 정규화된 점수 그대로
        assertTrue(result.get(1L) > result.get(2L));
        assertEquals(1.0, result.get(1L), 0.001); // (0.4 * 1.0) / 0.4 = 1.0
        assertEquals(0.0, result.get(2L), 0.001); // (0.4 * 0.0) / 0.4 = 0.0
    }

    @Test
    public void calculateNSFScores_모든_검색방법_결합() {
        // given
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);
        bm25Scores.put(2L, 5.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.9);
        vectorScores.put(3L, 0.7);

        Map<Long, Double> titleScores = new HashMap<>();
        titleScores.put(1L, 3.0);  // exact match

        Map<Long, Double> titleWeights = new HashMap<>();
        titleWeights.put(1L, 0.25);

        // when
        Map<Long, Double> result = scorer.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then
        assertEquals(3, result.size());
        assertTrue(result.get(1L) > result.get(2L));
        assertTrue(result.get(1L) > result.get(3L));
    }

    @Test
    public void calculateNSFScores_벡터_전용_결과_포함() {
        // given: Article 3은 벡터 검색에서만 발견
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 8.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.8);
        vectorScores.put(3L, 0.95);

        Map<Long, Double> titleScores = new HashMap<>();
        Map<Long, Double> titleWeights = new HashMap<>();

        // when
        Map<Long, Double> result = scorer.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then: 벡터에서 높은 점수를 받은 Article 3도 결과에 포함
        assertTrue(result.containsKey(3L));
        assertTrue(result.get(3L) > 0);
    }

    @Test
    public void calculateNSFScores_가중치_합_정규화_검증() {
        // given: 3가지 검색 방법 모두 결과가 있는 경우
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.9);

        Map<Long, Double> titleScores = new HashMap<>();
        titleScores.put(1L, 3.0);

        Map<Long, Double> titleWeights = new HashMap<>();
        titleWeights.put(1L, 0.3); // 최대 타이틀 가중치

        // when
        Map<Long, Double> result = scorer.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then: 최종 스코어가 1.0을 초과하지 않음
        // 단일 결과: BM25 10.0→1.0(클램핑), Vector 0.9→0.9, Title 3.0→1.0(클램핑)
        // weightedSum = 0.4*1.0 + 0.4*0.9 + 0.3*1.0 = 1.06
        // weightSum = 0.4 + 0.4 + 0.3 = 1.1
        // nsfScore = 1.06 / 1.1 ≈ 0.964
        assertTrue(result.get(1L) <= 1.0);
        assertTrue(result.get(1L) > 0.9);
    }

    @Test
    public void calculateTitleCoverageWeight_빈_제목() {
        // when
        double result = scorer.calculateTitleCoverageWeight("", 5);

        // then: 빈 제목은 최소 가중치 반환
        assertEquals(0.1, result, 0.001);
    }

    @Test
    public void calculateTitleCoverageWeight_높은_커버리지() {
        // given: 키워드가 제목의 대부분을 차지하는 경우
        String title = "Redis";
        int keywordLength = 5;

        // when
        double result = scorer.calculateTitleCoverageWeight(title, keywordLength);

        // then: 커버리지가 1.0이므로 최대 가중치
        assertEquals(0.3, result, 0.001);
    }

    @Test
    public void calculateTitleCoverageWeight_낮은_커버리지() {
        // given: 키워드가 제목의 작은 부분만 차지하는 경우
        String title = "대규모 트래픽 환경에서의 Redis 캐시 활용 전략과 주의사항";
        int keywordLength = 5;

        // when
        double result = scorer.calculateTitleCoverageWeight(title, keywordLength);

        // then: 커버리지가 낮으므로 최소 가중치에 가까움
        assertTrue(result > 0.1);
        assertTrue(result < 0.2);
    }

    @Test
    public void appendBoostedTerm_제목_부스트_1_5배_검증() {
        // given
        StringBuilder queryBuilder = new StringBuilder();
        String term = "redis";
        String boostValue = "2.0";

        // when
        scorer.appendBoostedTerm(queryBuilder, term, boostValue);

        // then: title boost = 2.0 * 1.5 = 3.0, content boost = 2.0
        String query = queryBuilder.toString();
        assertTrue(query.contains("title_terms:redis^3.0"));
        assertTrue(query.contains("content_terms:redis^2.0"));
    }

    @Test
    public void appendBoostedTerm_확장_term_부스트_검증() {
        // given: 확장 term의 경우 낮은 가중치
        StringBuilder queryBuilder = new StringBuilder();
        String term = "캐시";
        String boostValue = "0.5";

        // when
        scorer.appendBoostedTerm(queryBuilder, term, boostValue);

        // then: title boost = 0.5 * 1.5 = 0.8 (반올림), content boost = 0.5
        String query = queryBuilder.toString();
        assertTrue(query.contains("title_terms:캐시^0.8"));
        assertTrue(query.contains("content_terms:캐시^0.5"));
    }

    @Test
    public void appendBoostedTerm_여러_term_OR_연결() {
        // given
        StringBuilder queryBuilder = new StringBuilder();

        // when
        scorer.appendBoostedTerm(queryBuilder, "redis", "2.0");
        scorer.appendBoostedTerm(queryBuilder, "캐시", "1.0");

        // then: 두 번째 term 앞에 OR 연결
        String query = queryBuilder.toString();
        assertTrue(query.contains("content_terms:redis^2.0 OR title_terms:캐시"));
    }
}