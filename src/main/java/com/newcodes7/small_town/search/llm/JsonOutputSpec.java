package com.newcodes7.small_town.search.llm;

import java.util.Map;

/**
 * JSON 출력 강제 방식 명세
 *
 * @param geminiResponseSchema Gemini 네이티브 structured output용 responseSchema
 * @param jsonInstruction      네이티브 스키마가 없는 프로바이더(Bedrock, OpenAI)용 프롬프트 지시문
 */
public record JsonOutputSpec(Map<String, Object> geminiResponseSchema, String jsonInstruction) {}
