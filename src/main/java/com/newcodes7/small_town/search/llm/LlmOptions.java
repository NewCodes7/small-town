package com.newcodes7.small_town.search.llm;

/**
 * LLM 생성 옵션
 *
 * temperature가 null이면 요청에서 생략한다 — Claude Opus 4.7+/5세대처럼
 * 샘플링 파라미터를 거부(ValidationException)하는 모델용.
 */
public record LlmOptions(Double temperature, int maxTokens) {}
