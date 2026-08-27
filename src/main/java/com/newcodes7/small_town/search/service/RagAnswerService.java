package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.dto.AiSummarySourceDto;
import com.newcodes7.small_town.search.dto.RagAnswerCacheDto;
import com.newcodes7.small_town.search.dto.RagDoneDto;
import com.newcodes7.small_town.search.entity.RagQueryLog;
import com.newcodes7.small_town.search.llm.LlmOptions;
import com.newcodes7.small_town.search.llm.LlmTokenUsage;
import com.newcodes7.small_town.search.llm.RagLlmClientResolver;
import com.newcodes7.small_town.search.llm.RagLlmException;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 질의응답 서비스 (관리자 테스트 페이지용)
 *
 * AiSummaryService의 SSE 스트리밍 구조를 따르되 질의응답형으로 확장:
 * 전처리(이중 쿼리 분해) → 기업 프리필터 하이브리드 retrieval → 선택 모델(Gemini/Bedrock/OpenAI) SSE 생성.
 * 대화 히스토리가 없는 공개 챗봇 첫 턴 질의는 1시간 로컬 캐시(ragAnswer)를 거치고,
 * 그 외(멀티턴, 관리자 테스트 페이지)는 캐시 없이 매 요청 실행한다. 모든 요청은 RagQueryLog에 기록한다.
 *
 * SSE 이벤트: preprocess → sources → prompt → token* → done / notfound / error
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagAnswerService {

    private final RagQueryPreprocessService preprocessService;
    private final ArticleSearchService articleSearchService;
    private final VectorSearchService vectorSearchService;
    private final CacheManager cacheManager;
    private final RagQueryLogRepository ragQueryLogRepository;
    private final ObjectMapper objectMapper;
    private final RagLlmClientResolver llmClientResolver;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    private static final int CHUNK_MAX_CHARS = 1000;
    private static final int QUESTION_MAX_CHARS = 500;
    private static final double ANSWER_TEMPERATURE = 0.2;
    private static final int ANSWER_MAX_TOKENS = 2000;
    private static final int HISTORY_TURN_LIMIT = 3;
    private static final int HISTORY_ANSWER_MAX_CHARS = 500;

    private static final String CACHE_NAME = "ragAnswer";

    /**
     * 진행 중인 RAG 요청 수 / 그중 답변 스트림 단계에 있는 수.
     *
     * <p>왜 필요한가: RAG처럼 요청 하나가 수십 초를 점유하는 경로에서는 <b>용량의 단위가 RPS가 아니라
     * 동시 진행 수</b>다. Little's Law로 L = λ·W인데 W(스트림 길이)가 LLM 손에 있어서, λ만 보면
     * "우리 용량"이 아니라 "LLM 속도"를 재게 된다. 실제로 2026-08-27 사다리의 천장은
     * 2.05 RPS가 아니라 <b>동시 스트림 50개</b>였다(load-test/results/2026-08-27-rag-ladder.md).
     *
     * <p>둘로 나눈 이유: 천장 50은 Bedrock <b>async</b> 클라이언트의 maxConcurrency이고,
     * 그 풀을 쓰는 건 답변 스트림(generateStream)뿐이다. 전처리(generateJson)는 sync 클라이언트라
     * 별도 풀을 쓴다. 따라서 천장 근접도를 보려면 {@code llmStreamInFlight}를,
     * "몇 명이 답변을 기다리는가"를 보려면 {@code inFlight}를 봐야 한다.
     * 전처리·retrieval이 약 3초, 답변 스트림이 약 17초라 두 값은 15% 안팎 벌어진다.
     *
     * <p>검색은 같은 것을 {@code search_concurrency_in_use}로 노출한다(SearchConcurrencyLimiter).
     * RAG에는 유입 제어가 없어 그 지표도 없었다 — 이 게이지가 그 공백을 메우고,
     * 나중에 유입 제어를 넣을 때 그대로 입력이 된다.
     */
    private final AtomicInteger inFlight = new AtomicInteger();

    private final AtomicInteger llmStreamInFlight = new AtomicInteger();
    private static final String CACHE_KEY_PREFIX = "rag-answer:";
    private static final String LOADTEST_CACHE_KEY_PREFIX = "rag-answer:lt:";
    private static final int CACHE_REPLAY_CHUNK_SIZE = 50;

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
            - 서로 다른 기업/사례를 다룰 때는 각 사례를 "## 1. ", "## 2. " 형식의 마크다운 헤더로 구분하세요.
              헤더 뒤에는 회사명 또는 사례를 대표하는 제목을 붙이세요. 사례가 하나뿐이면 헤더 없이 답해도 됩니다.
            - 근거로 사용한 문단마다 끝에 [출처N]을 표기하세요.
            - 핵심 키워드(기술명, 수치, 성과)만 **볼드체**로 강조하세요.
            - 인사말, 질문 반복, 결론 없는 채움 문장은 쓰지 마세요.

            마지막에 반드시 관련 검색어 3개를 아래 형식으로 포함하세요.
            [QUERIES]{"queries":["검색어1","검색어2","검색어3"]}[/QUERIES]
            """;

    private static final String QUERIES_START_TAG = "[QUERIES]";
    private static final String QUERIES_END_TAG = "[/QUERIES]";
    private static final int QUERIES_HOLD_BACK = QUERIES_START_TAG.length() - 1;

    private Counter successCounter;
    private Counter failureCounter;
    private Counter cachedCounter;
    private Counter notFoundCounter;
    private Timer successLatencyTimer;
    private Timer failureLatencyTimer;
    private Timer cachedLatencyTimer;
    private Timer notFoundLatencyTimer;
    private Timer llmTtfbTimer;
    private Timer llmDurationTimer;
    private Timer llmChunkGapTimer;
    private DistributionSummary llmChunkSizeSummary;

    @PostConstruct
    public void init() {
        successCounter = Counter.builder("rag_answer_requests_total")
                .tag("status", "success")
                .description("RAG answer successful requests")
                .register(meterRegistry);
        failureCounter = Counter.builder("rag_answer_requests_total")
                .tag("status", "failure")
                .description("RAG answer failed requests")
                .register(meterRegistry);
        cachedCounter = Counter.builder("rag_answer_requests_total")
                .tag("status", "cached")
                .description("RAG answer cached requests")
                .register(meterRegistry);
        notFoundCounter = Counter.builder("rag_answer_requests_total")
                .tag("status", "not_found")
                .description("RAG answer requests with no matching corporation or result")
                .register(meterRegistry);
        successLatencyTimer = Timer.builder("rag_answer_latency_seconds")
                .tag("status", "success")
                .description("RAG answer end-to-end latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
        failureLatencyTimer = Timer.builder("rag_answer_latency_seconds")
                .tag("status", "failure")
                .description("RAG answer end-to-end latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
        cachedLatencyTimer = Timer.builder("rag_answer_latency_seconds")
                .tag("status", "cached")
                .description("RAG answer end-to-end latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
        notFoundLatencyTimer = Timer.builder("rag_answer_latency_seconds")
                .tag("status", "not_found")
                .description("RAG answer end-to-end latency")
                .publishPercentileHistogram()
                .register(meterRegistry);
        llmTtfbTimer = Timer.builder("rag_answer_llm_ttfb_seconds")
                .description("Time from LLM call start to first streamed chunk")
                .publishPercentileHistogram()
                .register(meterRegistry);
        llmDurationTimer = Timer.builder("rag_answer_llm_duration_seconds")
                .description("LLM call wall time from request start to stream fully consumed or errored")
                .publishPercentileHistogram()
                .register(meterRegistry);
        llmChunkGapTimer = Timer.builder("rag_answer_llm_chunk_gap_seconds")
                .description("Inter-arrival gap between consecutive LLM stream chunks (second chunk onward)")
                .publishPercentileHistogram()
                .register(meterRegistry);
        Gauge.builder("rag_answer_in_flight", inFlight, AtomicInteger::get)
                .description("RAG answer requests currently in flight (whole handler)")
                .register(meterRegistry);
        Gauge.builder("rag_answer_llm_stream_in_flight", llmStreamInFlight, AtomicInteger::get)
                .description("RAG answer requests currently streaming from the LLM "
                        + "(this is what consumes the Bedrock async client maxConcurrency)")
                .register(meterRegistry);
        llmChunkSizeSummary = DistributionSummary.builder("rag_answer_llm_chunk_size_bytes")
                .description("Size in bytes of each streamed LLM chunk")
                .baseUnit("bytes")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public void streamAnswer(
            String question, int topArticles, int chunksPerArticle, double threshold,
            ModelOption model, SseEmitter emitter) {
        // 관리자 테스트 페이지 전용 — 모델/파라미터를 매 요청 자유롭게 바꾸므로 캐시 대상에서 제외한다.
        // 전처리도 선택 모델로 실행해 "선택 모델의 전처리 품질"을 그대로 실험할 수 있게 한다.
        streamAnswerInternal(question, topArticles, chunksPerArticle, threshold, model, model,
                null, null, null, emitter, false, false);
    }

    /**
     * 부하테스트 전용(/api/rag/answer/loadtest) — 공개 챗봇과 동일한 흐름을 타되
     * LLM은 mock 모델(rag.models endpoint override), 임베딩은 mock 엔드포인트로 호출하고
     * 캐시 키를 분리(rag-answer:lt:)해 mock 답변이 실사용자에게 서빙되지 않게 한다.
     */
    @Observed(name = "rag-answer", contextualName = "rag-answer-stream-loadtest")
    public void streamAnswerForLoadTest(
            String question, int topArticles, int chunksPerArticle, double threshold,
            ModelOption model, ModelOption preprocessModel,
            String conversationId, String ipAddress, Long userId, SseEmitter emitter) {
        streamAnswerInternal(question, topArticles, chunksPerArticle, threshold, model, preprocessModel,
                conversationId, ipAddress, userId, emitter, true, true);
    }

    /**
     * 사용자용 챗봇 엔드포인트용 오버로드 — conversationId가 있으면 직전 턴들을 로드해
     * 전처리·답변 생성 프롬프트에 대화 맥락으로 주입하고, 로그에 대화/사용자 식별 정보를 남긴다.
     * 히스토리가 없는 첫 턴 질의는 1시간 로컬 캐시 대상이 된다.
     *
     * searchExecutor(ContextExecutorService로 trace context 전파)에서 비동기 호출되므로
     * RagChatController의 HTTP 요청 span과 같은 trace로 이어져 Tempo에 기록된다.
     */
    @Observed(name = "rag-answer", contextualName = "rag-answer-stream")
    public void streamAnswer(
            String question, int topArticles, int chunksPerArticle, double threshold,
            ModelOption model, ModelOption preprocessModel,
            String conversationId, String ipAddress, Long userId, SseEmitter emitter) {
        streamAnswerInternal(question, topArticles, chunksPerArticle, threshold, model, preprocessModel,
                conversationId, ipAddress, userId, emitter, true, false);
    }

    private void streamAnswerInternal(
            String question, int topArticles, int chunksPerArticle, double threshold,
            ModelOption model, ModelOption preprocessModel, String conversationId, String ipAddress, Long userId,
            SseEmitter emitter, boolean cacheAllowed, boolean loadTest) {
        if (question == null || question.trim().isEmpty()) {
            sendErrorEvent(emitter, "질문을 입력해주세요");
            completeEmitter(emitter);
            return;
        }
        String trimmedQuestion = question.trim();
        // /api/rag/answer는 로그인 없이 공개된 엔드포인트라 과도하게 긴 입력으로 LLM 비용을
        // 유발하는 것을 막기 위해 여기서 거부한다 (saveLog의 clamp는 로그 저장용일 뿐 입력 검증이 아님).
        if (trimmedQuestion.length() > QUESTION_MAX_CHARS) {
            sendErrorEvent(emitter, "질문은 " + QUESTION_MAX_CHARS + "자 이내로 입력해주세요");
            completeEmitter(emitter);
            return;
        }
        String historyContext = buildHistoryContext(loadHistory(conversationId));
        boolean cacheEligible = cacheAllowed && historyContext.isBlank();
        // 부하테스트 캐시 키는 분리 — mock 답변이 실사용자 캐시에 섞이면 안 됨
        String cacheKey = (loadTest ? LOADTEST_CACHE_KEY_PREFIX : CACHE_KEY_PREFIX) + trimmedQuestion.toLowerCase();
        long startTime = System.currentTimeMillis();

        RagPreprocessResult pre = null;
        // 부모 span(rag-answer)에 캐시 히트 여부를 태그로 남겨 Tempo에서 히트/미스 지연을 분리 조회할 수 있게 한다.
        Observation parentObservation = observationRegistry.getCurrentObservation();
        // 입력 검증에 걸려 조기 반환한 요청은 세지 않는다 — 여기서부터가 실제 처리 구간이다.
        // 아래 try의 finally에서 반드시 반납한다(캐시 히트·notfound의 중간 return, 예외 경로 포함).
        inFlight.incrementAndGet();
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cacheEligible && cache != null) {
                RagAnswerCacheDto cached = cache.get(cacheKey, RagAnswerCacheDto.class);
                if (cached != null) {
                    if (parentObservation != null) {
                        parentObservation.lowCardinalityKeyValue("cache", "hit");
                    }
                    sendSourcesEvent(emitter, cached.sources());
                    replayFromCache(emitter, cached);
                    saveLog(trimmedQuestion, null, cached.sources().size(), RagQueryLog.Outcome.ANSWERED,
                            null, null, null, model, conversationId, ipAddress, userId, cached.answerText());
                    cachedCounter.increment();
                    cachedLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            if (parentObservation != null) {
                parentObservation.lowCardinalityKeyValue("cache", "miss");
            }

            Observation preprocessObservation = Observation
                    .createNotStarted("rag.preprocess", observationRegistry)
                    .contextualName("rag-preprocess")
                    .start();
            try {
                // cacheEligible(공개 경로 첫 턴)이면 전처리 결과를 로컬 캐시 경유로 재사용.
                // 멀티턴·admin 경로는 historyContext/파라미터가 매번 달라 비캐시 호출.
                pre = cacheEligible
                        ? preprocessService.preprocessCached(trimmedQuestion, preprocessModel)
                        : preprocessService.preprocess(trimmedQuestion, historyContext, preprocessModel);
            } catch (Exception e) {
                preprocessObservation.error(e);
                throw e;
            } finally {
                preprocessObservation.stop();
            }
            sendPreprocessEvent(emitter, pre);

            if (pre.isCorporationTargetedButNotMatched()) {
                sendNotFoundEvent(emitter, NO_CORP_MESSAGE);
                completeEmitter(emitter);
                saveLog(trimmedQuestion, pre, 0, RagQueryLog.Outcome.NO_CORP, null, null, null, model,
                        conversationId, ipAddress, userId, null);
                notFoundCounter.increment();
                notFoundLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                return;
            }

            Observation retrievalObservation = Observation
                    .createNotStarted("rag.retrieval", observationRegistry)
                    .contextualName("rag-retrieval")
                    .start();
            List<Long> topArticleIds;
            List<AiSummaryChunkDto> chunks;
            try {
                ArticleSearchService.HybridTopArticles topArticlesResult = cacheEligible
                        ? articleSearchService.getTopArticleIdsForRagCached(
                                pre.keywords(), pre.vectorQuery(), pre.matchedCorporationIds(),
                                topArticles, threshold, loadTest)
                        : articleSearchService.getTopArticleIdsForRag(
                                pre.keywords(), pre.vectorQuery(), pre.matchedCorporationIds(),
                                topArticles, threshold, loadTest);
                topArticleIds = topArticlesResult.articleIds();
                chunks = topArticleIds.isEmpty()
                        ? List.of()
                        : cacheEligible
                                ? vectorSearchService.getChunksForRagCached(
                                        pre.vectorQuery(), topArticleIds, topArticlesResult.queryEmbedding(),
                                        chunksPerArticle, loadTest)
                                : vectorSearchService.getChunksForRag(
                                        pre.vectorQuery(), topArticleIds, topArticlesResult.queryEmbedding(),
                                        chunksPerArticle, loadTest);
            } catch (Exception e) {
                retrievalObservation.error(e);
                throw e;
            } finally {
                retrievalObservation.stop();
            }
            if (chunks.isEmpty()) {
                sendNotFoundEvent(emitter, NO_RESULT_MESSAGE);
                completeEmitter(emitter);
                saveLog(trimmedQuestion, pre, 0, RagQueryLog.Outcome.NO_RESULT, null, null, null, model,
                        conversationId, ipAddress, userId, null);
                notFoundCounter.increment();
                notFoundLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
                return;
            }

            List<AiSummaryChunkDto> orderedChunks = chunks.stream()
                    .sorted(Comparator.comparingInt(c -> topArticleIds.indexOf(c.articleId())))
                    .collect(Collectors.toList());
            List<AiSummarySourceDto> sources = buildSources(orderedChunks);
            sendSourcesEvent(emitter, sources);

            String userMessage = (historyContext.isBlank() ? "" : historyContext + "\n")
                    + "질문: " + trimmedQuestion + "\n\n" + buildContext(orderedChunks);
            sendPromptEvent(emitter, RAG_SYSTEM_PROMPT, userMessage);

            LlmOptions answerOptions = new LlmOptions(
                    model.isTemperatureSupported() ? ANSWER_TEMPERATURE : null, ANSWER_MAX_TOKENS);
            StringBuilder answerBuilder = new StringBuilder();
            StringBuilder pendingBuf = new StringBuilder();
            boolean[] queriesDetected = {false};
            // LLM 호출~스트림 소진 전체를 하나의 구간으로 기록.
            // first-token 이벤트로 첫 토큰까지의 지연(TTFT)을 워터폴에서 구분할 수 있다.
            Observation llmObservation = Observation
                    .createNotStarted("rag.llm-stream", observationRegistry)
                    .contextualName("rag-llm-stream")
                    .start();
            boolean[] firstTokenSeen = {false};
            long llmStartNanos = System.nanoTime();
            long[] lastChunkNanos = {0L};
            LlmTokenUsage usage;
            // 이 구간만이 Bedrock async 풀(maxConcurrency)을 점유한다 — 천장 근접도는 이 게이지로 본다.
            llmStreamInFlight.incrementAndGet();
            try {
                usage = llmClientResolver.resolve(model.getProvider())
                    .generateStream(model.getId(), RAG_SYSTEM_PROMPT, userMessage,
                            answerOptions, text -> {
                                if (!firstTokenSeen[0]) {
                                    firstTokenSeen[0] = true;
                                    llmTtfbTimer.record(System.nanoTime() - llmStartNanos, TimeUnit.NANOSECONDS);
                                    llmObservation.event(Observation.Event.of("first-token"));
                                } else {
                                    llmChunkGapTimer.record(System.nanoTime() - lastChunkNanos[0], TimeUnit.NANOSECONDS);
                                }
                                lastChunkNanos[0] = System.nanoTime();
                                llmChunkSizeSummary.record(text.getBytes(StandardCharsets.UTF_8).length);
                                answerBuilder.append(text);
                                if (queriesDetected[0]) {
                                    return;
                                }
                                pendingBuf.append(text);
                                String pending = pendingBuf.toString();
                                int queryIdx = pending.indexOf(QUERIES_START_TAG);
                                if (queryIdx != -1) {
                                    queriesDetected[0] = true;
                                    if (queryIdx > 0) sendTokenEvent(emitter, pending.substring(0, queryIdx));
                                    pendingBuf.setLength(0);
                                } else {
                                    int safeLen = pending.length() - QUERIES_HOLD_BACK;
                                    if (safeLen > 0) {
                                        sendTokenEvent(emitter, pending.substring(0, safeLen));
                                        pendingBuf.delete(0, safeLen);
                                    }
                                }
                            });
            } catch (Exception e) {
                llmObservation.error(e);
                throw e;
            } finally {
                llmStreamInFlight.decrementAndGet();
                llmDurationTimer.record(System.nanoTime() - llmStartNanos, TimeUnit.NANOSECONDS);
                llmObservation.stop();
            }
            if (!queriesDetected[0] && pendingBuf.length() > 0) {
                sendTokenEvent(emitter, pendingBuf.toString());
            }

            String rawAnswer = answerBuilder.toString();
            String cleanAnswer = rawAnswer;
            List<String> relatedQueries = List.of();
            int qStart = rawAnswer.indexOf(QUERIES_START_TAG);
            int qEnd = rawAnswer.indexOf(QUERIES_END_TAG);
            if (qStart != -1 && qEnd > qStart) {
                cleanAnswer = rawAnswer.substring(0, qStart).trim();
                String queriesJson = rawAnswer.substring(qStart + QUERIES_START_TAG.length(), qEnd).trim();
                try {
                    JsonNode queriesNode = objectMapper.readTree(queriesJson).path("queries");
                    if (queriesNode.isArray()) {
                        relatedQueries = objectMapper.convertValue(queriesNode,
                                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    }
                } catch (Exception e) {
                    log.warn("RAG 관련 검색어 파싱 실패: {}", queriesJson);
                }
            }

            Integer inTokens = sumTokens(pre.inputTokens(), usage.inputTokens());
            Integer outTokens = sumTokens(pre.outputTokens(), usage.outputTokens());
            Integer totalTokens = sumTokens(pre.totalTokens(), usage.totalTokens());
            sendDoneEvent(emitter,
                    new RagDoneDto(sources, relatedQueries, inTokens, outTokens, totalTokens, model.getLabel()));
            completeEmitter(emitter);

            saveLog(trimmedQuestion, pre, topArticleIds.size(),
                    RagQueryLog.Outcome.ANSWERED, inTokens, outTokens, totalTokens, model,
                    conversationId, ipAddress, userId, cleanAnswer);

            if (cacheEligible && cache != null && !cleanAnswer.isEmpty()) {
                cache.put(cacheKey, new RagAnswerCacheDto(cleanAnswer, sources, relatedQueries, model.getLabel()));
            }

            successCounter.increment();
            successLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("RAG 처리 중 인터럽트: {}", e.getMessage());
            sendErrorEvent(emitter, "답변을 불러올 수 없습니다");
            completeEmitter(emitter);
            saveLog(trimmedQuestion, pre, null, RagQueryLog.Outcome.ERROR, null, null, null, model,
                    conversationId, ipAddress, userId, null);
            failureCounter.increment();
            failureLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
        } catch (RagLlmException e) {
            log.error("RAG LLM 호출 실패: {}", e.getMessage(), e);
            sendErrorEvent(emitter, e.getMessage());
            completeEmitter(emitter);
            saveLog(trimmedQuestion, pre, null, RagQueryLog.Outcome.ERROR, null, null, null, model,
                    conversationId, ipAddress, userId, null);
            failureCounter.increment();
            failureLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("RAG 처리 중 예외 발생: {}", e.getMessage(), e);
            sendErrorEvent(emitter, "답변을 불러올 수 없습니다");
            completeEmitter(emitter);
            saveLog(trimmedQuestion, pre, null, RagQueryLog.Outcome.ERROR, null, null, null, model,
                    conversationId, ipAddress, userId, null);
            failureCounter.increment();
            failureLatencyTimer.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private List<RagQueryLog> loadHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return ragQueryLogRepository
                .findTop3ByConversationIdAndOutcomeOrderByCreatedAtDesc(conversationId, RagQueryLog.Outcome.ANSWERED)
                .reversed();
    }

    private void replayFromCache(SseEmitter emitter, RagAnswerCacheDto cached) {
        String text = cached.answerText();
        for (int i = 0; i < text.length(); i += CACHE_REPLAY_CHUNK_SIZE) {
            sendTokenEvent(emitter, text.substring(i, Math.min(i + CACHE_REPLAY_CHUNK_SIZE, text.length())));
        }
        sendDoneEvent(emitter,
                new RagDoneDto(cached.sources(), cached.relatedQueries(), null, null, null, cached.model()));
        completeEmitter(emitter);
    }

    private String buildHistoryContext(List<RagQueryLog> history) {
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("이전 대화:\n");
        for (RagQueryLog turn : history) {
            sb.append("Q: ").append(turn.getQuestion()).append("\n");
            String answer = turn.getAnswer();
            if (answer != null && !answer.isEmpty()) {
                if (answer.length() > HISTORY_ANSWER_MAX_CHARS) {
                    answer = answer.substring(0, HISTORY_ANSWER_MAX_CHARS) + "...";
                }
                sb.append("A: ").append(answer).append("\n");
            }
        }
        return sb.toString();
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

    private Integer sumTokens(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    private void saveLog(
            String question, RagPreprocessResult pre, Integer articleCount,
            RagQueryLog.Outcome outcome, Integer input, Integer output, Integer total,
            ModelOption model, String conversationId, String ipAddress, Long userId, String answer) {
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
                            .model(model != null ? model.getId() : null)
                            .conversationId(conversationId)
                            .ipAddress(ipAddress)
                            .userId(userId)
                            .answer(answer)
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

    private void sendPromptEvent(SseEmitter emitter, String systemPrompt, String userMessage) {
        try {
            emitter.send(SseEmitter.event().name("prompt").data(objectMapper.writeValueAsString(
                    Map.of("systemPrompt", systemPrompt, "userMessage", userMessage))));
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

    private void sendDoneEvent(SseEmitter emitter, RagDoneDto done) {
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
