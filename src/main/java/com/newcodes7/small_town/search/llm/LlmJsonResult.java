package com.newcodes7.small_town.search.llm;

/** {@link RagLlmClient#generateJson} 결과 — json은 펜스가 섞일 수 있는 원문 텍스트 */
public record LlmJsonResult(String json, LlmTokenUsage usage) {}
