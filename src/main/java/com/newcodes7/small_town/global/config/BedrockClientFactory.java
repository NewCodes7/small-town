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

    // Gemini 호출 타임아웃 임시 5분 상향(2026-07-09)과 맞춘 값
    private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(5);

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
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build());
    }

    public BedrockRuntimeAsyncClient async(String region) {
        return asyncClients.computeIfAbsent(region, r -> BedrockRuntimeAsyncClient.builder()
                .region(Region.of(r))
                .credentialsProvider(credentials())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
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
