package com.newcodes7.small_town.article.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * OpenAI Embedding API 응답 DTO
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAIEmbeddingResponse {

    /**
     * 응답 객체 타입 ("list")
     */
    private String object;

    /**
     * 임베딩 데이터 목록
     */
    private List<EmbeddingData> data;

    /**
     * 사용된 모델명
     */
    private String model;

    /**
     * 토큰 사용량 정보
     */
    private Usage usage;

    /**
     * 임베딩 데이터 내부 클래스
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingData {
        /**
         * 객체 타입 ("embedding")
         */
        private String object;

        /**
         * 인덱스 (배치 처리 시 순서)
         */
        private int index;

        /**
         * 임베딩 벡터 (1536차원)
         */
        private float[] embedding;
    }

    /**
     * 토큰 사용량 내부 클래스
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        /**
         * 프롬프트 토큰 수
         */
        @JsonProperty("prompt_tokens")
        private int promptTokens;

        /**
         * 총 토큰 수
         */
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
