package com.newcodes7.small_town.article.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * OpenAI Embedding API 요청 DTO
 */
@Data
@Builder
public class OpenAIEmbeddingRequest {

    /**
     * 사용할 모델 (text-embedding-3-small)
     */
    private String model;

    /**
     * 임베딩할 텍스트
     */
    private String input;

    /**
     * 인코딩 형식 (float 또는 base64)
     */
    @JsonProperty("encoding_format")
    private String encodingFormat;
}
