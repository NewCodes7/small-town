package com.newcodes7.small_town.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import com.newcodes7.small_town.search.service.RagAnswerService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminRagControllerTest {

    @Mock private RagAnswerService ragAnswerService;
    @Mock private ExecutorService searchExecutor;

    private AdminRagController controller;

    @BeforeEach
    void setUp() {
        RagModelProperties properties = new RagModelProperties();
        properties.setModels(List.of(
                modelOption("gemini-3.5-flash", "Gemini 3.5 Flash", Provider.GEMINI),
                modelOption("apac.amazon.nova-lite-v1:0", "Amazon Nova Lite (Bedrock)", Provider.BEDROCK)));
        controller = new AdminRagController(ragAnswerService, properties, searchExecutor);
    }

    private ModelOption modelOption(String id, String label, Provider provider) {
        ModelOption model = new ModelOption();
        model.setId(id);
        model.setLabel(label);
        model.setProvider(provider);
        return model;
    }

    private void runExecutorInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(searchExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("model 미지정: 목록 0번(기본 모델)으로 streamAnswer 호출")
    void answer_defaultModel() {
        runExecutorInline();

        controller.answer("Kafka 사례", 5, 3, 0.6, null);

        ArgumentCaptor<ModelOption> captor = ArgumentCaptor.forClass(ModelOption.class);
        verify(ragAnswerService).streamAnswer(
                anyString(), anyInt(), anyInt(), anyDouble(), captor.capture(), any());
        assertThat(captor.getValue().getId()).isEqualTo("gemini-3.5-flash");
    }

    @Test
    @DisplayName("model 지정: 해당 모델로 streamAnswer 호출")
    void answer_selectedModel() {
        runExecutorInline();

        controller.answer("Kafka 사례", 5, 3, 0.6, "apac.amazon.nova-lite-v1:0");

        ArgumentCaptor<ModelOption> captor = ArgumentCaptor.forClass(ModelOption.class);
        verify(ragAnswerService).streamAnswer(
                anyString(), anyInt(), anyInt(), anyDouble(), captor.capture(), any());
        assertThat(captor.getValue().getProvider()).isEqualTo(Provider.BEDROCK);
    }

    @Test
    @DisplayName("알 수 없는 model: 400 ResponseStatusException")
    void answer_unknownModel() {
        assertThatThrownBy(() -> controller.answer("Kafka 사례", 5, 3, 0.6, "unknown-model"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
