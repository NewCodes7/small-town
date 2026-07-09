package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.dto.AiSummaryDoneDto;
import com.newcodes7.small_town.search.dto.AiSummarySourceDto;
import com.newcodes7.small_town.search.entity.RagQueryLog;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 질의응답 서비스 (관리자 테스트 페이지용)
 *
 * AiSummaryService의 SSE 스트리밍 구조를 따르되 질의응답형으로 확장:
 * 전처리(이중 쿼리 분해) → 기업 프리필터 하이브리드 retrieval → Gemini SSE 생성.
 * 캐시 없이 매 요청 실행하고 RagQueryLog에 기록한다.
 *
 * SSE 이벤트: preprocess → sources → token* → done / notfound / error
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagAnswerService {

    private final RagQueryPreprocessService preprocessService;
    private final ArticleSearchService articleSearchService;
    private final VectorSearchService vectorSearchService;
    private final RagQueryLogRepository ragQueryLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.summary.model:gemini-3.5-flash}")
    private String geminiModel;

    private static final int CHUNK_MAX_CHARS = 1000;
    private static final int QUESTION_MAX_CHARS = 500;
    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String NO_CORP_MESSAGE = "해당 기업의 글이 없습니다";
    private static final String NO_RESULT_MESSAGE = "관련 글을 찾지 못했습니다";

    private static final String RAG_SYSTEM_PROMPT = """
            당신은 기업 기술 블로그 기반 기술 활용 사례 Q&A 어시스턴트입니다.
            사용자의 질문에 대해, 주어진 컨텍스트([출처N] 블록들)만을 근거로 답변하세요.

            [내용 규칙]
            - 활용 사례 위주로 답하세요: 도입 배경, 적용 과정, 시행착오, 성과·수치에 집중하세요.
            - 컨텍스트가 개념 설명뿐이고 실제 활용 사례가 없으면, 사례가 있는 것처럼 포장하지 마세요.
            - 컨텍스트에 질문에 대한 근거가 없으면 "관련 활용 사례를 찾지 못했습니다"라고만 답하세요.
              컨텍스트에 없는 내용을 추측하거나 일반 지식으로 보충하지 마세요.
            - 각 출처는 아티클의 서론과 질문 관련성이 높은 부분을 포함합니다.
              글의 전체 맥락을 파악해 답변에 반영하세요.

            [형식 규칙]
            - 마크다운 자유 형식으로 답하되, 간결하게 핵심만 정리하세요.
            - 근거로 사용한 문단마다 끝에 [출처N]을 표기하세요.
            - 핵심 키워드(기술명, 수치, 성과)만 **볼드체**로 강조하세요.
            - 인사말, 질문 반복, 결론 없는 채움 문장은 쓰지 마세요.
            """;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public void streamAnswer(
            String question, int topArticles, int chunksPerArticle, double threshold, SseEmitter emitter) {
        if (question == null || question.trim().isEmpty()) {
            sendErrorEvent(emitter, "질문을 입력해주세요");
            completeEmitter(emitter);
            return;
        }
        String trimmedQuestion = question.trim();

        RagPreprocessResult pre = null;
        try {
            pre = preprocessService.preprocess(trimmedQuestion);
            sendPreprocessEvent(emitter, pre);

            if (pre.isCorporationTargetedButNotMatched()) {
                sendNotFoundEvent(emitter, NO_CORP_MESSAGE);
                completeEmitter(emitter);
                saveLog(trimmedQuestion, pre, 0, RagQueryLog.Outcome.NO_CORP, null, null, null);
                return;
            }

            ArticleSearchService.HybridTopArticles topArticlesResult =
                    articleSearchService.getTopArticleIdsForRag(
                            pre.keywords(), pre.vectorQuery(), pre.matchedCorporationIds(),
                            topArticles, threshold);
            List<Long> topArticleIds = topArticlesResult.articleIds();
            List<AiSummaryChunkDto> chunks = topArticleIds.isEmpty()
                    ? List.of()
                    : vectorSearchService.getChunksForRag(
                            pre.vectorQuery(), topArticleIds, topArticlesResult.queryEmbedding(), chunksPerArticle);
            if (chunks.isEmpty()) {
                sendNotFoundEvent(emitter, NO_RESULT_MESSAGE);
                completeEmitter(emitter);
                saveLog(trimmedQuestion, pre, 0, RagQueryLog.Outcome.NO_RESULT, null, null, null);
                return;
            }

            List<AiSummaryChunkDto> orderedChunks = chunks.stream()
                    .sorted(Comparator.comparingInt(c -> topArticleIds.indexOf(c.articleId())))
                    .collect(Collectors.toList());
            List<AiSummarySourceDto> sources = buildSources(orderedChunks);
            sendSourcesEvent(emitter, sources);

            String userMessage = "질문: " + trimmedQuestion + "\n\n" + buildContext(orderedChunks);
            String requestBody = buildGeminiRequestBody(RAG_SYSTEM_PROMPT, userMessage);

            int[] tokenUsage = {0, 0, 0}; // [input, output, total]
            try (Stream<String> lines = callGeminiStream(requestBody)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (!line.startsWith("data: ")) continue;
                    String json = line.substring(6).trim();
                    if (json.isEmpty() || json.equals("[DONE]")) continue;

                    extractTokenUsage(json, tokenUsage);

                    String text = extractTextFromGeminiResponse(json);
                    if (text.isEmpty()) continue;
                    sendTokenEvent(emitter, text);
                }
            }

            Integer inTokens = sumTokens(pre.inputTokens(), tokenUsage[0] > 0 ? tokenUsage[0] : null);
            Integer outTokens = sumTokens(pre.outputTokens(), tokenUsage[1] > 0 ? tokenUsage[1] : null);
            Integer totalTokens = sumTokens(pre.totalTokens(), tokenUsage[2] > 0 ? tokenUsage[2] : null);
            sendDoneEvent(emitter, new AiSummaryDoneDto(sources, List.of(), inTokens, outTokens, totalTokens));
            completeEmitter(emitter);

            saveLog(trimmedQuestion, pre, topArticleIds.size(),
                    RagQueryLog.Outcome.ANSWERED, inTokens, outTokens, totalTokens);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RAG 처리 중 인터럽트: {}", e.getMessage());
            sendErrorEvent(emitter, "답변을 불러올 수 없습니다");
            completeEmitter(emitter);
            saveLog(trimmedQuestion, pre, null, RagQueryLog.Outcome.ERROR, null, null, null);
        } catch (Exception e) {
            log.error("RAG 처리 중 예외 발생: {}", e.getMessage(), e);
            sendErrorEvent(emitter, "답변을 불러올 수 없습니다");
            completeEmitter(emitter);
            saveLog(trimmedQuestion, pre, null, RagQueryLog.Outcome.ERROR, null, null, null);
        }
    }

    protected Stream<String> callGeminiStream(String requestBody) throws IOException, InterruptedException {
        String url = GEMINI_BASE_URL + geminiModel
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
            throw new IOException("Gemini API HTTP " + response.statusCode() + ": " + body);
        }
        return response.body();
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

    private String buildGeminiRequestBody(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", userMessage))
                )),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "maxOutputTokens", 2000,
                        "thinkingConfig", Map.of("thinkingLevel", "minimal")
                )
        );
        return objectMapper.writeValueAsString(body);
    }

    private void extractTokenUsage(String json, int[] tokenUsage) {
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

    private Integer sumTokens(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private void saveLog(
            String question, RagPreprocessResult pre, Integer articleCount,
            RagQueryLog.Outcome outcome, Integer input, Integer output, Integer total) {
        try {
            String clampedQuestion = question.length() > QUESTION_MAX_CHARS
                    ? question.substring(0, QUESTION_MAX_CHARS) : question;
            ragQueryLogRepository.save(
                    RagQueryLog.builder()
                            .question(clampedQuestion)
                            .extractedCorporations(pre != null ? String.join(", ", pre.rawCorporations()) : null)
                            .extractedKeywords(pre != null ? pre.keywords() : null)
                            .vectorQuery(pre != null ? pre.vectorQuery() : null)
                            .matchedCorporationIds(pre != null
                                    ? pre.matchedCorporationIds().stream()
                                            .map(String::valueOf)
                                            .collect(Collectors.joining(", "))
                                    : null)
                            .articleCount(articleCount)
                            .outcome(outcome)
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(total)
                            .build());
        } catch (Exception e) {
            log.warn("RAG 질의 로그 저장 실패: question={}", question, e);
        }
    }

    private void sendPreprocessEvent(SseEmitter emitter, RagPreprocessResult pre) {
        try {
            emitter.send(SseEmitter.event().name("preprocess").data(objectMapper.writeValueAsString(pre)));
        } catch (Exception ignored) {}
    }

    private void sendSourcesEvent(SseEmitter emitter, List<AiSummarySourceDto> sources) {
        try {
            emitter.send(SseEmitter.event().name("sources").data(objectMapper.writeValueAsString(sources)));
        } catch (Exception ignored) {}
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

    private void sendNotFoundEvent(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("notfound")
                    .data(objectMapper.writeValueAsString(Map.of("message", message))));
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
