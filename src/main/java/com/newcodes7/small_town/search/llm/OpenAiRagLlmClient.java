package com.newcodes7.small_town.search.llm;

import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 기반 RagLlmClient — Responses API 사용 (crawler의 OpenaiService와 같은 API·키 공유).
 *
 * JSON 생성은 json_object 모드 + jsonInstruction 프롬프트 지시를 병용한다 (네이티브 스키마 미지정).
 * GPT-5 계열 전제: temperature 미지원이라 options.temperature()는 사용하지 않고,
 * reasoning effort는 지연 최소화를 위해 최저 단계로 고정한다 (gpt-5.1부터 minimal → none 대체).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiRagLlmClient implements RagLlmClient {

    private final ObjectMapper objectMapper;

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    private static final String OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Provider provider() {
        return Provider.OPENAI;
    }

    @Override
    public LlmJsonResult generateJson(
            String modelId, String systemPrompt, String userMessage,
            JsonOutputSpec outputSpec, LlmOptions options)
            throws IOException, InterruptedException {
        Map<String, Object> body = buildRequestBody(
                modelId, systemPrompt + "\n\n" + outputSpec.jsonInstruction(), userMessage, options);
        body.put("text", Map.of("format", Map.of("type", "json_object")));

        String responseJson = callOpenAi(objectMapper.writeValueAsString(body));

        JsonNode root = objectMapper.readTree(responseJson);
        String text = extractOutputText(root);
        if (text.isEmpty()) {
            throw new RagLlmException("OpenAI 응답이 비어 있습니다");
        }
        return new LlmJsonResult(text, extractUsage(root.path("usage")));
    }

    @Override
    public LlmTokenUsage generateStream(
            String modelId, String systemPrompt, String userMessage,
            LlmOptions options, Consumer<String> onText)
            throws IOException, InterruptedException {
        Map<String, Object> body = buildRequestBody(modelId, systemPrompt, userMessage, options);
        body.put("stream", true);

        LlmTokenUsage usage = LlmTokenUsage.empty();
        try (Stream<String> lines = callOpenAiStream(objectMapper.writeValueAsString(body))) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data: ")) continue;
                String json = line.substring(6).trim();
                if (json.isEmpty() || json.equals("[DONE]")) continue;

                JsonNode event = readEvent(json);
                if (event == null) continue;
                String type = event.path("type").asText("");
                if (type.equals("response.output_text.delta")) {
                    String delta = event.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        onText.accept(delta);
                    }
                } else if (type.equals("response.completed")) {
                    usage = extractUsage(event.path("response").path("usage"));
                } else if (type.equals("response.failed") || type.equals("error")) {
                    log.error("OpenAI 스트리밍 실패 이벤트: {}", json);
                    throw new RagLlmException("OpenAI 응답 생성이 실패했습니다");
                }
            }
        }
        return usage;
    }

    protected String callOpenAi(String requestBody) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                request(requestBody), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("OpenAI API 오류 - HTTP {}, body: {}", response.statusCode(), response.body());
            throw httpException(response.statusCode());
        }
        return response.body();
    }

    protected Stream<String> callOpenAiStream(String requestBody)
            throws IOException, InterruptedException {
        HttpResponse<Stream<String>> response = httpClient.send(
                request(requestBody), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            String body = response.body().collect(Collectors.joining("\n"));
            log.error("OpenAI API 오류 - HTTP {}, body: {}", response.statusCode(), body);
            throw httpException(response.statusCode());
        }
        return response.body();
    }

    private HttpRequest request(String requestBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_RESPONSES_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openaiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                // Gemini 호출 타임아웃 임시 5분 상향(2026-07-09)과 맞춘 값
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    private Map<String, Object> buildRequestBody(
            String modelId, String instructions, String input, LlmOptions options) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("instructions", instructions);
        body.put("input", input);
        body.put("max_output_tokens", options.maxTokens());
        body.put("reasoning", Map.of("effort", reasoningEffort(modelId)));
        return body;
    }

    private String reasoningEffort(String modelId) {
        return modelId.startsWith("gpt-5.1") ? "none" : "minimal";
    }

    /** output 배열에서 message 아이템의 output_text들을 이어붙인다 (reasoning 아이템은 제외) */
    private String extractOutputText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!item.path("type").asText("").equals("message")) continue;
            for (JsonNode part : item.path("content")) {
                if (part.path("type").asText("").equals("output_text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
        }
        return sb.toString();
    }

    private LlmTokenUsage extractUsage(JsonNode usage) {
        Integer inputTokens = usage.path("input_tokens").isNumber()
                ? usage.path("input_tokens").asInt() : null;
        Integer outputTokens = usage.path("output_tokens").isNumber()
                ? usage.path("output_tokens").asInt() : null;
        Integer totalTokens = usage.path("total_tokens").isNumber()
                ? usage.path("total_tokens").asInt() : null;
        return new LlmTokenUsage(inputTokens, outputTokens, totalTokens);
    }

    private JsonNode readEvent(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private RagLlmException httpException(int statusCode) {
        if (statusCode == 401) {
            return new RagLlmException("OpenAI API 키가 유효하지 않습니다");
        }
        if (statusCode == 429) {
            return new RagLlmException("OpenAI 요청이 제한되었습니다. 잠시 후 다시 시도해주세요");
        }
        return new RagLlmException("OpenAI API 호출 실패 (HTTP " + statusCode + ")");
    }
}
