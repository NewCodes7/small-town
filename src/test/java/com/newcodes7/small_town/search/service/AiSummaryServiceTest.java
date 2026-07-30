package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.search.dto.AiSummaryCacheDto;
import com.newcodes7.small_town.search.dto.AiSummaryChunkDto;
import com.newcodes7.small_town.search.dto.AiSummarySourceDto;
import com.newcodes7.small_town.search.repository.AiSummaryLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AiSummaryServiceTest {

    @Mock private VectorSearchService vectorSearchService;
    @Mock private ArticleSearchService articleSearchService;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;
    @Mock private SseEmitter emitter;
    @Mock private AiSummaryLogRepository aiSummaryLogRepository;

    private AiSummaryService aiSummaryService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        AiSummaryService realService = new AiSummaryService(
                vectorSearchService,
                articleSearchService,
                cacheManager,
                meterRegistry,
                new ObjectMapper(),
                aiSummaryLogRepository,
                ObservationRegistry.create()
        );
        ReflectionTestUtils.setField(realService, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(realService, "geminiModel", "gemini-3.5-flash");
        ReflectionTestUtils.setField(realService, "recommendedQueriesRaw", "Spring Boot,Redis 캐시,JPA");
        realService.init();
        aiSummaryService = spy(realService);
    }

    @Test
    @DisplayName("정상 요약: chunk 조회 → Gemini 스트리밍 → token/done 이벤트 전송 → 캐시 저장")
    void streamAiSummary_success() throws Exception {
        String query = "스프링 부트 JPA";
        String cacheKey = "ai-summary:" + query.toLowerCase().trim();

        when(cacheManager.getCache("aiSummary")).thenReturn(cache);
        when(cache.get(eq(cacheKey), eq(AiSummaryCacheDto.class))).thenReturn(null);
        when(articleSearchService.getTopArticleIdsByHybrid(anyString(), anyInt()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(1L), null));
        when(vectorSearchService.getChunksForArticlesByIds(anyString(), anyList(), any()))
                .thenReturn(List.of(new AiSummaryChunkDto(1L, "JPA 성능 최적화", "https://example.com/1", "JPA 성능 관련 내용", null, "테스트기업", null)));

        Stream<String> geminiStream = Stream.of(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"스프링 부트에서는 [출처1]을 활용합니다.\"}]}}]}",
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" [QUERIES]{\\\"queries\\\":[\\\"JPA N+1\\\",\\\"영속성 컨텍스트\\\",\\\"Lazy Loading\\\"]}[/QUERIES]\"}]}}]}"
        );
        doReturn(geminiStream).when(aiSummaryService).callGeminiStream(anyString());

        aiSummaryService.streamAiSummary(query, emitter);

        // token 이벤트 + done 이벤트 최소 2회 send (토큰 분할 수에 따라 횟수 변동)
        verify(emitter, atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        // 캐시 저장 확인
        verify(cache).put(eq(cacheKey), any(AiSummaryCacheDto.class));

        // 신규 메트릭 기록 확인 (모의 스트림 2개 청크 → chunk size 2건, gap 1건)
        assertThat(meterRegistry.get("ai_summary_requests_total").tag("status", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ai_summary_latency_seconds").tag("status", "success").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_ttfb_seconds").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_duration_seconds").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_chunk_size_bytes").summary().count()).isEqualTo(2L);
        assertThat(meterRegistry.get("ai_summary_gemini_chunk_gap_seconds").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("캐시 히트: Gemini 미호출, 캐시된 텍스트를 SSE로 재생")
    void streamAiSummary_cacheHit() throws Exception {
        String query = "Redis 캐시";
        String cacheKey = "ai-summary:" + query.toLowerCase().trim();

        AiSummaryCacheDto cached = new AiSummaryCacheDto(
                "Redis는 인메모리 캐시입니다.",
                List.of(new AiSummarySourceDto(1L, "Redis 입문", "https://example.com/redis", null, "테스트기업", null)),
                List.of("Redis Cluster", "캐시 전략", "Eviction Policy")
        );

        when(cacheManager.getCache("aiSummary")).thenReturn(cache);
        when(cache.get(eq(cacheKey), eq(AiSummaryCacheDto.class))).thenReturn(cached);

        aiSummaryService.streamAiSummary(query, emitter);

        // Gemini 미호출
        verify(aiSummaryService, never()).callGeminiStream(anyString());
        // 하이브리드 검색 / chunk 조회 미호출
        verify(articleSearchService, never()).getTopArticleIdsByHybrid(anyString(), anyInt());
        verify(vectorSearchService, never()).getChunksForArticlesByIds(anyString(), anyList(), any());
        // token + done 이벤트 전송 후 complete
        verify(emitter, atLeast(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();

        // 캐시 히트는 Gemini를 호출하지 않으므로 Gemini 전용 메트릭은 기록되지 않아야 함 (미리 등록만 돼 있어 count=0)
        assertThat(meterRegistry.get("ai_summary_requests_total").tag("status", "cached").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ai_summary_latency_seconds").tag("status", "cached").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_ttfb_seconds").timer().count()).isZero();
    }

    @Test
    @DisplayName("Gemini 타임아웃: error 이벤트 전송 후 SSE 종료")
    void streamAiSummary_geminiTimeout() throws Exception {
        String query = "Docker 배포";

        when(cacheManager.getCache("aiSummary")).thenReturn(cache);
        when(cache.get(any(), eq(AiSummaryCacheDto.class))).thenReturn(null);
        when(articleSearchService.getTopArticleIdsByHybrid(anyString(), anyInt()))
                .thenReturn(new ArticleSearchService.HybridTopArticles(List.of(1L), null));
        when(vectorSearchService.getChunksForArticlesByIds(anyString(), anyList(), any()))
                .thenReturn(List.of(new AiSummaryChunkDto(1L, "Docker 튜토리얼", "https://example.com/docker", "Docker 컨테이너 기본 설명", null, "테스트기업", null)));

        doThrow(new HttpTimeoutException("Connection timed out"))
                .when(aiSummaryService).callGeminiStream(anyString());

        aiSummaryService.streamAiSummary(query, emitter);

        // sources 이벤트(1회) + error 이벤트(1회) 전송 후 complete
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        // 캐시 저장 미호출
        verify(cache, never()).put(anyString(), any());

        // finally 블록은 타임아웃에도 실행되므로 duration은 기록되지만, 청크가 한 번도 도착하지 않아 TTFB는 미기록
        assertThat(meterRegistry.get("ai_summary_requests_total").tag("status", "failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ai_summary_latency_seconds").tag("status", "failure").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_duration_seconds").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_ttfb_seconds").timer().count()).isZero();
    }

    @Test
    @DisplayName("chunk 없음: error 이벤트 전송 후 SSE 종료")
    void streamAiSummary_noChunksFound() throws Exception {
        String query = "존재하지않는쿼리";

        when(cacheManager.getCache("aiSummary")).thenReturn(cache);
        when(cache.get(any(), eq(AiSummaryCacheDto.class))).thenReturn(null);
        when(articleSearchService.getTopArticleIdsByHybrid(anyString(), anyInt()))
                .thenReturn(ArticleSearchService.HybridTopArticles.empty());
        when(vectorSearchService.getChunksForArticlesByIds(anyString(), anyList(), any()))
                .thenReturn(List.of());

        aiSummaryService.streamAiSummary(query, emitter);

        // error 이벤트 1회 전송 후 complete
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        // Gemini 미호출
        verify(aiSummaryService, never()).callGeminiStream(anyString());

        // chunk 없음 경로도 failure latency는 기록되지만, Gemini를 호출하지 않았으므로 Gemini 전용 메트릭은 미기록
        assertThat(meterRegistry.get("ai_summary_latency_seconds").tag("status", "failure").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("ai_summary_gemini_duration_seconds").timer().count()).isZero();
        assertThat(meterRegistry.get("ai_summary_gemini_ttfb_seconds").timer().count()).isZero();
    }

    @Test
    @DisplayName("예상치 못한 예외: outer catch에서도 failure latency가 기록되어야 함")
    void streamAiSummary_unexpectedException_recordsFailureLatency() throws Exception {
        String query = "예외 발생 쿼리";

        when(cacheManager.getCache("aiSummary")).thenReturn(cache);
        when(cache.get(any(), eq(AiSummaryCacheDto.class))).thenReturn(null);
        when(articleSearchService.getTopArticleIdsByHybrid(anyString(), anyInt()))
                .thenThrow(new RuntimeException("예상치 못한 오류"));

        aiSummaryService.streamAiSummary(query, emitter);

        verify(emitter).complete();
        assertThat(meterRegistry.get("ai_summary_requests_total").tag("status", "failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("ai_summary_latency_seconds").tag("status", "failure").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("빈 쿼리: 즉시 error 이벤트 전송 후 SSE 종료, Gemini 미호출")
    void streamAiSummary_emptyQuery() throws Exception {
        aiSummaryService.streamAiSummary("   ", emitter);

        // error 이벤트 1회 전송 후 complete
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        // 하이브리드 검색 / chunk 조회 / Gemini 미호출
        verify(articleSearchService, never()).getTopArticleIdsByHybrid(anyString(), anyInt());
        verify(vectorSearchService, never()).getChunksForArticlesByIds(anyString(), anyList(), any());
        verify(aiSummaryService, never()).callGeminiStream(anyString());

        // 빈 쿼리는 try 블록 진입 전에 반환되므로 Gemini 전용 메트릭 미기록
        assertThat(meterRegistry.get("ai_summary_gemini_duration_seconds").timer().count()).isZero();
        assertThat(meterRegistry.get("ai_summary_gemini_ttfb_seconds").timer().count()).isZero();
    }

    @Test
    @DisplayName("추천 검색어: 설정값에서 파싱하여 반환")
    void getRecommendedQueries_returnsConfiguredList() {
        List<String> result = aiSummaryService.getRecommendedQueries();

        assertThat(result).containsExactly("Spring Boot", "Redis 캐시", "JPA");
    }
}
