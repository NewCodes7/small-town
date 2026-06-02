package com.newcodes7.small_town.search.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.search.dto.AiSummaryCacheDto;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.dto.AiSummaryDoneDto;
import com.newcodes7.small_town.search.dto.AiSummarySourceDto;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiSummaryService {

    private final VectorSearchService vectorSearchService;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.model:gemini-3.5-flash}")
    private String geminiModel;

    @Value("${search.recommended-queries:Spring Boot,Redis 캐시,JPA 성능,Docker 배포}")
    private String recommendedQueriesRaw;

    private static final int MAX_CHUNKS = 6;
    private static final int CHUNK_MAX_CHARS = 1000;
    private static final String CACHE_NAME = "aiSummary";
    private static final String CACHE_KEY_PREFIX = "ai-summary:";
    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String SYSTEM_PROMPT = """
            당신은 기술 블로그 내용을 요약하는 어시스턴트입니다.
            [규칙]
            - 제공된 출처 내용만 참고하여 요약하세요. 외부 지식을 추가하지 마세요.
            - 사용자 검색 의도에 맞게 핵심만 3~5문장으로 요약하세요.
            - 출처는 [출처N] 형식으로 인용하세요. 예: 스프링 부트에서는 [출처1]...
            - 마지막에 반드시 아래 형식으로 추천 검색어 3개를 포함하세요.
            [QUERIES]{"queries":["검색어1","검색어2","검색어3"]}[/QUERIES]
            """;

    private Counter successCounter;
    private Counter failureCounter;
    private Counter cachedCounter;
    private Timer latencyTimer;

    @PostConstruct
    public void init() {
        successCounter = Counter.builder("ai_summary_requests_total")
                .tag("status", "success")
                .description("AI summary successful requests")
                .register(meterRegistry);
        failureCounter = Counter.builder("ai_summary_requests_total")
                .tag("status", "failure")
                .description("AI summary failed requests")
                .register(meterRegistry);
        cachedCounter = Counter.builder("ai_summary_requests_total")
                .tag("status", "cached")
                .description("AI summary cached requests")
                .register(meterRegistry);
        latencyTimer = Timer.builder("ai_summary_latency_seconds")
                .description("AI summary end-to-end latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void streamAiSummary(String query, SseEmitter emitter) {
        if (query == null || query.trim().isEmpty()) {
            sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
            completeEmitter(emitter);
            return;
        }

        String normalizedQuery = query.toLowerCase().trim();
        String cacheKey = CACHE_KEY_PREFIX + normalizedQuery;
        long startTime = System.currentTimeMillis();

        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                AiSummaryCacheDto cached = cache.get(cacheKey, AiSummaryCacheDto.class);
                if (cached != null) {
                    replayFromCache(emitter, cached);
                    cachedCounter.increment();
                    latencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                    return;
                }
            }

            List<AiSummaryChunkDto> chunks = vectorSearchService.getTopChunksForSummary(normalizedQuery, MAX_CHUNKS);
            if (chunks.isEmpty()) {
                sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
                completeEmitter(emitter);
                failureCounter.increment();
                return;
            }

            List<AiSummarySourceDto> sources = buildSources(chunks);
            String userMessage = "검색어: " + query + "\n\n" + buildContext(chunks);
            String requestBody = buildGeminiRequestBody(SYSTEM_PROMPT, userMessage);

            StringBuilder fullText = new StringBuilder();
            boolean queriesDetected = false;

            try (Stream<String> lines = callGeminiStream(requestBody)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data: ")) continue;
                    String json = line.substring(6).trim();
                    if (json.isEmpty() || json.equals("[DONE]")) continue;

                    String text = extractTextFromGeminiResponse(json);
                    if (text.isEmpty()) continue;

                    if (!queriesDetected) {
                        String before = fullText.toString();
                        fullText.append(text);

                        int queryIdx = fullText.indexOf("[QUERIES]");
                        if (queryIdx == -1) {
                            sendTokenEvent(emitter, text);
                        } else {
                            queriesDetected = true;
                            String safeText = fullText.substring(before.length(), queryIdx);
                            if (!safeText.isEmpty()) {
                                sendTokenEvent(emitter, safeText);
                            }
                        }
                    } else {
                        fullText.append(text);
                    }
                }
            } catch (HttpTimeoutException e) {
                log.warn("Gemini API 타임아웃 - 쿼리: {}", query);
                sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
                completeEmitter(emitter);
                failureCounter.increment();
                latencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                return;
            } catch (IOException | InterruptedException e) {
                log.error("Gemini API 호출 실패: {}", e.getMessage());
                sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
                completeEmitter(emitter);
                failureCounter.increment();
                latencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                return;
            }

            String fullTextStr = fullText.toString();
            String cleanText = fullTextStr.trim();
            List<String> queries = List.of();

            int qStart = fullTextStr.indexOf("[QUERIES]");
            int qEnd = fullTextStr.indexOf("[/QUERIES]");
            if (qStart != -1 && qEnd != -1 && qEnd > qStart) {
                cleanText = fullTextStr.substring(0, qStart).trim();
                String queriesJson = fullTextStr.substring(qStart + 9, qEnd).trim();
                try {
                    JsonNode queriesNode = objectMapper.readTree(queriesJson).path("queries");
                    if (queriesNode.isArray()) {
                        queries = objectMapper.convertValue(queriesNode,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    }
                } catch (Exception e) {
                    log.warn("Gemini queries 파싱 실패: {}", queriesJson);
                }
            }

            sendDoneEvent(emitter, new AiSummaryDoneDto(sources, queries));
            completeEmitter(emitter);

            if (cache != null && !cleanText.isEmpty()) {
                cache.put(cacheKey, new AiSummaryCacheDto(cleanText, sources, queries));
            }

            successCounter.increment();
            latencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            log.error("AI 요약 처리 중 예외 발생: {}", e.getMessage(), e);
            sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
            completeEmitter(emitter);
            failureCounter.increment();
        }
    }

    public List<String> getRecommendedQueries() {
        return Arrays.stream(recommendedQueriesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    protected Stream<String> callGeminiStream(String requestBody) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        String url = GEMINI_BASE_URL + geminiModel
                + ":streamGenerateContent?key=" + geminiApiKey + "&alt=sse";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IOException("Gemini API 오류: " + response.statusCode());
        }
        return response.body();
    }

    private void replayFromCache(SseEmitter emitter, AiSummaryCacheDto cached) {
        String text = cached.summaryText();
        int chunkSize = 50;
        for (int i = 0; i < text.length(); i += chunkSize) {
            sendTokenEvent(emitter, text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        sendDoneEvent(emitter, new AiSummaryDoneDto(cached.sources(), cached.queries()));
        completeEmitter(emitter);
    }

    private List<AiSummarySourceDto> buildSources(List<AiSummaryChunkDto> chunks) {
        return chunks.stream()
                .collect(Collectors.toMap(
                        AiSummaryChunkDto::articleId,
                        c -> new AiSummarySourceDto(c.articleId(), c.articleTitle(), c.articleUrl()),
                        (a, b) -> a,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    private String buildContext(List<AiSummaryChunkDto> chunks) {
        Map<Long, List<AiSummaryChunkDto>> byArticle = chunks.stream()
                .collect(Collectors.groupingBy(AiSummaryChunkDto::articleId, LinkedHashMap::new, Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        int sourceNum = 1;
        for (Map.Entry<Long, List<AiSummaryChunkDto>> entry : byArticle.entrySet()) {
            List<AiSummaryChunkDto> articleChunks = entry.getValue();
            sb.append("[출처").append(sourceNum++).append("] ")
              .append(articleChunks.get(0).articleTitle()).append("\n");
            for (AiSummaryChunkDto chunk : articleChunks) {
                String content = chunk.content();
                if (content.length() > CHUNK_MAX_CHARS) {
                    content = content.substring(0, CHUNK_MAX_CHARS);
                }
                sb.append(content).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildGeminiRequestBody(String systemPrompt, String userMessage) throws Exception {
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userMessage))
                )),
                "generationConfig", Map.of(
                        "temperature", 0.7,
                        "maxOutputTokens", 1024
                )
        );
        return objectMapper.writeValueAsString(body);
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

    private void sendTokenEvent(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("token").data(text));
        } catch (Exception ignored) {}
    }

    private void sendDoneEvent(SseEmitter emitter, AiSummaryDoneDto done) {
        try {
            emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
        } catch (Exception ignored) {}
    }

    private void sendErrorEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data(objectMapper.writeValueAsString(Map.of("message", message))));
        } catch (Exception ignored) {}
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {}
    }
}
