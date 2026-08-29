package com.newcodes7.small_town.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import com.newcodes7.small_town.search.dto.RagChatRequestDto;
import com.newcodes7.small_town.search.repository.RagQueryLogRepository;
import com.newcodes7.small_town.search.service.RagAnswerService;
import com.newcodes7.small_town.search.service.RagConcurrencyLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실사용자 RAG 경로의 유입 제어 검증.
 *
 * <p>여기서 지켜야 하는 성질 세 가지:
 * <ol>
 *   <li>거절은 <b>SseEmitter 생성 전</b>에 나야 한다 — 만들고 나면 헤더가 200으로 나가 상태코드를 못 바꾼다</li>
 *   <li>permit은 <b>스트림이 끝난 뒤</b>에 반납돼야 한다 — 핸들러 반환 시점에 놓으면 정작 힙과
 *       Bedrock async 풀을 물고 있는 구간에 상한이 없어진다</li>
 *   <li>유입 제어는 <b>시간당 한도(DB COUNT)보다 먼저</b> 걸려야 한다 — 과부하분이 HikariCP(5) 앞에
 *       줄 서는 것이 런 3에서 관측된 붕괴 모양이다</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RagChatControllerTest {

    private static final String FIXED_MODEL_ID = "global.anthropic.claude-sonnet-4-5-20250929-v1:0";

    @Mock private RagAnswerService ragAnswerService;
    @Mock private RagConcurrencyLimiter ragConcurrencyLimiter;
    @Mock private UserRepository userRepository;
    @Mock private RagQueryLogRepository ragQueryLogRepository;
    @Mock private HttpServletRequest request;

    private final RagModelProperties ragModelProperties = new RagModelProperties();
    private MockHttpServletResponse response;
    private ExecutorService searchExecutor;
    private RagChatController controller;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newSingleThreadExecutor();
        response = new MockHttpServletResponse();
        controller = new RagChatController(
                ragAnswerService, ragConcurrencyLimiter, ragModelProperties,
                userRepository, ragQueryLogRepository, searchExecutor);
        ReflectionTestUtils.setField(controller, "preprocessModelId", "");
        ReflectionTestUtils.setField(controller, "hourlyLimitPerIp", 30);
        ReflectionTestUtils.setField(controller, "loadtestBypassToken", "");

        ModelOption model = new ModelOption();
        model.setId(FIXED_MODEL_ID);
        model.setLabel("sonnet");
        model.setProvider(Provider.BEDROCK);
        ragModelProperties.setModels(List.of(model));

        lenient().when(request.getHeader(anyString())).thenReturn(null);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(ragConcurrencyLimiter.tryAcquire()).thenReturn(true);
        lenient().when(ragQueryLogRepository.countByIpAddressAndCreatedAtAfter(anyString(), any()))
                .thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
    }

    private RagChatRequestDto body() {
        return new RagChatRequestDto("Kafka 도입 사례", "conv-1");
    }

    @Test
    @DisplayName("동시 실행 상한 초과: emitter를 만들기 전에 429 + Retry-After + RAG_BUSY")
    void overConcurrencyLimit_returns429BeforeEmitter() throws Exception {
        when(ragConcurrencyLimiter.tryAcquire()).thenReturn(false);

        SseEmitter emitter = controller.answer(body(), null, request, response);

        assertThat(emitter).as("emitter를 만들면 200 헤더가 나가 429를 보낼 수 없다").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentAsString()).contains("RAG_BUSY");

        verify(ragAnswerService, never()).streamAnswer(
                anyString(), anyInt(), anyInt(), anyDouble(), any(), any(),
                any(), any(), any(), any(SseEmitter.class));
        // permit을 잡지 않았으므로 반납도 하지 않는다 — 반납하면 상한이 늘어난다
        verify(ragConcurrencyLimiter, never()).release();
    }

    @Test
    @DisplayName("거절된 요청은 DB를 건드리지 않는다 — HikariCP(5) 앞에서 셰딩해야 의미가 있다")
    void rejectedRequest_neverTouchesDb() throws Exception {
        when(ragConcurrencyLimiter.tryAcquire()).thenReturn(false);

        controller.answer(body(), null, request, response);

        verify(ragQueryLogRepository, never())
                .countByIpAddressAndCreatedAtAfter(anyString(), any(LocalDateTime.class));
        verify(userRepository, never()).findByUsernameAndDeletedAtIsNull(anyString());
    }

    @Test
    @DisplayName("정상 처리: 스트림이 끝난 뒤에 permit을 반납한다 (핸들러 반환 시점이 아니라)")
    void normalPath_releasesPermitAfterStreamCompletes() throws Exception {
        SseEmitter emitter = controller.answer(body(), null, request, response);

        assertThat(emitter).isNotNull();
        verify(ragAnswerService, timeout(2000)).streamAnswer(
                anyString(), anyInt(), anyInt(), anyDouble(), any(), any(),
                anyString(), anyString(), any(), any(SseEmitter.class));
        verify(ragConcurrencyLimiter, timeout(2000)).release();
    }

    @Test
    @DisplayName("시간당 한도 초과: 429를 던지되 permit은 누수 없이 반납한다")
    void hourlyLimitExceeded_releasesPermit() {
        when(ragQueryLogRepository.countByIpAddressAndCreatedAtAfter(anyString(), any()))
                .thenReturn(30L);

        assertThatThrownBy(() -> controller.answer(body(), null, request, response))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(ragConcurrencyLimiter).release();
        verify(ragAnswerService, never()).streamAnswer(
                anyString(), anyInt(), anyInt(), anyDouble(), any(), any(),
                any(), any(), any(), any(SseEmitter.class));
    }

    @Test
    @DisplayName("모델 설정 누락으로 이탈해도 permit을 반납한다 — 누수 방지")
    void missingModel_releasesPermit() {
        ragModelProperties.setModels(List.of());

        assertThatThrownBy(() -> controller.answer(body(), null, request, response))
                .isInstanceOf(IllegalStateException.class);

        verify(ragConcurrencyLimiter).release();
    }
}
