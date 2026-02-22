package com.newcodes7.small_town.embedding.service;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;

/**
 * 임베딩 서비스 인터페이스
 * 다양한 임베딩 모델을 추상화
 */
public interface EmbeddingService {

    /**
     * 텍스트를 임베딩 벡터로 변환
     *
     * @param text 임베딩할 텍스트
     * @param wholeDocument 전체 문서 (컨텍스트용, nullable)
     * @return 임베딩 결과
     */
    ModelEmbeddingResult generateEmbedding(String text, String wholeDocument);

    /**
     * 모델 이름 반환
     */
    String getModelName();

    /**
     * 임베딩 벡터 차원 수 반환
     */
    int getDimension();

    /**
     * 토큰 수에 따른 비용 계산
     *
     * @param tokenCount 토큰 수
     * @return 비용 (USD)
     */
    double calculateCost(int tokenCount);
}
