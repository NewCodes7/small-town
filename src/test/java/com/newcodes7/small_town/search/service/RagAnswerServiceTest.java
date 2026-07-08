package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.entity.RagQueryLog;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagAnswerServiceTest {

    @Mock private RagQueryPreprocessService preprocessService;
    @Mock private ArticleSearchService articleSearchService;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private RagQueryLogRepository ragQueryLogRepository;
    @Mock private SseEmitter emitter;

    private RagAnswerService ragAnswerService;

    @BeforeEach
    void setUp() {
        RagAnswerService realService = new RagAnswerService(
                preprocessService,
                articleSearchService,
                vectorSearchService,
                ragQueryLogRepository,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(realService, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(realService, "geminiModel", "gemini-3.5-flash");
        realService.init();
        ragAnswerService = spy(realService);
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
    @DisplayName("기업 지목+무매칭: preprocess/notfound 이벤트 후 종료, retrieval·Gemini 미호출, NO_CORP 로그")
    void streamAnswer_corporationNotMatched() throws Exception {
        when(preprocessService.preprocess(anyString()))
                .thenReturn(preprocessResult(List.of("존재하지않는회사"), List.of(), List.of()));

        ragAnswerService.streamAnswer("존재하지않는회사의 Kafka 사례", 5, 3, 0.6, emitter);

        // preprocess + notfound 이벤트 2회 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(articleSearchService, never())
                .getTopArticleIdsForRag(anyString(), anyString(), anyList(), anyInt(), anyDouble());
        verify(ragAnswerService, never()).callGeminiStream(anyString());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.NO_CORP);
        assertThat(log.getExtractedCorporations()).isEqualTo("존재하지않는회사");
    }

    @Test
    @DisplayName("retrieval 0건: notfound 이벤트 후 종료, Gemini 미호출, NO_RESULT 로그")
    void streamAnswer_noRetrievalResults() throws Exception {
        when(preprocessService.preprocess(anyString()))
                .thenReturn(preprocessResult(List.of(), List.of(), List.of()));
        when(articleSearchService.getTopArticleIdsForRag(anyString(), anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(ArticleSearchService.HybridTopArticles.empty());

        ragAnswerService.streamAnswer("사내 미보유 기술 사례", 5, 3, 0.6, emitter);

        // preprocess + notfound 이벤트 2회 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(ragAnswerService, never()).callGeminiStream(anyString());

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.NO_RESULT);
        assertThat(log.getArticleCount()).isZero();
    }

    @Test
    @DisplayName("정상 흐름: preprocess → sources → token → done 이벤트 전송, ANSWERED 로그(토큰 합산)")
    void streamAnswer_success() throws Exception {
        when(preprocessService.preprocess(anyString()))
                .thenReturn(preprocessResult(List.of("네이버"), List.of(1L), List.of("네이버")));
        when(articleSearchService.getTopArticleIdsForRag(
                anyString(), anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(10L), null));
        when(vectorSearchService.getChunksForRag(anyString(), anyList(), any(), anyInt()))
                .thenReturn(List.of(new AiSummaryChunkDto(
                        10L, "Kafka 도입기", "https://example.com/kafka", "Kafka 도입 배경과 성과", null, "네이버", null)));

        Stream<String> geminiStream = Stream.of(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"**Kafka**를 도입했습니다. [출처1]\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":200,\"candidatesTokenCount\":50,\"totalTokenCount\":250}}"
        );
        doReturn(geminiStream).when(ragAnswerService).callGeminiStream(anyString());

        ragAnswerService.streamAnswer("네이버에서 Kafka 도입한 사례", 5, 3, 0.6, emitter);

        // preprocess + sources + token + done 최소 4회 전송 후 complete
        verify(emitter, atLeast(4)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(articleSearchService).getTopArticleIdsForRag(
                "Kafka 도입", "Kafka를 도입한 사례", List.of(1L), 5, 0.6);
        verify(vectorSearchService).getChunksForRag("Kafka를 도입한 사례", List.of(10L), null, 3);

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ANSWERED);
        assertThat(log.getArticleCount()).isEqualTo(1);
        // 전처리(100/30/130) + 생성(200/50/250) 합산
        assertThat(log.getInputTokens()).isEqualTo(300);
        assertThat(log.getOutputTokens()).isEqualTo(80);
        assertThat(log.getTotalTokens()).isEqualTo(380);
        assertThat(log.getMatchedCorporationIds()).isEqualTo("1");
    }

    @Test
    @DisplayName("전처리 실패: error 이벤트 전송 후 종료, ERROR 로그")
    void streamAnswer_preprocessFails() throws Exception {
        when(preprocessService.preprocess(anyString()))
                .thenThrow(new java.io.IOException("Gemini API HTTP 500"));

        ragAnswerService.streamAnswer("네이버 Kafka 사례", 5, 3, 0.6, emitter);

        // error 이벤트 1회 전송 후 complete
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        RagQueryLog log = capturedLog();
        assertThat(log.getOutcome()).isEqualTo(RagQueryLog.Outcome.ERROR);
    }

    @Test
    @DisplayName("빈 질문: 즉시 error 이벤트 전송 후 종료, 전처리·로그 미호출")
    void streamAnswer_emptyQuestion() throws Exception {
        ragAnswerService.streamAnswer("   ", 5, 3, 0.6, emitter);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        verify(preprocessService, never()).preprocess(anyString());
        verify(ragQueryLogRepository, never()).save(any());
    }
}
