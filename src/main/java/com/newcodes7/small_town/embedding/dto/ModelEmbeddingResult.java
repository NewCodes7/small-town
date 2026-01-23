package com.newcodes7.small_town.embedding.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 임베딩 생성 결과 DTO
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelEmbeddingResult {

    private String modelName;
    private int dimension;
    private long responseTimeMs;
    private int tokenUsage;
    private double estimatedCost;
    private boolean promptCachingUsed;
    private boolean success;
    private String errorMessage;
    private float[] embedding;

    /**
     * 성공 여부 확인
     */
    public boolean isSuccess() {
        return success && embedding != null;
    }
}
