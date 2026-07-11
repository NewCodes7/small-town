package com.newcodes7.small_town.search.llm;

import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * AWS Bedrock 기반 RagLlmClient — Converse(전처리) / ConverseStream(답변 스트리밍).
 *
 * Converse API는 네이티브 JSON 스키마 응답이 없어 generateJson은 프롬프트 지시(jsonInstruction)로
 * JSON을 강제한다 — 호출측은 stripFences 후 파싱해야 한다.
 * ConverseStream의 onText 콜백은 netty 이벤트루프 스레드에서 실행된다 (admin 테스트 페이지 용도라 허용).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BedrockRagLlmClient implements RagLlmClient {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient;

    @Override
    public Provider provider() {
        return Provider.BEDROCK;
    }

    @Override
    public LlmJsonResult generateJson(
            String modelId, String systemPrompt, String userMessage,
            JsonOutputSpec outputSpec, LlmOptions options) throws RagLlmException {
        String systemWithJsonRule = systemPrompt + "\n\n" + outputSpec.jsonInstruction();
        try {
            ConverseResponse response = bedrockRuntimeClient.converse(
                    ConverseRequest.builder()
                            .modelId(modelId)
                            .system(SystemContentBlock.fromText(systemWithJsonRule))
                            .messages(userMessage(userMessage))
                            .inferenceConfig(inferenceConfig(options))
                            .build());
            String text = response.output().message().content().stream()
                    .map(ContentBlock::text)
                    .filter(t -> t != null && !t.isEmpty())
                    .reduce("", String::concat);
            if (text.isEmpty()) {
                throw new RagLlmException("Bedrock 응답이 비어 있습니다");
            }
            return new LlmJsonResult(text, toTokenUsage(response.usage()));
        } catch (RuntimeException e) {
            throw mapException(modelId, e);
        }
    }

    @Override
    public LlmTokenUsage generateStream(
            String modelId, String systemPrompt, String userMessage,
            LlmOptions options, Consumer<String> onText) throws RagLlmException {
        AtomicReference<LlmTokenUsage> usageRef = new AtomicReference<>(LlmTokenUsage.empty());
        ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                        .onContentBlockDelta(event -> {
                            String text = event.delta().text();
                            if (text != null && !text.isEmpty()) {
                                onText.accept(text);
                            }
                        })
                        .onMetadata(event -> usageRef.set(toTokenUsage(event.usage())))
                        .build())
                .build();
        try {
            bedrockRuntimeAsyncClient.converseStream(
                    ConverseStreamRequest.builder()
                            .modelId(modelId)
                            .system(SystemContentBlock.fromText(systemPrompt))
                            .messages(userMessage(userMessage))
                            .inferenceConfig(inferenceConfig(options))
                            .build(),
                    handler).join();
        } catch (RuntimeException e) {
            throw mapException(modelId, e);
        }
        return usageRef.get();
    }

    private Message userMessage(String userMessage) {
        return Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(userMessage))
                .build();
    }

    private InferenceConfiguration inferenceConfig(LlmOptions options) {
        return InferenceConfiguration.builder()
                .temperature((float) options.temperature())
                .maxTokens(options.maxTokens())
                .build();
    }

    private LlmTokenUsage toTokenUsage(TokenUsage usage) {
        if (usage == null) {
            return LlmTokenUsage.empty();
        }
        return new LlmTokenUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
    }

    private RagLlmException mapException(String modelId, RuntimeException e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        log.error("Bedrock 호출 실패 - modelId={}: {}", modelId, cause.getMessage(), cause);
        if (cause instanceof AccessDeniedException) {
            return new RagLlmException(
                    "Bedrock 모델 접근 권한이 없습니다 (IAM 권한·콘솔 모델 액세스 확인 필요)", cause);
        }
        if (cause instanceof ThrottlingException) {
            return new RagLlmException("Bedrock 요청이 제한되었습니다. 잠시 후 다시 시도해주세요", cause);
        }
        if (cause instanceof ValidationException) {
            return new RagLlmException(
                    "Bedrock 요청 검증 실패 (온디맨드 미지원 모델은 inference profile ID 필요)", cause);
        }
        return new RagLlmException("Bedrock 호출에 실패했습니다", cause);
    }
}
