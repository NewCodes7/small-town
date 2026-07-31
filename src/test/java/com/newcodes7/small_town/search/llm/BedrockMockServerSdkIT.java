package com.newcodes7.small_town.search.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * load-test/mock/ mock server의 wire 포맷을 실제 AWS SDK로 검증하는 통합 테스트.
 *
 * ConverseStream의 AWS eventstream 바이너리 프레이밍(prelude/헤더 인코딩/CRC32)은
 * 실 SDK 언마샬러를 통과시키는 것 외에 검증 수단이 없다 — EventStreamCodec 수정 시 반드시 이 테스트로 확인.
 *
 * 실행 (mock server 기동 후):
 *   MOCK_LLM_URL=http://localhost:9099 ./gradlew test --tests "*BedrockMockServerSdkIT*"
 * env 미설정 시 스킵되므로 CI·훅 전체 테스트에는 영향 없다.
 */
@EnabledIfEnvironmentVariable(named = "MOCK_LLM_URL", matches = ".+")
class BedrockMockServerSdkIT {

    private static final String MOCK_URL = System.getenv("MOCK_LLM_URL");
    private static final String MODEL_ID = "mock.anthropic.claude-sonnet-4-5";

    private static StaticCredentialsProvider mockCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("mock", "mock"));
    }

    private static Message userMessage(String text) {
        return Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(text))
                .build();
    }

    @Test
    @DisplayName("converse: mock의 전처리 JSON envelope를 실 SDK가 파싱한다")
    void converse_parsedByRealSdk() {
        try (BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create(MOCK_URL))
                .credentialsProvider(mockCredentials())
                .build()) {

            ConverseResponse response = client.converse(ConverseRequest.builder()
                    .modelId(MODEL_ID)
                    .system(SystemContentBlock.fromText("시스템"))
                    .messages(userMessage("MSA 전환 사례 알려줘 (test:abc-123)"))
                    .build());

            String text = response.output().message().content().get(0).text();
            assertThat(text).contains("\"vectorQuery\"").contains("(test:abc-123)");
            assertThat(text).contains("\"keywords\"").doesNotContain("keywords\": \"MSA 전환 사례 알려줘 (test");
            assertThat(response.usage().totalTokens()).isPositive();
        }
    }

    @Test
    @DisplayName("converseStream: mock의 eventstream 프레임(CRC 포함)을 실 SDK 언마샬러가 끝까지 소진한다")
    void converseStream_unmarshalledByRealSdk() {
        // JDK HttpServer는 h2 미지원 — BedrockClientFactory도 mock endpoint에는 동일하게 HTTP/1.1을 강제한다
        try (BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(URI.create(MOCK_URL))
                .credentialsProvider(mockCredentials())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1))
                .build()) {

            StringBuilder answer = new StringBuilder();
            AtomicReference<TokenUsage> usage = new AtomicReference<>();
            ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                    .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                            .onContentBlockDelta(event -> answer.append(event.delta().text()))
                            .onMetadata(event -> usage.set(event.usage()))
                            .build())
                    .build();

            client.converseStream(ConverseStreamRequest.builder()
                    .modelId(MODEL_ID)
                    .system(SystemContentBlock.fromText("시스템"))
                    .messages(userMessage("질문"))
                    .build(), handler).join();

            // RagAnswerService 파싱 경로가 의존하는 마커들이 스트림 재조립 결과에 온전히 있어야 한다
            assertThat(answer.toString())
                    .contains("[QUERIES]")
                    .contains("[/QUERIES]")
                    .contains("[출처");
            assertThat(usage.get()).isNotNull();
            assertThat(usage.get().outputTokens()).isPositive();
        }
    }
}
