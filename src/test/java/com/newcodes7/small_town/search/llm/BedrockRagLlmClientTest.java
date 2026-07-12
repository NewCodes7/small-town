package com.newcodes7.small_town.search.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.global.config.BedrockClientFactory;
import com.newcodes7.small_town.search.config.RagModelProperties;
import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

@ExtendWith(MockitoExtension.class)
class BedrockRagLlmClientTest {

    @Mock private BedrockClientFactory bedrockClientFactory;
    @Mock private BedrockRuntimeClient bedrockRuntimeClient;
    @Mock private BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;

    private final RagModelProperties ragModelProperties = new RagModelProperties();
    private BedrockRagLlmClient client;

    private static final String DEFAULT_REGION = "ap-northeast-2";
    private static final String MODEL_ID = "global.anthropic.claude-sonnet-4-5-20250929-v1:0";
    private static final JsonOutputSpec OUTPUT_SPEC =
            new JsonOutputSpec(Map.of(), "반드시 JSON 객체 하나만 출력하세요");
    private static final LlmOptions OPTIONS = new LlmOptions(0.0, 500);

    @BeforeEach
    void setUp() {
        lenient().when(bedrockClientFactory.defaultRegion()).thenReturn(DEFAULT_REGION);
        lenient().when(bedrockClientFactory.sync(DEFAULT_REGION)).thenReturn(bedrockRuntimeClient);
        lenient().when(bedrockClientFactory.async(DEFAULT_REGION)).thenReturn(bedrockRuntimeAsyncClient);
        client = new BedrockRagLlmClient(bedrockClientFactory, ragModelProperties);
    }

    private ConverseResponse converseResponse(String text) {
        return ConverseResponse.builder()
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(text))
                        .build()))
                .usage(TokenUsage.builder().inputTokens(10).outputTokens(5).totalTokens(15).build())
                .build();
    }

    @Test
    @DisplayName("generateJson: modelId·JSON 지시 시스템 프롬프트·inference 옵션으로 Converse 호출, 원문+usage 반환")
    void generateJson_success() throws Exception {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(converseResponse("{\"keywords\":\"Kafka\"}"));

        LlmJsonResult result = client.generateJson(
                MODEL_ID, "시스템 프롬프트", "질문입니다", OUTPUT_SPEC, OPTIONS);

        assertThat(result.json()).isEqualTo("{\"keywords\":\"Kafka\"}");
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(result.usage().outputTokens()).isEqualTo(5);
        assertThat(result.usage().totalTokens()).isEqualTo(15);

        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(bedrockRuntimeClient).converse(captor.capture());
        ConverseRequest request = captor.getValue();
        assertThat(request.modelId()).isEqualTo(MODEL_ID);
        assertThat(request.system().get(0).text())
                .contains("시스템 프롬프트")
                .contains("반드시 JSON 객체 하나만 출력하세요");
        assertThat(request.messages().get(0).content().get(0).text()).isEqualTo("질문입니다");
        assertThat(request.inferenceConfig().temperature()).isEqualTo(0.0f);
        assertThat(request.inferenceConfig().maxTokens()).isEqualTo(500);
    }

    @Test
    @DisplayName("generateJson: rag.models의 region 지정 모델은 해당 리전 클라이언트로 호출")
    void generateJson_usesModelRegion() throws Exception {
        String regionalModelId = "us.meta.llama4-maverick-17b-instruct-v1:0";
        ModelOption option = new ModelOption();
        option.setId(regionalModelId);
        option.setLabel("Llama 4 Maverick");
        option.setProvider(Provider.BEDROCK);
        option.setRegion("us-east-1");
        ragModelProperties.setModels(List.of(option));
        when(bedrockClientFactory.sync("us-east-1")).thenReturn(bedrockRuntimeClient);
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(converseResponse("{}"));

        client.generateJson(regionalModelId, "시스템", "질문", OUTPUT_SPEC, OPTIONS);

        verify(bedrockClientFactory).sync("us-east-1");
    }

    @Test
    @DisplayName("generateJson: AccessDeniedException → 권한 안내 RagLlmException으로 매핑")
    void generateJson_accessDenied() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenThrow(AccessDeniedException.builder().message("no access").build());

        assertThatThrownBy(() -> client.generateJson(
                MODEL_ID, "시스템", "질문", OUTPUT_SPEC, OPTIONS))
                .isInstanceOf(RagLlmException.class)
                .hasMessageContaining("권한");
    }

    @Test
    @DisplayName("generateJson: ValidationException → inference profile 안내 RagLlmException으로 매핑")
    void generateJson_validationError() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenThrow(ValidationException.builder().message("on-demand not supported").build());

        assertThatThrownBy(() -> client.generateJson(
                MODEL_ID, "시스템", "질문", OUTPUT_SPEC, OPTIONS))
                .isInstanceOf(RagLlmException.class)
                .hasMessageContaining("inference profile");
    }

    @Test
    @DisplayName("generateStream: 실패 future의 CompletionException을 unwrap하여 매핑")
    void generateStream_unwrapsCompletionException() {
        when(bedrockRuntimeAsyncClient.converseStream(
                any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        AccessDeniedException.builder().message("no access").build()));

        assertThatThrownBy(() -> client.generateStream(
                MODEL_ID, "시스템", "질문", OPTIONS, text -> {}))
                .isInstanceOf(RagLlmException.class)
                .hasMessageContaining("권한");
    }
}
