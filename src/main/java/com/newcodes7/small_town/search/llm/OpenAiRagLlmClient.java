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
 * OpenAI 호환 Responses API용 RagLlmClient — GPT뿐 아니라 Gemma(Google)·Grok(xAI)도 처리한다.
 *
 * 기본 설정은 AWS Bedrock의 OpenAI 호환 엔드포인트(bedrock-mantle openai/v1)를 경유한다.
 * GPT-5.5/5.4·Gemma 4·Grok 4.3은 미국 리전 bedrock-mantle에만 있으므로
 * bedrock.region(서울)과 무관하게 URL에 리전을 박는다.
 * rag.openai.base-url을 https://api.openai.com/v1 으로 바꾸면 OpenAI 직접 호출로 되돌아간다.
 *
 * temperature는 GPT-5 계열이 미지원이라 options.temperature()를 사용하지 않는다.
 * reasoning effort는 GPT 계열만 최저 단계로 고정해 전송한다 (gpt-5.1부터 minimal → none 대체) —
 * Grok 4.3은 minimal을 거부(none/low/medium/high만 허용, 기본 low)하고 Gemma는 허용값이 미문서화라 생략.
 * JSON 생성은 GPT 계열만 json_object 모드 + jsonInstruction 병용, 그 외 모델은 프롬프트 지시만 사용한다
 * — 호출측은 stripFences 후 파싱해야 한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiRagLlmClient implements RagLlmClient {

    private final ObjectMapper objectMapper;

    // Bedrock 경유 시 Bedrock long-term API key, 직접 호출 시 OpenAI API key
    @Value("${rag.openai.api-key:${openai.api-key:}}")
    private String apiKey;

    @Value("${rag.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

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
        // json_object 모드는 GPT 계열만 — Gemma/Grok은 jsonInstruction 프롬프트 지시에만 의존
        if (isGptModel(modelId)) {
            body.put("text", Map.of("format", Map.of("type", "json_object")));
        }

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
                .uri(URI.create(baseUrl + "/responses"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
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
        // Grok 4.3은 minimal 거부, Gemma는 허용값 미문서화 → reasoning은 GPT 계열만 전송
        if (isGptModel(modelId)) {
            body.put("reasoning", Map.of("effort", reasoningEffort(modelId)));
        }
        return body;
    }

    private boolean isGptModel(String modelId) {
        return bareModelId(modelId).startsWith("gpt-");
    }

    private String reasoningEffort(String modelId) {
        return bareModelId(modelId).matches("gpt-5\\.\\d.*") ? "none" : "minimal";
    }

    /** Bedrock 모델 ID의 openai. 벤더 프리픽스를 제거한다 (예: openai.gpt-5.5 → gpt-5.5) */
    private String bareModelId(String modelId) {
        return modelId.startsWith("openai.") ? modelId.substring("openai.".length()) : modelId;
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
        if (statusCode == 401 || statusCode == 403) {
            return new RagLlmException("OpenAI API 키가 유효하지 않습니다 (rag.openai.api-key 확인)");
        }
        if (statusCode == 429) {
            return new RagLlmException("OpenAI 요청이 제한되었습니다. 잠시 후 다시 시도해주세요");
        }
        return new RagLlmException("OpenAI API 호출 실패 (HTTP " + statusCode + ")");
    }
}
