package com.newcodes7.small_town.global.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * AWS Bedrock Runtime 클라이언트 (admin RAG 테스트용 LLM)
 *
 * S3(AwsS3Config, SDK v1)와 같은 자격증명을 쓰되 SDK v2로 구성한다.
 * sync 클라이언트는 Converse(전처리), async 클라이언트는 ConverseStream(답변 스트리밍)에 사용.
 */
@Configuration
public class BedrockConfig {

    // Gemini 호출 타임아웃 임시 5분 상향(2026-07-09)과 맞춘 값
    private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(5);

    @Value("${bedrock.access-key}")
    private String accessKey;

    @Value("${bedrock.secret-key}")
    private String secretKey;

    @Value("${bedrock.region}")
    private String region;

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient() {
        return BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build();
    }

    @Bean
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient() {
        return BedrockRuntimeAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build();
    }
}
