package com.newcodes7.small_town.search.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
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
    private final ArticleSearchService articleSearchService;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.model:gemini-3.5-flash}")
    private String geminiModel;

    @Value("${search.recommended-queries:Spring Boot,Redis 캐시,JPA 성능,Docker 배포}")
    private String recommendedQueriesRaw;

    private static final int TOP_ARTICLES = 3;
    private static final int CHUNK_MAX_CHARS = 1000;
    private static final String CACHE_NAME = "aiSummary";
    private static final String CACHE_KEY_PREFIX = "ai-summary:";
    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String SYSTEM_PROMPT = """
            당신은 기업 기술 블로그 큐레이터입니다.
            사용자가 기술/주제를 검색하면, 각 기업이 그 기술을 어떻게 활용했는지 소개해주세요.

            [규칙]
            - 인사말, 도입 문장, 검색어 설명 없이 첫 번째 기업 소개부터 바로 시작하세요.
            - 기업별 내용은 마크다운 불렛 포인트(- 으로 시작)로 2~3개 항목을 작성하세요.
            - 핵심 키워드(기술명, 수치, 성과 등)는 **볼드체**로 강조하세요.
            - 도입 이유, 해결한 문제, 성과에 집중하세요. 출처에 없는 내용은 추가하지 마세요.
            - 각 기업 소개 끝에 [출처N] 형식으로 출처를 표기하세요. [출처1][출처2]처럼 개별 표기하세요.
            - 각 출처는 아티클의 첫 번째 청크(서론)와 가장 관련성 높은 청크를 포함합니다.
              서론과 관련성 높은 부분을 참고해 글의 전체 맥락과 핵심 내용을 파악하여 요약에 반영하세요.
            - 마지막에 반드시 추천 검색어 3개를 아래 형식으로 포함하세요.
            [QUERIES]{"queries":["검색어1","검색어2","검색어3"]}[/QUERIES]
            """;

    private Counter successCounter;
    private Counter failureCounter;
    private Counter cachedCounter;
    private Timer latencyTimer;
    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
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

            List<Long> topArticleIds = articleSearchService.getTopArticleIdsByHybrid(normalizedQuery, TOP_ARTICLES);
            List<AiSummaryChunkDto> chunks = vectorSearchService.getChunksForArticlesByIds(normalizedQuery, topArticleIds);
            if (chunks.isEmpty()) {
                sendHideEvent(emitter);
                completeEmitter(emitter);
                failureCounter.increment();
                return;
            }

            List<AiSummaryChunkDto> orderedChunks = chunks.stream()
                    .sorted(Comparator.comparingInt(c -> topArticleIds.indexOf(c.articleId())))
                    .collect(Collectors.toList());
            List<AiSummarySourceDto> sources = buildSources(orderedChunks);
            String userMessage = "검색어: " + query + "\n\n" + buildContext(orderedChunks);
            String requestBody = buildGeminiRequestBody(SYSTEM_PROMPT, userMessage);

            StringBuilder fullText = new StringBuilder();
            StringBuilder pendingBuf = new StringBuilder();
            boolean queriesDetected = false;
            final int HOLD_BACK = "[QUERIES]".length() - 1;

            try (Stream<String> lines = callGeminiStream(requestBody)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data: ")) continue;
                    String json = line.substring(6).trim();
                    if (json.isEmpty() || json.equals("[DONE]")) continue;

                    String text = extractTextFromGeminiResponse(json);
                    if (text.isEmpty()) continue;

                    fullText.append(text);

                    if (!queriesDetected) {
                        pendingBuf.append(text);
                        String pending = pendingBuf.toString();
                        int queryIdx = pending.indexOf("[QUERIES]");
                        if (queryIdx != -1) {
                            queriesDetected = true;
                            if (queryIdx > 0) sendTokenEvent(emitter, pending.substring(0, queryIdx));
                            pendingBuf.setLength(0);
                        } else {
                            // [QUERIES] 태그가 토큰 경계에 걸쳐 오는 경우 대비:
                            // 끝 8자는 홀드하여 다음 토큰과 합쳐서 재검사
                            int safeLen = pending.length() - HOLD_BACK;
                            if (safeLen > 0) {
                                sendTokenEvent(emitter, pending.substring(0, safeLen));
                                pendingBuf.delete(0, safeLen);
                            }
                        }
                    }
                }
                if (!queriesDetected && pendingBuf.length() > 0) {
                    sendTokenEvent(emitter, pendingBuf.toString());
                }
            } catch (HttpTimeoutException e) {
                log.warn("Gemini API 타임아웃 - 쿼리: {}", query);
                sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
                completeEmitter(emitter);
                failureCounter.increment();
                latencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Gemini API 호출 중 인터럽트: {}", e.getMessage());
                sendErrorEvent(emitter, "요약을 불러올 수 없습니다");
                completeEmitter(emitter);
                failureCounter.increment();
                latencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                return;
            } catch (IOException e) {
                log.error("Gemini API 호출 실패: {}", e.getMessage(), e);
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
        String url = GEMINI_BASE_URL + geminiModel
                + ":streamGenerateContent?key=" + geminiApiKey + "&alt=sse";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            String body = response.body().collect(Collectors.joining("\n"));
            log.error("Gemini API 오류 - HTTP {}, body: {}", response.statusCode(), body);
            throw new IOException("Gemini API HTTP " + response.statusCode() + ": " + body);
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
                        c -> new AiSummarySourceDto(c.articleId(), c.articleTitle(), c.articleUrl(), c.logoUrl(), c.corporationName(), c.thumbnailImage()),
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
            String title = articleChunks.get(0).articleTitle();
            String corpName = articleChunks.get(0).corporationName();
            sb.append("[출처").append(sourceNum++).append("] ")
              .append("회사: ").append(corpName != null ? corpName : "Unknown")
              .append(" | 글 제목: ").append(title).append("\n");
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
                        "maxOutputTokens", 1500,
                        "thinkingConfig", Map.of("thinkingLevel", "minimal")
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
            emitter.send(SseEmitter.event().name("token").data(objectMapper.writeValueAsString(text)));
        } catch (Exception ignored) {}
    }

    private void sendDoneEvent(SseEmitter emitter, AiSummaryDoneDto done) {
        try {
            emitter.send(SseEmitter.event().name("done").data(objectMapper.writeValueAsString(done)));
        } catch (Exception ignored) {}
    }

    private void sendHideEvent(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("hide").data("{}"));
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
