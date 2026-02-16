package com.newcodes7.small_town.article.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * 하이브리드 검색 스코어 퓨전 (Normalized Score Fusion)
 *
 * BM25, Vector 검색 결과를 정규화하고 가중 합산하여
 * 최종 검색 스코어를 계산하는 책임을 담당.
 *
 * - 가중치 합 정규화로 최종 스코어를 [0, 1] 범위로 보장
 */
@Component
public class HybridSearchScorer {

    // Normalized Score Fusion (NSF) 가중치
    public static final double NSF_WEIGHT_BM25 = 0.5;
    public static final double NSF_WEIGHT_VECTOR = 0.5;

    /**
     * NSF 계산 결과를 담는 DTO
     * 최종 스코어와 각 검색 방법의 정규화 점수를 포함
     */
    @Getter
    public static class NSFResult {
        private final Map<Long, Double> nsfScores;
        private final Map<Long, Double> normalizedBm25;
        private final Map<Long, Double> normalizedVector;
        private final Map<Long, Double> weightSums;

        public NSFResult(Map<Long, Double> nsfScores,
                         Map<Long, Double> normalizedBm25,
                         Map<Long, Double> normalizedVector,
                         Map<Long, Double> weightSums) {
            this.nsfScores = nsfScores;
            this.normalizedBm25 = normalizedBm25;
            this.normalizedVector = normalizedVector;
            this.weightSums = weightSums;
        }
    }

    /**
     * NSF 스코어 계산 (정규화된 점수의 가중 합산, 가중치 합 정규화)
     *
     * 각 검색 방법의 점수를 min-max 정규화 후,
     * 가중 합산하고 가중치 합으로 나누어 [0, 1] 범위의 최종 스코어를 산출.
     *
     * @param bm25Scores BM25 검색 점수 맵
     * @param vectorScores 벡터 검색 유사도 점수 맵
     * @return NSFResult (최종 스코어 + 각 검색 방법의 정규화 점수)
     */
    public NSFResult calculateNSFScores(
            Map<Long, Double> bm25Scores,
            Map<Long, Double> vectorScores) {

        // 각 검색 방법의 min-max 정규화
        Map<Long, Double> normalizedBm25 = minMaxNormalize(bm25Scores);
        Map<Long, Double> normalizedVector = minMaxNormalize(vectorScores);

        Map<Long, Double> nsfScores = new HashMap<>();
        Map<Long, Double> weightSums = new HashMap<>();

        // 모든 Article ID 수집
        Set<Long> allArticleIds = new HashSet<>();
        allArticleIds.addAll(bm25Scores.keySet());
        allArticleIds.addAll(vectorScores.keySet());

        // NSF 스코어 계산 (정규화된 점수의 가중 합산, 가중치 합 정규화)
        // BM25와 Vector 가중치는 해당 article에 점수가 없어도 항상 분모에 포함하여,
        // 1개 검색 방법에서만 발견된 article이 과대평가되는 것을 방지
        double weightSum = NSF_WEIGHT_BM25 + NSF_WEIGHT_VECTOR;

        for (Long articleId : allArticleIds) {
            double weightedSum = 0.0;

            if (normalizedBm25.containsKey(articleId)) {
                weightedSum += NSF_WEIGHT_BM25 * normalizedBm25.get(articleId);
            }

            if (normalizedVector.containsKey(articleId)) {
                weightedSum += NSF_WEIGHT_VECTOR * normalizedVector.get(articleId);
            }

            // 가중치 합으로 나누어 최종 스코어를 [0, 1] 범위로 정규화
            double nsfScore = (weightSum > 0) ? weightedSum / weightSum : 0.0;
            nsfScores.put(articleId, nsfScore);
            weightSums.put(articleId, weightSum);
        }

        return new NSFResult(nsfScores, normalizedBm25, normalizedVector, weightSums);
    }

    /**
     * Min-Max 정규화: 점수를 0~1 범위로 변환
     *
     * - 결과가 1개이거나 모든 점수가 동일한 경우, 원본 점수를 [0, 1]로 클램핑
     *   (벡터 유사도 0.52 같은 threshold 근처 단일 결과가 1.0으로 과대평가되는 것을 방지)
     *
     * @param scoreMap Article ID → 원본 점수 맵
     * @return Article ID → 정규화된 점수 맵 (0~1 범위)
     */
    public Map<Long, Double> minMaxNormalize(Map<Long, Double> scoreMap) {
        Map<Long, Double> normalized = new HashMap<>();
        if (scoreMap == null || scoreMap.isEmpty()) {
            return normalized;
        }

        double min = scoreMap.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = scoreMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double range = max - min;

        for (Map.Entry<Long, Double> entry : scoreMap.entrySet()) {
            double normalizedScore;
            if (range > 0) {
                normalizedScore = (entry.getValue() - min) / range;
            } else {
                // 단일 결과이거나 모든 점수가 동일한 경우:
                // 원본 점수를 [0, 1]로 클램핑 (벡터 유사도는 이미 0~1 범위이므로 그대로 보존,
                // BM25/ILIKE 등 1.0 초과 점수는 1.0으로 제한)
                normalizedScore = Math.max(0.0, Math.min(entry.getValue(), 1.0));
            }
            normalized.put(entry.getKey(), normalizedScore);
        }

        return normalized;
    }

    /**
     * BM25 쿼리용 Term 따옴표 처리
     * 띄어쓰기가 포함된 term은 따옴표로 감싸고, 단일 단어는 그대로 반환
     *
     * @param term 검색 용어
     * @return 따옴표 처리된 용어
     */
    public String quoteTerm(String term) {
        if (term == null || term.isEmpty()) {
            return term;
        }

        if (term.contains(" ")) {
            return "\"" + term + "\"";
        }

        return term;
    }

    /**
     * BM25 쿼리에 부스트된 term 추가
     * ParadeDB parse()는 column:term 형식 필수
     * title_terms에 1.5배 가중치 부여하여 제목 매칭 우선
     *
     * @param queryBuilder 쿼리 문자열 빌더
     * @param term 검색 용어
     * @param boostValue 기본 부스트 값
     */
    public void appendBoostedTerm(StringBuilder queryBuilder, String term, String boostValue) {
        String quotedTerm = quoteTerm(term);
        if (queryBuilder.length() > 0) {
            queryBuilder.append(" OR ");
        }
        String titleBoost = String.format("%.1f", Double.parseDouble(boostValue) * 1.5);
        queryBuilder.append(String.format(
            "title_terms:%s^%s OR content_terms:%s^%s",
            quotedTerm, titleBoost, quotedTerm, boostValue
        ));
    }
}
