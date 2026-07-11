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
import java.util.List;
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
 * Gemini 기반 RagLlmClient — RagAnswerService/RagQueryPreprocessService의 기존 HTTP 호출 코드 이동.
 * JSON 생성은 네이티브 structured output(responseSchema)을 사용한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GeminiRagLlmClient implements RagLlmClient {

    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public Provider provider() {
        return Provider.GEMINI;
    }

    @Override
    public LlmJsonResult generateJson(
            String modelId, String systemPrompt, String userMessage,
            JsonOutputSpec outputSpec, LlmOptions options)
            throws IOException, InterruptedException {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", options.temperature());
        generationConfig.put("maxOutputTokens", options.maxTokens());
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", outputSpec.geminiResponseSchema());
        generationConfig.put("thinkingConfig", Map.of("thinkingLevel", "minimal"));

        String responseJson = callGemini(modelId, buildRequestBody(systemPrompt, userMessage, generationConfig));

        JsonNode root = objectMapper.readTree(responseJson);
        String structuredText = root.path("candidates").path(0)
                .path("content").path("parts").path(0).path("text").asText("");
        if (structuredText.isEmpty()) {
            throw new RagLlmException("Gemini 응답에 structured output이 없습니다");
        }
        return new LlmJsonResult(structuredText, extractUsage(root));
    }

    @Override
    public LlmTokenUsage generateStream(
            String modelId, String systemPrompt, String userMessage,
            LlmOptions options, Consumer<String> onText)
            throws IOException, InterruptedException {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", options.temperature());
        generationConfig.put("maxOutputTokens", options.maxTokens());
        generationConfig.put("thinkingConfig", Map.of("thinkingLevel", "minimal"));

        String requestBody = buildRequestBody(systemPrompt, userMessage, generationConfig);

        int[] tokenUsage = {0, 0, 0}; // [input, output, total]
        try (Stream<String> lines = callGeminiStream(modelId, requestBody)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.startsWith("data: ")) continue;
                String json = line.substring(6).trim();
                if (json.isEmpty() || json.equals("[DONE]")) continue;

                extractStreamTokenUsage(json, tokenUsage);

                String text = extractTextFromGeminiResponse(json);
                if (text.isEmpty()) continue;
                onText.accept(text);
            }
        }
        return new LlmTokenUsage(
                tokenUsage[0] > 0 ? tokenUsage[0] : null,
                tokenUsage[1] > 0 ? tokenUsage[1] : null,
                tokenUsage[2] > 0 ? tokenUsage[2] : null);
    }

    protected String callGemini(String modelId, String requestBody)
            throws IOException, InterruptedException {
        String url = GEMINI_BASE_URL + modelId + ":generateContent?key=" + geminiApiKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                // TODO: 임시 조치 (2026-07-09) — Gemini 타임아웃 원인 파악 전까지 5분으로 상향. 원인 규명 후 재조정 필요
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Gemini API 오류 - HTTP {}, body: {}", response.statusCode(), response.body());
            throw new RagLlmException("Gemini API 호출 실패 (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    protected Stream<String> callGeminiStream(String modelId, String requestBody)
            throws IOException, InterruptedException {
        String url = GEMINI_BASE_URL + modelId
                + ":streamGenerateContent?key=" + geminiApiKey + "&alt=sse";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                // TODO: 임시 조치 (2026-07-09) — Gemini 타임아웃 원인 파악 전까지 5분으로 상향. 원인 규명 후 재조정 필요
                .timeout(Duration.ofMinutes(5))
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            String body = response.body().collect(Collectors.joining("\n"));
            log.error("Gemini API 오류 - HTTP {}, body: {}", response.statusCode(), body);
            throw new RagLlmException("Gemini API 호출 실패 (HTTP " + response.statusCode() + ")");
        }
        return response.body();
    }

    private String buildRequestBody(
            String systemPrompt, String userMessage, Map<String, Object> generationConfig) {
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userMessage))
                )),
                "generationConfig", generationConfig
        );
        return objectMapper.writeValueAsString(body);
    }

    private LlmTokenUsage extractUsage(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        Integer inputTokens = usage.path("promptTokenCount").isNumber()
                ? usage.path("promptTokenCount").asInt() : null;
        Integer outputTokens = usage.path("candidatesTokenCount").isNumber()
                ? usage.path("candidatesTokenCount").asInt() : null;
        Integer totalTokens = usage.path("totalTokenCount").isNumber()
                ? usage.path("totalTokenCount").asInt() : null;
        return new LlmTokenUsage(inputTokens, outputTokens, totalTokens);
    }

    private void extractStreamTokenUsage(String json, int[] tokenUsage) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode usage = root.path("usageMetadata");
            if (!usage.isMissingNode()) {
                tokenUsage[0] = usage.path("promptTokenCount").asInt(tokenUsage[0]);
                tokenUsage[1] = usage.path("candidatesTokenCount").asInt(tokenUsage[1]);
                tokenUsage[2] = usage.path("totalTokenCount").asInt(tokenUsage[2]);
            }
        } catch (Exception ignored) {}
    }

    private String extractTextFromGeminiResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode candidates = root.path("candidates");
            if (candidates.isEmpty()) return "";
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isEmpty()) return "";
            return parts.get(0).path("text").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
