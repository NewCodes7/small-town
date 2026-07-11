package com.newcodes7.small_town.search.llm;

import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * admin RAG 테스트용 LLM 프로바이더 추상화 (Gemini / Bedrock / OpenAI)
 *
 * 공개용 AiSummaryService는 이 추상화를 사용하지 않는다 (Gemini 직접 호출 유지).
 */
public interface RagLlmClient {

    Provider provider();

    /**
     * 단발 호출로 JSON 응답을 생성한다 (전처리용).
     *
     * Gemini는 네이티브 responseSchema, Bedrock/OpenAI는 프롬프트 지시(jsonInstruction)를 사용하므로
     * 반환 JSON에 코드 펜스가 섞일 수 있다 — 파싱 전 {@link LlmJsonUtils#stripFences(String)} 적용 필요.
     */
    LlmJsonResult generateJson(
            String modelId, String systemPrompt, String userMessage,
            JsonOutputSpec outputSpec, LlmOptions options)
            throws IOException, InterruptedException;

    /**
     * 스트리밍 호출로 텍스트 조각을 onText 콜백에 전달하고 토큰 사용량을 반환한다 (답변 생성용).
     */
    LlmTokenUsage generateStream(
            String modelId, String systemPrompt, String userMessage,
            LlmOptions options, Consumer<String> onText)
            throws IOException, InterruptedException;
}
