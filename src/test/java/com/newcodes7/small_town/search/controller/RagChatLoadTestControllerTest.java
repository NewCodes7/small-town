package com.newcodes7.small_town.search.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import com.newcodes7.small_town.search.dto.RagChatRequestDto;
import com.newcodes7.small_town.search.service.RagAnswerService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class RagChatLoadTestControllerTest {

    @Mock private RagAnswerService ragAnswerService;
    @Mock private HttpServletRequest request;

    private final RagModelProperties ragModelProperties = new RagModelProperties();
    private ExecutorService searchExecutor;
    private RagChatLoadTestController controller;

    private static final String ANSWER_MODEL_ID = "mock.anthropic.claude-sonnet-4-5";
    private static final String PREPROCESS_MODEL_ID = "mock.anthropic.claude-haiku-4-5";

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newSingleThreadExecutor();
        controller = new RagChatLoadTestController(ragAnswerService, ragModelProperties, searchExecutor);
        ReflectionTestUtils.setField(controller, "loadTestEnabled", true);
        ReflectionTestUtils.setField(controller, "answerModelId", ANSWER_MODEL_ID);
        ReflectionTestUtils.setField(controller, "preprocessModelId", PREPROCESS_MODEL_ID);
        ragModelProperties.setModels(List.of(
                mockModel(ANSWER_MODEL_ID, "http://llm-mock:9099"),
                mockModel(PREPROCESS_MODEL_ID, "http://llm-mock:9099")));
        lenient().when(request.getHeader(anyString())).thenReturn(null);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
    }

    private ModelOption mockModel(String id, String endpoint) {
        ModelOption model = new ModelOption();
        model.setId(id);
        model.setLabel(id);
        model.setProvider(Provider.BEDROCK);
        model.setEndpoint(endpoint);
        model.setHidden(true);
        return model;
    }

    private RagChatRequestDto body() {
        return new RagChatRequestDto("Kafka 도입 사례", "conv-1");
    }

    @Test
    @DisplayName("게이트 비활성(기본값): 404로 존재를 숨긴다")
    void answer_disabled_returns404() {
        ReflectionTestUtils.setField(controller, "loadTestEnabled", false);

        assertThatThrownBy(() -> controller.answer(body(), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("mock endpoint 미설정 모델: 503 — 실 Bedrock 과금 호출로 새는 오설정 차단")
    void answer_modelWithoutEndpoint_returns503() {
        ragModelProperties.setModels(List.of(
                mockModel(ANSWER_MODEL_ID, null),
                mockModel(PREPROCESS_MODEL_ID, "http://llm-mock:9099")));

        assertThatThrownBy(() -> controller.answer(body(), request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("활성 상태: loadtest 전용 서비스 오버로드를 mock 모델로 호출")
    void answer_enabled_callsLoadTestOverloadWithMockModels() {
        SseEmitter emitter = controller.answer(body(), request);

        org.assertj.core.api.Assertions.assertThat(emitter).isNotNull();
        verify(ragAnswerService, timeout(2000)).streamAnswerForLoadTest(
                eq("Kafka 도입 사례"), anyInt(), anyInt(), anyDouble(),
                argThatModelId(ANSWER_MODEL_ID), argThatModelId(PREPROCESS_MODEL_ID),
                eq("conv-1"), anyString(), isNull(), any(SseEmitter.class));
    }

    private ModelOption argThatModelId(String id) {
        return org.mockito.ArgumentMatchers.argThat(model -> model != null && id.equals(model.getId()));
    }
}
