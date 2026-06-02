package com.newcodes7.small_town.search.dto;

import java.util.List;

public record AiSummaryCacheDto(
    String summaryText,
    List<AiSummarySourceDto> sources,
    List<String> queries
) {}
