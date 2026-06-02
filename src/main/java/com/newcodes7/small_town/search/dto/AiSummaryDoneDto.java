package com.newcodes7.small_town.search.dto;

import java.util.List;

public record AiSummaryDoneDto(
    List<AiSummarySourceDto> sources,
    List<String> queries
) {}
