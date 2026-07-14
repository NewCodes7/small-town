package com.newcodes7.small_town.search.dto;

import java.util.List;

/**
 * admin RAG done SSE 이벤트 페이로드 — 공개용 AiSummaryDoneDto와 달리 사용 모델 라벨을 포함한다.
 */
public record RagDoneDto(
        List<AiSummarySourceDto> sources,
        List<String> relatedQueries,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens,
        String model) {}
