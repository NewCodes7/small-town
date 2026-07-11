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

class OpenAiRagLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final JsonOutputSpec OUTPUT_SPEC =
            new JsonOutputSpec(Map.of(), "반드시 JSON 객체 하나만 출력하세요");
    private static final LlmOptions OPTIONS = new LlmOptions(0.0, 500);

    private String capturedRequestBody;

    private OpenAiRagLlmClient clientReturning(String responseJson) {
        return new OpenAiRagLlmClient(objectMapper) {
            @Override
            protected String callOpenAi(String requestBody) {
                capturedRequestBody = requestBody;
                return responseJson;
            }
        };
    }

    private OpenAiRagLlmClient clientStreaming(List<String> lines) {
        return new OpenAiRagLlmClient(objectMapper) {
            @Override
            protected Stream<String> callOpenAiStream(String requestBody) {
                capturedRequestBody = requestBody;
                return lines.stream();
            }
        };
    }

    // reasoning 아이템 + message 아이템이 섞인 Responses API 응답
    private static final String RESPONSES_JSON = """
            {
              "output": [
                {"type": "reasoning", "summary": []},
                {"type": "message", "content": [
                  {"type": "output_text", "text": "{\\"keywords\\":\\"Kafka\\"}"}
                ]}
              ],
              "usage": {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}
            }
            """;

    @Test
    @DisplayName("generateJson: json_object 모드·JSON 지시 프롬프트·reasoning minimal로 호출, message 텍스트+usage 반환")
    void generateJson_success() throws Exception {
        OpenAiRagLlmClient client = clientReturning(RESPONSES_JSON);

        LlmJsonResult result = client.generateJson(
                "gpt-5-mini", "시스템 프롬프트", "질문입니다", OUTPUT_SPEC, OPTIONS);

        assertThat(result.json()).isEqualTo("{\"keywords\":\"Kafka\"}");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(result.usage().outputTokens()).isEqualTo(5);
        assertThat(result.usage().totalTokens()).isEqualTo(15);

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.path("model").asText()).isEqualTo("gpt-5-mini");
        assertThat(request.path("instructions").asText())
                .contains("시스템 프롬프트")
                .contains("반드시 JSON 객체 하나만 출력하세요");
        assertThat(request.path("input").asText()).isEqualTo("질문입니다");
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(500);
        assertThat(request.path("reasoning").path("effort").asText()).isEqualTo("minimal");
        assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.has("temperature")).isFalse();
        assertThat(request.has("stream")).isFalse();
    }

    @Test
    @DisplayName("generateJson: gpt-5.1 계열은 reasoning effort none 사용")
    void generateJson_gpt51UsesEffortNone() throws Exception {
        OpenAiRagLlmClient client = clientReturning(RESPONSES_JSON);

        client.generateJson("gpt-5.1", "시스템", "질문", OUTPUT_SPEC, OPTIONS);

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.path("reasoning").path("effort").asText()).isEqualTo("none");
    }

    @Test
    @DisplayName("generateJson: message 텍스트가 없으면 RagLlmException")
    void generateJson_emptyOutput() {
        OpenAiRagLlmClient client = clientReturning(
                "{\"output\": [{\"type\": \"reasoning\", \"summary\": []}], \"usage\": {}}");

        assertThatThrownBy(() -> client.generateJson(
                "gpt-5-mini", "시스템", "질문", OUTPUT_SPEC, OPTIONS))
                .isInstanceOf(RagLlmException.class)
                .hasMessageContaining("비어 있습니다");
    }

    @Test
    @DisplayName("generateStream: output_text.delta만 onText로 전달하고 completed의 usage 반환")
    void generateStream_success() throws Exception {
        OpenAiRagLlmClient client = clientStreaming(List.of(
                "event: response.created",
                "data: {\"type\": \"response.created\"}",
                "",
                "data: {\"type\": \"response.output_text.delta\", \"delta\": \"안녕\"}",
                "data: {\"type\": \"response.output_text.delta\", \"delta\": \"하세요\"}",
                "data: {\"type\": \"response.output_text.done\", \"text\": \"안녕하세요\"}",
                "data: {\"type\": \"response.completed\", \"response\": "
                        + "{\"usage\": {\"input_tokens\": 20, \"output_tokens\": 8, \"total_tokens\": 28}}}",
                "data: [DONE]"));

        List<String> received = new ArrayList<>();
        LlmTokenUsage usage = client.generateStream(
                "gpt-5-mini", "시스템", "질문", new LlmOptions(0.2, 2000), received::add);

        assertThat(received).containsExactly("안녕", "하세요");
        assertThat(usage.inputTokens()).isEqualTo(20);
        assertThat(usage.outputTokens()).isEqualTo(8);
        assertThat(usage.totalTokens()).isEqualTo(28);

        JsonNode request = objectMapper.readTree(capturedRequestBody);
        assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(2000);
        assertThat(request.has("text")).isFalse();
    }

    @Test
    @DisplayName("generateStream: response.failed 이벤트 → RagLlmException")
    void generateStream_failedEvent() {
        OpenAiRagLlmClient client = clientStreaming(List.of(
                "data: {\"type\": \"response.failed\", \"response\": {\"error\": {\"message\": \"boom\"}}}"));

        assertThatThrownBy(() -> client.generateStream(
                "gpt-5-mini", "시스템", "질문", new LlmOptions(0.2, 2000), text -> {}))
                .isInstanceOf(RagLlmException.class)
                .hasMessageContaining("실패");
    }
}
