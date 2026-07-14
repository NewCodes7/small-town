package com.newcodes7.small_town.search.dto;

import java.util.List;

public record RagAnswerCacheDto(
        String answerText,
        List<AiSummarySourceDto> sources,
        List<String> relatedQueries,
        String model) {}
