package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.dto.RagAnswerCacheDto;
import com.newcodes7.small_town.search.entity.RagQueryLog;
import com.newcodes7.small_town.search.llm.LlmTokenUsage;
import com.newcodes7.small_town.search.llm.RagLlmClient;
import com.newcodes7.small_town.search.llm.RagLlmClientResolver;
import com.newcodes7.small_town.search.llm.RagLlmException;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagAnswerServiceTest {

    @Mock private RagQueryPreprocessService preprocessService;
    @Mock private ArticleSearchService articleSearchService;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;
    @Mock private RagQueryLogRepository ragQueryLogRepository;
    @Mock private RagLlmClientResolver llmClientResolver;
    @Mock private RagLlmClient llmClient;
    @Mock private SseEmitter emitter;

    private RagAnswerService ragAnswerService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ragAnswerService = new RagAnswerService(
                preprocessService,
                articleSearchService,
                vectorSearchService,
                cacheManager,
                ragQueryLogRepository,
                new ObjectMapper(),
                llmClientResolver,
                ObservationRegistry.NOOP,
                meterRegistry
        );
        ragAnswerService.init();
    }

    private ModelOption geminiModel() {
        ModelOption model = new ModelOption();
        model.setId("gemini-3.5-flash");
        model.setLabel("Gemini 3.5 Flash");
        model.setProvider(Provider.GEMINI);
        return model;
    }

    private RagPreprocessResult preprocessResult(List<String> raw, List<Long> matchedIds, List<String> matchedNames) {
        return new RagPreprocessResult(raw, matchedIds, matchedNames,
                "Kafka 도입", "Kafka를 도입한 사례", 100, 30, 130);
    }

    /**
     * LLM 지연 미터는 경로별로(source=real|loadtest|admin) 나뉘어 등록된다 — 라벨 없이 조회하면
     * 어느 시계열이 잡힐지 알 수 없다. mock 부하테스트 표본이 실경로 캘리브레이션을 오염시키는 것을
     * 막으려고 나눈 것이라(RagAnswerService.llmMeters), 테스트도 경로를 명시해서 본다.
     */
    private io.micrometer.core.instrument.Timer llmTimer(String name, String source) {
        return meterRegistry.get(name).tag("source", source).timer();
    }

    private RagQueryLog capturedLog() {
        ArgumentCaptor<RagQueryLog> captor = ArgumentCaptor.forClass(RagQueryLog.class);
        verify(ragQueryLogRepository).save(captor.capture());
        return captor.getValue();
    }

    // ==================== in-flight 게이지 ====================
    //
    // 이 게이지의 가치는 "안 새는 것"에 전적으로 달려 있다. 한 번이라도 반납을 놓치면 값이
    // 단조증가해 영영 못 쓰게 되고, 하필 그걸 알아채는 시점은 과부하 때다.
    // 그래서 정상 종료뿐 아니라 조기 반환·예외 경로까지 0으로 돌아오는지 고정한다.

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private AiSummaryChunkDto chunk(Long articleId) {
        return new AiSummaryChunkDto(articleId, "Kafka 도입기", "https://example.com/kafka",
                "Kafka 도입 배경과 성과", null, "네이버", null);
    }

    @Test
    @DisplayName("in-flight: 정상 응답 후 두 게이지 모두 0으로 반납된다")
    void inFlight_정상경로_반납() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of(), List.of(), List.of()));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(1L), new float[]{0.1f}));
        when(vectorSearchService.getChunksForRag(
                anyString(), anyList(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(chunk(1L)));
        when(llmClientResolver.resolve(any())).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new LlmTokenUsage(10, 20, 30));

        ragAnswerService.streamAnswer("Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        assertThat(gauge("rag_answer_in_flight")).isZero();
        assertThat(gauge("rag_answer_llm_stream_in_flight")).isZero();
    }

    @Test
    @DisplayName("in-flight: LLM이 예외를 던져도 두 게이지 모두 반납된다 (누수 방지)")
    void inFlight_LLM예외_반납() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of(), List.of(), List.of()));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(1L), new float[]{0.1f}));
        when(vectorSearchService.getChunksForRag(
                anyString(), anyList(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(chunk(1L)));
        when(llmClientResolver.resolve(any())).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new RagLlmException("Bedrock 호출에 실패했습니다", null));

        ragAnswerService.streamAnswer("Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        assertThat(gauge("rag_answer_in_flight")).isZero();
        assertThat(gauge("rag_answer_llm_stream_in_flight")).isZero();
    }

    @Test
    @DisplayName("in-flight: retrieval이 빈 결과로 조기 반환해도 반납된다 (LLM 게이지는 애초에 0)")
    void inFlight_조기반환_반납() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of("존재하지않는회사"), List.of(), List.of()));

        ragAnswerService.streamAnswer("존재하지않는회사의 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        assertThat(gauge("rag_answer_in_flight")).isZero();
        assertThat(gauge("rag_answer_llm_stream_in_flight")).isZero();
    }

    @Test
    @DisplayName("in-flight: 입력 검증에 걸린 요청은 아예 세지 않는다")
    void inFlight_입력검증_미집계() throws Exception {
        ragAnswerService.streamAnswer("   ", 5, 3, 0.6, geminiModel(), emitter);

        assertThat(gauge("rag_answer_in_flight")).isZero();
        verify(preprocessService, never()).preprocess(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("기업 지목+무매칭: preprocess/notfound 이벤트 후 종료, retrieval·LLM 미호출, NO_CORP 로그")
    void streamAnswer_corporationNotMatched() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of("존재하지않는회사"), List.of(), List.of()));

        ragAnswerService.streamAnswer("존재하지않는회사의 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        // preprocess + notfound 이벤트 2회 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(articleSearchService, never())
                .getTopArticleIdsForRag(anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean());
        verify(llmClient, never()).generateStream(anyString(), anyString(), anyString(), any(), any());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.NO_CORP);
        assertThat(log.getExtractedCorporations()).isEqualTo("존재하지않는회사");
        assertThat(log.getModel()).isEqualTo("gemini-3.5-flash");

        assertThat(meterRegistry.get("rag_answer_requests_total").tag("status", "not_found").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rag_answer_latency_seconds").tag("status", "not_found").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("retrieval 0건: notfound 이벤트 후 종료, LLM 미호출, NO_RESULT 로그")
    void streamAnswer_noRetrievalResults() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of(), List.of(), List.of()));
        when(articleSearchService.getTopArticleIdsForRag(anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(ArticleSearchService.HybridTopArticles.empty());

        ragAnswerService.streamAnswer("사내 미보유 기술 사례", 5, 3, 0.6, geminiModel(), emitter);

        // preprocess + notfound 이벤트 2회 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(llmClient, never()).generateStream(anyString(), anyString(), anyString(), any(), any());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.NO_RESULT);
        assertThat(log.getArticleCount()).isZero();

        assertThat(meterRegistry.get("rag_answer_requests_total").tag("status", "not_found").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rag_answer_latency_seconds").tag("status", "not_found").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("정상 흐름: preprocess → sources → token → done 이벤트 전송, ANSWERED 로그(토큰 합산·모델 기록)")
    void streamAnswer_success() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of("네이버"), List.of(1L), List.of("네이버")));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRag(anyString(), anyList(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onText = invocation.getArgument(4);
                    onText.accept("**Kafka**를 도입했습니다. [출처1]");
                    onText.accept("[QUERIES]{\"queries\":[\"Kafka 성능\",\"메시지 큐 비교\",\"이벤트 드리븐\"]}[/QUERIES]");
                    return new LlmTokenUsage(200, 50, 250);
                });

        ragAnswerService.streamAnswer("네이버에서 Kafka 도입한 사례", 5, 3, 0.6, geminiModel(), emitter);

        // preprocess + sources + prompt + token + done 최소 5회 전송 후 complete
        verify(emitter, atLeast(5)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(articleSearchService).getTopArticleIdsForRag(
                "Kafka 도입", "Kafka를 도입한 사례", List.of(1L), 5, 0.6, false);
        verify(vectorSearchService).getChunksForRag("Kafka를 도입한 사례", List.of(10L), null, 3, false);
        verify(llmClient).generateStream(
                eq("gemini-3.5-flash"), anyString(), anyString(), any(), any());

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, atLeast(5)).send(eventCaptor.capture());
        String allSentData = eventCaptor.getAllValues().stream()
                .flatMap(builder -> builder.build().stream())
                .map(part -> String.valueOf(part.getData()))
                .collect(java.util.stream.Collectors.joining("\n"));
        // done 이벤트에 관련 검색어가 포함되어야 함
        assertThat(allSentData).contains("Kafka 성능", "메시지 큐 비교", "이벤트 드리븐");
        // 스트리밍된 답변 본문(token 이벤트)에는 [QUERIES] 태그가 새면 안 됨 — prompt 이벤트(시스템 프롬프트 디버그 표시)는 제외
        String tokenEventsData = eventCaptor.getAllValues().stream()
                .flatMap(builder -> builder.build().stream())
                .map(part -> String.valueOf(part.getData()))
                .filter(data -> data.startsWith("\"")) // token 이벤트는 JSON 문자열(따옴표로 시작), 다른 이벤트는 객체(중괄호)/배열
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(tokenEventsData).doesNotContain("QUERIES");

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ANSWERED);
        assertThat(log.getArticleCount()).isEqualTo(1);
        // 전처리(100/30/130) + 생성(200/50/250) 합산
        assertThat(log.getInputTokens()).isEqualTo(300);
        assertThat(log.getOutputTokens()).isEqualTo(80);
        assertThat(log.getTotalTokens()).isEqualTo(380);
        assertThat(log.getMatchedCorporationIds()).isEqualTo("1");
        assertThat(log.getModel()).isEqualTo("gemini-3.5-flash");
        assertThat(log.getAnswer()).isEqualTo("**Kafka**를 도입했습니다. [출처1]");
        assertThat(log.getAnswer()).doesNotContain("QUERIES");

        // 신규 메트릭 기록 확인 (모의 스트림 2개 청크 → chunk size 2건, gap 1건)
        assertThat(meterRegistry.get("rag_answer_requests_total").tag("status", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rag_answer_latency_seconds").tag("status", "success").timer().count()).isEqualTo(1L);
        // 이 테스트는 6-arg(관리자) 오버로드를 쓰므로 source=admin으로 기록된다
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "admin").count()).isEqualTo(1L);
        assertThat(llmTimer("rag_answer_llm_duration_seconds", "admin").count()).isEqualTo(1L);
        assertThat(meterRegistry.get("rag_answer_llm_chunk_size_bytes")
                .tag("source", "admin").summary().count()).isEqualTo(2L);
        assertThat(llmTimer("rag_answer_llm_chunk_gap_seconds", "admin").count()).isEqualTo(1L);
        // 다른 경로 시계열은 등록만 돼 있고 0이어야 한다 — 라벨이 실제로 경로를 가르는지 확인
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "real").count()).isZero();
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "loadtest").count()).isZero();
    }

    @Test
    @DisplayName("전처리 실패: error 이벤트 전송 후 종료, ERROR 로그")
    void streamAnswer_preprocessFails() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenThrow(new java.io.IOException("Gemini API HTTP 500"));

        ragAnswerService.streamAnswer("네이버 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        // error 이벤트 1회 전송 후 complete
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ERROR);

        assertThat(meterRegistry.get("rag_answer_requests_total").tag("status", "failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rag_answer_latency_seconds").tag("status", "failure").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("LLM 호출 실패(RagLlmException): error 이벤트 전송 후 종료, ERROR 로그에 모델 기록")
    void streamAnswer_llmFails() throws Exception {
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenThrow(new RagLlmException("Bedrock 모델 접근 권한이 없습니다"));

        ragAnswerService.streamAnswer("네이버 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ERROR);
        assertThat(log.getModel()).isEqualTo("gemini-3.5-flash");

        assertThat(meterRegistry.get("rag_answer_requests_total").tag("status", "failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rag_answer_latency_seconds").tag("status", "failure").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("빈 질문: 즉시 error 이벤트 전송 후 종료, 전처리·로그 미호출")
    void streamAnswer_emptyQuestion() throws Exception {
        ragAnswerService.streamAnswer("   ", 5, 3, 0.6, geminiModel(), emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(preprocessService, never()).preprocess(anyString(), anyString(), any());
        verify(ragQueryLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("캐시 히트(첫 턴): 전처리·LLM 미호출, 캐시된 답변을 SSE로 재생, ANSWERED 로그")
    void streamAnswer_cacheHit_firstTurn() throws Exception {
        String question = "네이버 Kafka 도입 사례";
        String cacheKey = "rag-answer:" + question.toLowerCase();
        RagAnswerCacheDto cached = new RagAnswerCacheDto(
                "**Kafka**를 도입했습니다.",
                List.of(new com.newcodes7.small_town.search.dto.AiSummarySourceDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", null, "네이버", null)),
                List.of("Kafka 성능", "메시지 큐 비교", "이벤트 드리븐"),
                "Gemini 3.5 Flash");

        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(cache.get(eq(cacheKey), eq(RagAnswerCacheDto.class))).thenReturn(cached);

        ragAnswerService.streamAnswer(question, 5, 3, 0.6, geminiModel(), geminiModel(), null, "127.0.0.1", null, emitter);

        verify(preprocessService, never()).preprocess(anyString(), anyString(), any());
        verify(llmClient, never()).generateStream(anyString(), anyString(), anyString(), any(), any());
        verify(emitter, atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ANSWERED);
        assertThat(log.getAnswer()).isEqualTo("**Kafka**를 도입했습니다.");
        assertThat(log.getArticleCount()).isEqualTo(1);

        // 캐시 히트는 LLM을 호출하지 않으므로 LLM 전용 메트릭은 기록되지 않아야 함 (미리 등록만 돼 있어 count=0)
        assertThat(meterRegistry.get("rag_answer_requests_total").tag("status", "cached").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("rag_answer_latency_seconds").tag("status", "cached").timer().count()).isEqualTo(1L);
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "real").count()).isZero();
    }

    @Test
    @DisplayName("캐시 미스(첫 턴): 정상 생성 후 캐시에 저장")
    void streamAnswer_cacheMiss_populatesCache() throws Exception {
        String question = "네이버에서 Kafka 도입한 사례";
        String cacheKey = "rag-answer:" + question.toLowerCase();

        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(cache.get(eq(cacheKey), eq(RagAnswerCacheDto.class))).thenReturn(null);
        // 첫 턴(cacheEligible)은 전처리·검색 모두 캐시 경유 변형을 사용한다
        when(preprocessService.preprocessCached(anyString(), any()))
                .thenReturn(preprocessResult(List.of("네이버"), List.of(1L), List.of("네이버")));
        when(articleSearchService.getTopArticleIdsForRagCached(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRagCached(anyString(), anyList(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onText = invocation.getArgument(4);
                    onText.accept("**Kafka**를 도입했습니다. [출처1]");
                    onText.accept("[QUERIES]{\"queries\":[\"Kafka 성능\",\"메시지 큐 비교\",\"이벤트 드리븐\"]}[/QUERIES]");
                    return new LlmTokenUsage(200, 50, 250);
                });

        ragAnswerService.streamAnswer(question, 5, 3, 0.6, geminiModel(), geminiModel(), null, "127.0.0.1", null, emitter);

        verify(cache).put(eq(cacheKey), any(RagAnswerCacheDto.class));
        verify(preprocessService, never()).preprocess(anyString(), anyString(), any());
        verify(articleSearchService, never()).getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean());
    }

    @Test
    @DisplayName("멀티턴(히스토리 존재): 캐시 조회/저장 모두 건너뜀")
    void streamAnswer_multiTurn_bypassesCache() throws Exception {
        String conversationId = "conv-1";
        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(ragQueryLogRepository.findTop3ByConversationIdAndOutcomeOrderByCreatedAtDesc(
                eq(conversationId), eq(RagQueryLog.Outcome.ANSWERED)))
                .thenReturn(List.of(RagQueryLog.builder()
                        .question("네이버 Kafka 도입 사례")
                        .answer("네이버는 Kafka를 도입했습니다.")
                        .outcome(RagQueryLog.Outcome.ANSWERED)
                        .build()));
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of("네이버"), List.of(1L), List.of("네이버")));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRag(anyString(), anyList(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new LlmTokenUsage(200, 50, 250));

        ragAnswerService.streamAnswer("그 회사 다른 사례는?", 5, 3, 0.6, geminiModel(), geminiModel(),
                conversationId, "127.0.0.1", null, emitter);

        verify(cache, never()).get(anyString(), eq(RagAnswerCacheDto.class));
        verify(cache, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("관리자(6-arg) 오버로드: 캐시 조회/저장 모두 건너뜀")
    void streamAnswer_adminOverload_neverCaches() throws Exception {
        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of("네이버"), List.of(1L), List.of("네이버")));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRag(anyString(), anyList(), any(), anyInt(), anyBoolean()))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new LlmTokenUsage(200, 50, 250));

        ragAnswerService.streamAnswer("네이버에서 Kafka 도입한 사례", 5, 3, 0.6, geminiModel(), emitter);

        verify(cache, never()).get(anyString(), eq(RagAnswerCacheDto.class));
        verify(cache, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("공개 경로(첫 턴): 전처리는 별도 preprocessModel로 캐시 경유 호출")
    void streamAnswer_publicPath_usesPreprocessModel() throws Exception {
        ModelOption answerModel = geminiModel();
        ModelOption preprocessModel = new ModelOption();
        preprocessModel.setId("global.anthropic.claude-haiku-4-5-20251001-v1:0");
        preprocessModel.setLabel("Claude Haiku 4.5 (Bedrock)");
        preprocessModel.setProvider(Provider.BEDROCK);

        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(cache.get(anyString(), eq(RagAnswerCacheDto.class))).thenReturn(null);
        // 기업 지목했지만 무매칭 → NO_CORP 조기 종료 경로 (답변 LLM 스텁 불필요)
        when(preprocessService.preprocessCached(anyString(), any()))
                .thenReturn(preprocessResult(List.of("없는회사"), List.of(), List.of()));

        ragAnswerService.streamAnswer("없는회사 Kafka 사례", 5, 3, 0.6, answerModel, preprocessModel,
                null, "127.0.0.1", null, emitter);

        verify(preprocessService).preprocessCached(eq("없는회사 Kafka 사례"), org.mockito.ArgumentMatchers.same(preprocessModel));
        verify(preprocessService, never()).preprocess(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("관리자(6-arg) 오버로드: 선택 모델로 전처리를 비캐시 직접 호출")
    void streamAnswer_adminOverload_preprocessesWithSelectedModel() throws Exception {
        ModelOption model = geminiModel();
        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(preprocessService.preprocess(anyString(), anyString(), any()))
                .thenReturn(preprocessResult(List.of("없는회사"), List.of(), List.of()));

        ragAnswerService.streamAnswer("없는회사 Kafka 사례", 5, 3, 0.6, model, emitter);

        verify(preprocessService).preprocess(eq("없는회사 Kafka 사례"), eq(""), org.mockito.ArgumentMatchers.same(model));
        verify(preprocessService, never()).preprocessCached(anyString(), any());
        verify(articleSearchService, never()).getTopArticleIdsForRagCached(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), anyBoolean());
    }

    @Test
    @DisplayName("부하테스트 오버로드: 캐시 키 rag-answer:lt: 분리 + retrieval 전체에 mock 플래그(true) 전달")
    void streamAnswerForLoadTest_usesLoadTestCacheKeyAndMockFlag() throws Exception {
        String question = "네이버에서 Kafka 도입한 사례";
        String cacheKey = "rag-answer:lt:" + question.toLowerCase();
        when(cacheManager.getCache("ragAnswer")).thenReturn(cache);
        when(cache.get(eq(cacheKey), eq(RagAnswerCacheDto.class))).thenReturn(null);
        when(preprocessService.preprocessCached(anyString(), any()))
                .thenReturn(preprocessResult(List.of(), List.of(), List.of()));
        when(articleSearchService.getTopArticleIdsForRagCached(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), eq(true)))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRagCached(anyString(), anyList(), any(), anyInt(), eq(true)))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onText = invocation.getArgument(4);
                    onText.accept("**Kafka**를 도입했습니다. [출처1]");
                    return new LlmTokenUsage(200, 50, 250);
                });

        ragAnswerService.streamAnswerForLoadTest(question, 5, 3, 0.6, geminiModel(), geminiModel(),
                null, "127.0.0.1", null, emitter);

        // 실사용자 캐시 키(rag-answer:)가 아닌 lt: 키로만 조회·저장되어야 함
        verify(cache).get(eq(cacheKey), eq(RagAnswerCacheDto.class));
        verify(cache).put(eq(cacheKey), any(RagAnswerCacheDto.class));
        verify(cache, never()).get(eq("rag-answer:" + question.toLowerCase()), eq(RagAnswerCacheDto.class));
        // retrieval 두 단계 모두 mock 임베딩 플래그로 호출
        verify(articleSearchService).getTopArticleIdsForRagCached(
                anyString(), anyString(), anyList(), anyInt(), anyDouble(), eq(true));
        verify(vectorSearchService).getChunksForRagCached(anyString(), anyList(), any(), anyInt(), eq(true));
        verify(emitter).complete();

        // 부하테스트 표본은 source=loadtest로만 쌓여야 한다 — 이게 섞이면 mock 지연 상수를
        // mock 자기 값으로 보정하는 순환이 된다(2026-08-28 실측: 30일 표본의 99.5%가 mock이었다).
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "loadtest").count()).isEqualTo(1L);
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "real").count()).isZero();
        assertThat(llmTimer("rag_answer_llm_ttfb_seconds", "admin").count()).isZero();
    }
}
