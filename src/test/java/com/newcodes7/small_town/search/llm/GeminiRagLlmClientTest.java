package com.newcodes7.small_town.search.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GeminiRagLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final JsonOutputSpec OUTPUT_SPEC =
            new JsonOutputSpec(Map.of(), "반드시 JSON 객체 하나만 출력하세요");
    private static final LlmOptions OPTIONS = new LlmOptions(0.0, 500);

    private String capturedRequestBody;

    private GeminiRagLlmClient clientReturning(String responseJson) {
        return new GeminiRagLlmClient(objectMapper) {
            @Override
            protected String callGemini(String modelId, String requestBody) {
                capturedRequestBody = requestBody;
                return responseJson;
            }
        };
    }

    private GeminiRagLlmClient clientStreaming(List<String> lines) {
        return new GeminiRagLlmClient(objectMapper) {
            @Override
            protected Stream<String> callGeminiStream(String modelId, String requestBody) {
                capturedRequestBody = requestBody;
                return lines.stream();
            }
        };
    }

    private static final String RESPONSE_JSON = """
            {
              "candidates": [
                {"content": {"parts": [{"text": "{\\"keywords\\":\\"Kafka\\"}"}]}}
              ],
              "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 6, "totalTokenCount": 18}
            }
            """;

    @Test
    @DisplayName("generateJson: structured output 요청 바디 생성 + 응답 파싱 (temperature 포함)")
    void generateJson_success() throws Exception {
        GeminiRagLlmClient client = clientReturning(RESPONSE_JSON);

        LlmJsonResult result = client.generateJson(
                "gemini-2.5-flash", "시스템 프롬프트", "질문입니다", OUTPUT_SPEC, OPTIONS);

        assertThat(result.json()).isEqualTo("{\"keywords\":\"Kafka\"}");
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.usage().outputTokens()).isEqualTo(6);
        assertThat(result.usage().totalTokens()).isEqualTo(18);

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.path("system_instruction").path("parts").get(0).path("text").asText())
                .isEqualTo("시스템 프롬프트");
        assertThat(request.path("contents").get(0).path("parts").get(0).path("text").asText())
                .isEqualTo("질문입니다");
        assertThat(request.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(500);
        assertThat(request.path("generationConfig").path("responseMimeType").asText())
                .isEqualTo("application/json");
        assertThat(request.path("generationConfig").has("responseSchema")).isTrue();
        assertThat(request.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText())
                .isEqualTo("minimal");
        assertThat(request.path("generationConfig").has("temperature")).isTrue();
    }

    @Test
    @DisplayName("generateJson: temperature가 null이면 요청 바디에서 생략")
    void generateJson_nullTemperature_omitsTemperatureField() throws Exception {
        GeminiRagLlmClient client = clientReturning(RESPONSE_JSON);

        client.generateJson("gemini-2.5-flash", "시스템", "질문", OUTPUT_SPEC, new LlmOptions(null, 500));

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.path("generationConfig").has("temperature")).isFalse();
    }

    @Test
    @DisplayName("generateJson: structured output 텍스트가 비어있으면 RagLlmException")
    void generateJson_emptyStructuredOutput_throwsRagLlmException() {
        GeminiRagLlmClient client = clientReturning(
                "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"\"}]}}]}");

        assertThatThrownBy(() -> client.generateJson(
                "gemini-2.5-flash", "시스템", "질문", OUTPUT_SPEC, OPTIONS))
                .isInstanceOf(RagLlmException.class)
                .hasMessageContaining("structured output이 없습니다");
    }

    @Test
    @DisplayName("generateStream: 텍스트 청크 순서대로 전달 + 중간에 등장한 usageMetadata 반영")
    void generateStream_success_forwardsTextAndReturnsUsage() throws Exception {
        GeminiRagLlmClient client = clientStreaming(List.of(
                "event: start",
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"안녕\"}]}}]}",
                "",
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"하세요\"}]}}], "
                        + "\"usageMetadata\": {\"promptTokenCount\": 20, \"candidatesTokenCount\": 8, \"totalTokenCount\": 28}}",
                "data: [DONE]"));

        List<String> received = new ArrayList<>();
        LlmTokenUsage usage = client.generateStream(
                "gemini-2.5-flash", "시스템", "질문", new LlmOptions(0.2, 2000), received::add);

        assertThat(received).containsExactly("안녕", "하세요");
        assertThat(usage.inputTokens()).isEqualTo(20);
        assertThat(usage.outputTokens()).isEqualTo(8);
        assertThat(usage.totalTokens()).isEqualTo(28);
    }

    @Test
    @DisplayName("generateStream: candidates/parts가 비어있으면 onText 호출 안 함")
    void generateStream_emptyCandidatesOrParts_noOnTextCall() throws Exception {
        GeminiRagLlmClient client = clientStreaming(List.of(
                "data: {\"candidates\": []}",
                "data: {\"candidates\": [{\"content\": {\"parts\": []}}]}",
                "data: [DONE]"));

        List<String> received = new ArrayList<>();
        client.generateStream("gemini-2.5-flash", "시스템", "질문", new LlmOptions(0.2, 2000), received::add);

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("generateStream: 깨진 JSON 라인은 무시하고 다음 라인 계속 처리")
    void generateStream_malformedJsonLine_ignoredSilently() throws Exception {
        GeminiRagLlmClient client = clientStreaming(List.of(
                "data: {broken json",
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"정상\"}]}}]}",
                "data: [DONE]"));

        List<String> received = new ArrayList<>();
        client.generateStream("gemini-2.5-flash", "시스템", "질문", new LlmOptions(0.2, 2000), received::add);

        assertThat(received).containsExactly("정상");
    }

    @Test
    @DisplayName("generateStream: usageMetadata가 전혀 없으면 반환 usage 필드는 0이 아닌 null")
    void generateStream_tokenUsageNeverObserved_resultsInNullFields() throws Exception {
        GeminiRagLlmClient client = clientStreaming(List.of(
                "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"텍스트\"}]}}]}",
                "data: [DONE]"));

        LlmTokenUsage usage = client.generateStream(
                "gemini-2.5-flash", "시스템", "질문", new LlmOptions(0.2, 2000), text -> {});

        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
    }

    @Test
    @DisplayName("generateStream: 요청 바디에는 responseMimeType/responseSchema가 없음 (generateJson 전용)")
    void generateStream_requestBody_omitsResponseMimeTypeAndSchema() throws Exception {
        GeminiRagLlmClient client = clientStreaming(List.of("data: [DONE]"));

        client.generateStream("gemini-2.5-flash", "시스템", "질문", new LlmOptions(0.2, 2000), text -> {});

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.path("generationConfig").has("responseMimeType")).isFalse();
        assertThat(request.path("generationConfig").has("responseSchema")).isFalse();
        assertThat(request.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText())
                .isEqualTo("minimal");
        assertThat(request.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(2000);
    }
}
