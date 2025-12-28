package com.newcodes7.small_town.crawler.dto;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * Article 요약 생성 응답 DTO
 * OpenAI API를 통해 생성된 3줄 요약 + 핵심 키워드를 담는다
 */
@Data
@Builder
public class ArticleSummaryResponse {

    /**
     * 3줄 요약 (각 50자 이내)
     */
    private List<String> threeLineSummary;

    /**
     * 핵심 키워드 (쉼표로 구분)
     */
    private String keywords;

    /**
     * 3줄 요약과 키워드를 하나의 텍스트로 결합
     * 임베딩 생성 시 사용
     */
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();

        // 3줄 요약
        if (threeLineSummary != null && !threeLineSummary.isEmpty()) {
            for (String line : threeLineSummary) {
                sb.append(line).append(". ");
            }
        }

        // 핵심 키워드 (여러 번 반복하여 가중치 부여)
        if (keywords != null && !keywords.trim().isEmpty()) {
            for (int i = 0; i < 3; i++) {
                sb.append(keywords).append(". ");
            }
        }

        return sb.toString().trim();
    }

    /**
     * 사용자에게 보여줄 요약 텍스트 생성
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();

        if (threeLineSummary != null && !threeLineSummary.isEmpty()) {
            for (int i = 0; i < threeLineSummary.size(); i++) {
                sb.append("• ").append(threeLineSummary.get(i));
                if (i < threeLineSummary.size() - 1) {
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }
}
