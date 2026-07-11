package com.newcodes7.small_town.search.llm;

/** LLM 토큰 사용량 (프로바이더가 미제공 시 null) */
public record LlmTokenUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

    public static LlmTokenUsage empty() {
        return new LlmTokenUsage(null, null, null);
    }
}
