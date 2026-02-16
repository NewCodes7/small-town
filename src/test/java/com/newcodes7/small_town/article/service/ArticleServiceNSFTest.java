package com.newcodes7.small_town.article.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.article.repository.ArticleRepository;

/**
 * Normalized Score Fusion (NSF) 및 관련 점수 계산 메서드 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
public class ArticleServiceNSFTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private com.newcodes7.small_town.article.repository.TermRepository termRepository;

    @Mock
    private com.newcodes7.small_town.article.repository.ArticleTermRepository articleTermRepository;

    @Mock
    private TermSynonymService termSynonymService;

    @Mock
    private com.newcodes7.small_town.global.service.MorphemeAnalyzer morphemeAnalyzer;

    @InjectMocks
    private ArticleService articleService;

    @Test
    public void minMaxNormalize_정상_정규화() {
        // given
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 10.0);
        scores.put(2L, 5.0);
        scores.put(3L, 0.0);

        // when
        Map<Long, Double> result = articleService.minMaxNormalize(scores);

        // then
        assertEquals(1.0, result.get(1L), 0.001);
        assertEquals(0.5, result.get(2L), 0.001);
        assertEquals(0.0, result.get(3L), 0.001);
    }

    @Test
    public void minMaxNormalize_동일한_점수() {
        // given: 모든 점수가 같으면 정규화 값은 1.0
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 5.0);
        scores.put(2L, 5.0);

        // when
        Map<Long, Double> result = articleService.minMaxNormalize(scores);

        // then
        assertEquals(1.0, result.get(1L), 0.001);
        assertEquals(1.0, result.get(2L), 0.001);
    }

    @Test
    public void minMaxNormalize_빈_맵() {
        // given
        Map<Long, Double> scores = new HashMap<>();

        // when
        Map<Long, Double> result = articleService.minMaxNormalize(scores);

        // then
        assertTrue(result.isEmpty());
    }

    @Test
    public void minMaxNormalize_단일_항목() {
        // given
        Map<Long, Double> scores = new HashMap<>();
        scores.put(1L, 7.0);

        // when
        Map<Long, Double> result = articleService.minMaxNormalize(scores);

        // then: 단일 항목은 1.0으로 정규화
        assertEquals(1.0, result.get(1L), 0.001);
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
        Map<Long, Double> result = articleService.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then: BM25 가중치(0.4)만 반영
        assertTrue(result.get(1L) > result.get(2L));
        assertEquals(0.4, result.get(1L), 0.001); // 1.0 * 0.4
        assertEquals(0.0, result.get(2L), 0.001); // 0.0 * 0.4
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
        Map<Long, Double> result = articleService.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then
        // Article 1: BM25 정규화(1.0)*0.4 + Vector 정규화(1.0)*0.4 + Title 정규화(1.0)*0.25
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
        Map<Long, Double> result = articleService.calculateNSFScores(
                bm25Scores, vectorScores, titleScores, titleWeights);

        // then: 벡터에서 높은 점수를 받은 Article 3도 결과에 포함
        assertTrue(result.containsKey(3L));
        assertTrue(result.get(3L) > 0);
    }

    @Test
    public void calculateTitleCoverageWeight_빈_제목() {
        // when
        double result = articleService.calculateTitleCoverageWeight("", 5);

        // then: 빈 제목은 최소 가중치 반환
        assertEquals(0.1, result, 0.001);
    }

    @Test
    public void calculateTitleCoverageWeight_높은_커버리지() {
        // given: 키워드가 제목의 대부분을 차지하는 경우
        String title = "Redis";
        int keywordLength = 5;

        // when
        double result = articleService.calculateTitleCoverageWeight(title, keywordLength);

        // then: 커버리지가 1.0이므로 최대 가중치
        assertEquals(0.3, result, 0.001);
    }

    @Test
    public void calculateTitleCoverageWeight_낮은_커버리지() {
        // given: 키워드가 제목의 작은 부분만 차지하는 경우
        String title = "대규모 트래픽 환경에서의 Redis 캐시 활용 전략과 주의사항";
        int keywordLength = 5;

        // when
        double result = articleService.calculateTitleCoverageWeight(title, keywordLength);

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
        articleService.appendBoostedTerm(queryBuilder, term, boostValue);

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
        articleService.appendBoostedTerm(queryBuilder, term, boostValue);

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
        articleService.appendBoostedTerm(queryBuilder, "redis", "2.0");
        articleService.appendBoostedTerm(queryBuilder, "캐시", "1.0");

        // then: 두 번째 term 앞에 OR 연결
        String query = queryBuilder.toString();
        assertTrue(query.contains("content_terms:redis^2.0 OR title_terms:캐시"));
    }
}
