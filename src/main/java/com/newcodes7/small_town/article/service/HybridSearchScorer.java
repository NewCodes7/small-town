package com.newcodes7.small_town.article.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 하이브리드 검색 스코어 퓨전 (Normalized Score Fusion)
 *
 * BM25, Vector, ILIKE 검색 결과를 정규화하고 가중 합산하여
 * 최종 검색 스코어를 계산하는 책임을 담당.
 *
 * - min-max 정규화 전 이상치 캡핑 (95th percentile)
 * - 가중치 합 정규화로 최종 스코어를 [0, 1] 범위로 보장
 */
@Component
public class HybridSearchScorer {

    // Normalized Score Fusion (NSF) 가중치
    public static final double NSF_WEIGHT_BM25 = 0.4;
    public static final double NSF_WEIGHT_VECTOR = 0.4;
    public static final double NSF_WEIGHT_TITLE_MIN = 0.1;
    public static final double NSF_WEIGHT_TITLE_MAX = 0.3;

    /**
     * NSF 스코어 계산 (정규화된 점수의 가중 합산, 가중치 합 정규화)
     *
     * 각 검색 방법의 점수를 min-max 정규화 후,
     * 가중 합산하고 가중치 합으로 나누어 [0, 1] 범위의 최종 스코어를 산출.
     *
     * @param bm25Scores BM25 검색 점수 맵
     * @param vectorScores 벡터 검색 유사도 점수 맵
     * @param titleScores ILIKE 제목 매칭 점수 맵
     * @param titleWeights 제목 매칭별 커버리지 기반 가중치 맵
     * @return Article ID → NSF 최종 스코어 맵
     */
    public Map<Long, Double> calculateNSFScores(
            Map<Long, Double> bm25Scores,
            Map<Long, Double> vectorScores,
            Map<Long, Double> titleScores,
            Map<Long, Double> titleWeights) {

        // 각 검색 방법의 min-max 정규화 (이상치 캡핑 포함)
        Map<Long, Double> normalizedBm25 = minMaxNormalize(bm25Scores);
        Map<Long, Double> normalizedVector = minMaxNormalize(vectorScores);
        Map<Long, Double> normalizedTitle = minMaxNormalize(titleScores);

        Map<Long, Double> nsfScores = new HashMap<>();

        // 모든 Article ID 수집
        Set<Long> allArticleIds = new HashSet<>();
        allArticleIds.addAll(bm25Scores.keySet());
        allArticleIds.addAll(vectorScores.keySet());
        allArticleIds.addAll(titleScores.keySet());

        // NSF 스코어 계산 (정규화된 점수의 가중 합산, 가중치 합 정규화)
        // BM25와 Vector 가중치는 해당 article에 점수가 없어도 항상 분모에 포함하여,
        // 1개 검색 방법에서만 발견된 article이 과대평가되는 것을 방지
        for (Long articleId : allArticleIds) {
            double weightedSum = 0.0;
            // BM25 + Vector 가중치는 항상 분모에 포함 (미참여 시 점수 0으로 취급)
            double weightSum = NSF_WEIGHT_BM25 + NSF_WEIGHT_VECTOR;

            if (normalizedBm25.containsKey(articleId)) {
                weightedSum += NSF_WEIGHT_BM25 * normalizedBm25.get(articleId);
            }

            if (normalizedVector.containsKey(articleId)) {
                weightedSum += NSF_WEIGHT_VECTOR * normalizedVector.get(articleId);
            }

            // Title 가중치는 참여한 경우에만 분모에 추가 (ILIKE 미실행 시 페널티 없음)
            if (normalizedTitle.containsKey(articleId)) {
                double titleWeight = titleWeights.getOrDefault(articleId, NSF_WEIGHT_TITLE_MIN);
                weightedSum += titleWeight * normalizedTitle.get(articleId);
                weightSum += titleWeight;
            }

            // 가중치 합으로 나누어 최종 스코어를 [0, 1] 범위로 정규화
            double nsfScore = (weightSum > 0) ? weightedSum / weightSum : 0.0;
            nsfScores.put(articleId, nsfScore);
        }

        return nsfScores;
    }

    /**
     * Min-Max 정규화: 점수를 0~1 범위로 변환
     *
     * - 정규화 전 이상치 캡핑 적용 (95th percentile)
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

        // 이상치 캡핑 적용
        Map<Long, Double> capped = capOutliers(scoreMap);

        double min = capped.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = capped.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double range = max - min;

        for (Map.Entry<Long, Double> entry : capped.entrySet()) {
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
     * 이상치 캡핑: 95th percentile 기반으로 극단적인 점수를 제한
     *
     * BM25 점수에서 [1.0, 2.0, 3.0, 100.0] 같은 이상치가 있으면
     * 상위 3개가 모두 0에 가까워지는 min-max 정규화의 문제를 방지.
     *
     * 결과가 20개 미만이면 캡핑 없이 원본 반환 (소규모 결과에서는 이상치 판단이 부정확)
     *
     * @param scoreMap 원본 점수 맵
     * @return 이상치가 캡핑된 점수 맵
     */
    public Map<Long, Double> capOutliers(Map<Long, Double> scoreMap) {
        if (scoreMap.size() < 20) {
            return new HashMap<>(scoreMap);
        }

        double[] sortedScores = scoreMap.values().stream()
                .mapToDouble(Double::doubleValue)
                .sorted()
                .toArray();

        // 95th percentile 계산 (선형 보간)
        int p95Index = (int) ((sortedScores.length - 1) * 0.95);
        p95Index = Math.min(p95Index, sortedScores.length - 1);
        double p95 = sortedScores[p95Index];

        Map<Long, Double> capped = new HashMap<>();
        for (Map.Entry<Long, Double> entry : scoreMap.entrySet()) {
            capped.put(entry.getKey(), Math.min(entry.getValue(), p95));
        }
        return capped;
    }

    /**
     * 제목 길이 대비 키워드 커버리지에 따른 타이틀 가중치 계산
     *
     * coverage = keywordLength / titleLength
     * weight = TITLE_MIN + (TITLE_MAX - TITLE_MIN) * coverage
     *
     * @param title 기사 제목
     * @param keywordLength 검색 키워드 길이
     * @return 커버리지 기반 타이틀 가중치 (TITLE_MIN ~ TITLE_MAX)
     */
    public double calculateTitleCoverageWeight(String title, int keywordLength) {
        if (title == null || title.isEmpty()) {
            return NSF_WEIGHT_TITLE_MIN;
        }
        double coverage = (double) keywordLength / title.length();
        coverage = Math.min(coverage, 1.0);
        return NSF_WEIGHT_TITLE_MIN + (NSF_WEIGHT_TITLE_MAX - NSF_WEIGHT_TITLE_MIN) * coverage;
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
