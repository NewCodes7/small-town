package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.newcodes7.small_town.search.entity.RagQueryLog;
import com.newcodes7.small_town.search.llm.LlmTokenUsage;
import com.newcodes7.small_town.search.llm.RagLlmClient;
import com.newcodes7.small_town.search.llm.RagLlmClientResolver;
import com.newcodes7.small_town.search.llm.RagLlmException;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagAnswerServiceTest {

    @Mock private RagQueryPreprocessService preprocessService;
    @Mock private ArticleSearchService articleSearchService;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private RagQueryLogRepository ragQueryLogRepository;
    @Mock private RagLlmClientResolver llmClientResolver;
    @Mock private RagLlmClient llmClient;
    @Mock private SseEmitter emitter;

    private RagAnswerService ragAnswerService;

    @BeforeEach
    void setUp() {
        ragAnswerService = new RagAnswerService(
                preprocessService,
                articleSearchService,
                vectorSearchService,
                ragQueryLogRepository,
                new ObjectMapper(),
                llmClientResolver
        );
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

    private RagQueryLog capturedLog() {
        ArgumentCaptor<RagQueryLog> captor = ArgumentCaptor.forClass(RagQueryLog.class);
        verify(ragQueryLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("기업 지목+무매칭: preprocess/notfound 이벤트 후 종료, retrieval·LLM 미호출, NO_CORP 로그")
    void streamAnswer_corporationNotMatched() throws Exception {
        when(preprocessService.preprocess(anyString(), any()))
                .thenReturn(preprocessResult(List.of("존재하지않는회사"), List.of(), List.of()));

        ragAnswerService.streamAnswer("존재하지않는회사의 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        // preprocess + notfound 이벤트 2회 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(articleSearchService, never())
                .getTopArticleIdsForRag(anyString(), anyString(), anyList(), anyInt(), anyDouble());
        verify(llmClient, never()).generateStream(anyString(), anyString(), anyString(), any(), any());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.NO_CORP);
        assertThat(log.getExtractedCorporations()).isEqualTo("존재하지않는회사");
        assertThat(log.getModel()).isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("retrieval 0건: notfound 이벤트 후 종료, LLM 미호출, NO_RESULT 로그")
    void streamAnswer_noRetrievalResults() throws Exception {
        when(preprocessService.preprocess(anyString(), any()))
                .thenReturn(preprocessResult(List.of(), List.of(), List.of()));
        when(articleSearchService.getTopArticleIdsForRag(anyString(), anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(ArticleSearchService.HybridTopArticles.empty());

        ragAnswerService.streamAnswer("사내 미보유 기술 사례", 5, 3, 0.6, geminiModel(), emitter);

        // preprocess + notfound 이벤트 2회 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(llmClient, never()).generateStream(anyString(), anyString(), anyString(), any(), any());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.NO_RESULT);
        assertThat(log.getArticleCount()).isZero();
    }

    @Test
    @DisplayName("정상 흐름: preprocess → sources → token → done 이벤트 전송, ANSWERED 로그(토큰 합산·모델 기록)")
    void streamAnswer_success() throws Exception {
        when(preprocessService.preprocess(anyString(), any()))
                .thenReturn(preprocessResult(List.of("네이버"), List.of(1L), List.of("네이버")));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRag(anyString(), anyList(), any(), anyInt()))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateStream(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onText = invocation.getArgument(4);
                    onText.accept("**Kafka**를 도입했습니다. [출처1]");
                    return new LlmTokenUsage(200, 50, 250);
                });

        ragAnswerService.streamAnswer("네이버에서 Kafka 도입한 사례", 5, 3, 0.6, geminiModel(), emitter);

        // preprocess + sources + prompt + token + done 최소 5회 전송 후 complete
        verify(emitter, atLeast(5)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(articleSearchService).getTopArticleIdsForRag(
                "Kafka 도입", "Kafka를 도입한 사례", List.of(1L), 5, 0.6);
        verify(vectorSearchService).getChunksForRag("Kafka를 도입한 사례", List.of(10L), null, 3);
        verify(llmClient).generateStream(
                eq("gemini-3.5-flash"), anyString(), anyString(), any(), any());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ANSWERED);
        assertThat(log.getArticleCount()).isEqualTo(1);
        // 전처리(100/30/130) + 생성(200/50/250) 합산
        assertThat(log.getInputTokens()).isEqualTo(300);
        assertThat(log.getOutputTokens()).isEqualTo(80);
        assertThat(log.getTotalTokens()).isEqualTo(380);
        assertThat(log.getMatchedCorporationIds()).isEqualTo("1");
        assertThat(log.getModel()).isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("전처리 실패: error 이벤트 전송 후 종료, ERROR 로그")
    void streamAnswer_preprocessFails() throws Exception {
        when(preprocessService.preprocess(anyString(), any()))
                .thenThrow(new java.io.IOException("Gemini API HTTP 500"));

        ragAnswerService.streamAnswer("네이버 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        // error 이벤트 1회 전송 후 complete
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ERROR);
    }

    @Test
    @DisplayName("LLM 호출 실패(RagLlmException): error 이벤트 전송 후 종료, ERROR 로그에 모델 기록")
    void streamAnswer_llmFails() throws Exception {
        when(preprocessService.preprocess(anyString(), any()))
                .thenThrow(new RagLlmException("Bedrock 모델 접근 권한이 없습니다"));

        ragAnswerService.streamAnswer("네이버 Kafka 사례", 5, 3, 0.6, geminiModel(), emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ERROR);
        assertThat(log.getModel()).isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("빈 질문: 즉시 error 이벤트 전송 후 종료, 전처리·로그 미호출")
    void streamAnswer_emptyQuestion() throws Exception {
        ragAnswerService.streamAnswer("   ", 5, 3, 0.6, geminiModel(), emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(preprocessService, never()).preprocess(anyString(), any());
        verify(ragQueryLogRepository, never()).save(any());
    }
}
