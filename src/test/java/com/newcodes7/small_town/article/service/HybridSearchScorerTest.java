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
    public void minMaxNormalize_이상치_포함_일반_정규화() {
        // given: 이상치가 있어도 일반 min-max 정규화 적용
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 1.0);
        scores.put(2L, 2.0);
        scores.put(3L, 3.0);
        scores.put(4L, 100.0);

        // when
        Map<Long, Double> result = scorer.minMaxNormalize(scores);

        // then: 일반 min-max (100.0이 최대이므로 상대적 순서 보존)
        assertEquals(1.0, result.get(4L), 0.001);   // 100.0 → 1.0
        assertEquals(0.0, result.get(1L), 0.001);   // 1.0 → 0.0
        assertTrue(result.get(3L) < result.get(4L)); // 순서 보존
        assertTrue(result.get(2L) < result.get(3L)); // 순서 보존
    }

    @Test
    public void calculateNSFScores_BM25만_있을때() {
        // given
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);
        bm25Scores.put(2L, 5.0);
        Map<Long, Double> vectorScores = new HashMap<>();

        // when
        HybridSearchScorer.NSFResult nsfResult = scorer.calculateNSFScores(bm25Scores, vectorScores);
        Map<Long, Double> result = nsfResult.getNsfScores();

        // then: BM25만 있을 때, 분모는 항상 BM25+Vector(1.0)
        // Vector 미참여 → 점수 0으로 취급되어 최종 스코어가 절반으로 줄어듦
        assertTrue(result.get(1L) > result.get(2L));
        assertEquals(0.5, result.get(1L), 0.001); // (0.5 * 1.0) / 1.0 = 0.5
        assertEquals(0.0, result.get(2L), 0.001); // (0.5 * 0.0) / 1.0 = 0.0

        // 정규화 점수 검증
        assertNotNull(nsfResult.getNormalizedBm25());
        assertEquals(2, nsfResult.getNormalizedBm25().size());
        assertTrue(nsfResult.getNormalizedVector().isEmpty());
        assertEquals(2, nsfResult.getWeightSums().size());
        assertEquals(1.0, nsfResult.getWeightSums().get(1L), 0.001);
    }

    @Test
    public void calculateNSFScores_BM25_Vector_결합() {
        // given
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);
        bm25Scores.put(2L, 5.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.9);
        vectorScores.put(3L, 0.7);

        // when
        HybridSearchScorer.NSFResult nsfResult = scorer.calculateNSFScores(bm25Scores, vectorScores);
        Map<Long, Double> result = nsfResult.getNsfScores();

        // then
        assertEquals(3, result.size());
        assertTrue(result.get(1L) > result.get(2L));
        assertTrue(result.get(1L) > result.get(3L));

        // 정규화 점수 검증
        assertNotNull(nsfResult.getNormalizedBm25());
        assertNotNull(nsfResult.getNormalizedVector());
        assertTrue(nsfResult.getNormalizedBm25().containsKey(1L));
        assertTrue(nsfResult.getNormalizedVector().containsKey(1L));
        assertTrue(nsfResult.getWeightSums().containsKey(1L));
        assertEquals(1.0, nsfResult.getWeightSums().get(1L), 0.001);
    }

    @Test
    public void calculateNSFScores_벡터_전용_결과_포함() {
        // given: Article 3은 벡터 검색에서만 발견
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 8.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.8);
        vectorScores.put(3L, 0.95);

        // when
        HybridSearchScorer.NSFResult nsfResult = scorer.calculateNSFScores(bm25Scores, vectorScores);
        Map<Long, Double> result = nsfResult.getNsfScores();

        // then: 벡터에서만 발견된 Article 3도 결과에 포함되지만,
        // BM25 미참여 페널티(분모에 BM25 가중치 포함)로 과대평가되지 않음
        assertTrue(result.containsKey(3L));
        assertTrue(result.get(3L) > 0);
        assertTrue(result.get(1L) >= result.get(3L),
                "BM25+Vector 모두 히트한 Article 1이 Vector만 히트한 Article 3보다 같거나 높아야 함");
    }

    @Test
    public void calculateNSFScores_가중치_합_정규화_검증() {
        // given: BM25 + Vector 모두 결과가 있는 경우
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.9);

        // when
        HybridSearchScorer.NSFResult nsfResult = scorer.calculateNSFScores(bm25Scores, vectorScores);
        Map<Long, Double> result = nsfResult.getNsfScores();

        // then: 최종 스코어가 1.0을 초과하지 않음
        // 단일 결과: BM25 10.0→1.0(클램핑), Vector 0.9→0.9
        // weightedSum = 0.5*1.0 + 0.5*0.9 = 0.95
        // weightSum = 0.5 + 0.5 = 1.0
        // nsfScore = 0.95 / 1.0 = 0.95
        assertTrue(result.get(1L) <= 1.0);
        assertTrue(result.get(1L) > 0.9);
        assertEquals(0.95, result.get(1L), 0.001);
    }

    @Test
    public void calculateNSFScores_단일_검색방법_과대평가_방지() {
        // given: Article A는 BM25+Vector 모두 히트, Article B는 Vector에서만 히트
        Map<Long, Double> bm25Scores = new HashMap<>();
        bm25Scores.put(1L, 10.0);  // A: BM25 최고점
        bm25Scores.put(2L, 5.0);

        Map<Long, Double> vectorScores = new HashMap<>();
        vectorScores.put(1L, 0.8);  // A: Vector 높은 점수
        vectorScores.put(3L, 0.95); // B: Vector에서만 발견, 매우 높은 점수

        // when
        HybridSearchScorer.NSFResult nsfResult = scorer.calculateNSFScores(bm25Scores, vectorScores);
        Map<Long, Double> result = nsfResult.getNsfScores();

        // then: BM25+Vector 모두 히트한 A가 Vector만 히트한 B보다 높아야 함
        assertTrue(result.get(1L) > result.get(3L),
                "2가지 검색 방법 모두에서 발견된 Article이 Vector만 히트한 Article보다 높아야 함");
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
