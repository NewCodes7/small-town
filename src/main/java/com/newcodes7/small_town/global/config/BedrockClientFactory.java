package com.newcodes7.small_town.global.config;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * AWS Bedrock Runtime 클라이언트 팩토리 (admin RAG 테스트용 LLM)
 *
 * 모델마다 호출 가능한 소스 리전이 달라(global 프로파일 vs US geo 프로파일 vs In-Region 전용)
 * 리전별 클라이언트를 lazy 생성·캐싱한다. S3(AwsS3Config, SDK v1)와 같은 자격증명을 쓰되 SDK v2로 구성.
 * sync 클라이언트는 Converse(전처리), async 클라이언트는 ConverseStream(답변 스트리밍)에 사용.
 */
@Component
public class BedrockClientFactory {

    // Bedrock Converse/ConverseStream 자체는 지연 이슈가 보고된 적 없어 Gemini와 달리 짧게 유지.
    // apiCallTimeout은 스트리밍 API에서도 요청 시작~완료(스트림 소진)까지 전체 구간에 적용되므로
    // sync(전처리, PREPROCESS_MAX_TOKENS=500)와 async(답변 생성, ANSWER_MAX_TOKENS=2000)를 분리—
    // 하나로 묶으면 답변 생성이 길어질 때 정상 진행 중인 스트림도 SdkClientException으로 끊긴다.
    // RagChatController.SSE_TIMEOUT_MS·nginx proxy_read_timeout은 이 둘의 합보다 여유 있게 잡아야 한다.
    private static final Duration SYNC_API_CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ASYNC_API_CALL_TIMEOUT = Duration.ofSeconds(90);

    @Value("${bedrock.access-key}")
    private String accessKey;

    @Value("${bedrock.secret-key}")
    private String secretKey;

    @Value("${bedrock.region}")
    private String defaultRegion;

    private final Map<String, BedrockRuntimeClient> syncClients = new ConcurrentHashMap<>();
    private final Map<String, BedrockRuntimeAsyncClient> asyncClients = new ConcurrentHashMap<>();

    public String defaultRegion() {
        return defaultRegion;
    }

    public BedrockRuntimeClient sync(String region) {
        return syncClients.computeIfAbsent(region, r -> BedrockRuntimeClient.builder()
                .region(Region.of(r))
                .credentialsProvider(credentials())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(SYNC_API_CALL_TIMEOUT)
                        .build())
                .build());
    }

    public BedrockRuntimeAsyncClient async(String region) {
        return asyncClients.computeIfAbsent(region, r -> BedrockRuntimeAsyncClient.builder()
                .region(Region.of(r))
                .credentialsProvider(credentials())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(ASYNC_API_CALL_TIMEOUT)
                        .build())
                .build());
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    @PreDestroy
    public void close() {
        syncClients.values().forEach(BedrockRuntimeClient::close);
        asyncClients.values().forEach(BedrockRuntimeAsyncClient::close);
    }
}
