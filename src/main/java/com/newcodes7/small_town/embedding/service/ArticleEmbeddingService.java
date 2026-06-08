package com.newcodes7.small_town.embedding.service;

import org.springframework.stereotype.Service;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.service.EmbeddingApiService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Embedding 공용 서비스
 * - generateEmbedding: Embedding API 호출
 * - computeCosineSimilarity / interpretSimilarity: 테스트/분석용 유틸
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleEmbeddingService {

    private final EmbeddingApiService embeddingApiService;

    /**
     * 텍스트를 임베딩 벡터로 변환
     *
     * @param text 임베딩할 텍스트
     * @return 임베딩 벡터 (실패 시 null)
     */
    public float[] generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        try {
            ModelEmbeddingResult result = embeddingApiService.generateEmbedding(text, null);
            if (!result.isSuccess() || result.getEmbedding() == null) {
                log.warn("임베딩 생성 실패: {}", result.getErrorMessage());
                return null;
            }
            return result.getEmbedding();
        } catch (Exception e) {
            log.error("임베딩 생성 중 오류 발생", e);
            return null;
        }
    }

    /**
     * 두 벡터 간의 코사인 유사도 계산
     *
     * 코사인 유사도 = (A · B) / (||A|| × ||B||)
     * 결과값 범위: -1 ~ 1 (보통 0 ~ 1 사이)
     *
     * @param vec1 첫 번째 벡터
     * @param vec2 두 번째 벡터
     * @return 코사인 유사도 (-1 ~ 1)
     */
    public double computeCosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            log.error("벡터가 null이거나 길이가 다릅니다.");
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);

        if (denominator == 0.0) {
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * 유사도 점수에 대한 해석 제공
     *
     * @param similarity 유사도 점수
     * @return 해석 문자열
     */
    public String interpretSimilarity(double similarity) {
        if (similarity >= 0.9) {
            return "거의 동일 (0.9 이상)";
        } else if (similarity >= 0.7) {
            return "매우 유사 (0.7~0.9)";
        } else if (similarity >= 0.5) {
            return "중간 관련성 (0.5~0.7)";
        } else {
            return "관련 없음 (0.5 이하)";
        }
    }
}
