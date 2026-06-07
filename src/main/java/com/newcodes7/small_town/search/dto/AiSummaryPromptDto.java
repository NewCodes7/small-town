package com.newcodes7.small_town.search.dto;

public record AiSummaryPromptDto(
        String systemPrompt,
        String userMessage,
        int articleCount,
        int chunkCount
) {}
