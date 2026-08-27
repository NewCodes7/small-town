package com.newcodes7.small_town.global.config;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
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

    /**
     * ConverseStream(답변 생성) async 클라이언트의 동시 요청 상한. 기본 300.
     *
     * <p>설정하지 않으면 AWS SDK 기본값 <b>50</b>(SdkHttpConfigurationOption.DEFAULT_MAX_CONNECTIONS)이
     * 그대로 RAG의 동시 답변 스트림 천장이 된다. 2026-08-27 사다리에서 이게 실측으로 드러났다:
     * VU45(동시 45 &lt; 50)는 에러 0인데 VU70(동시 70 &gt; 50)에서 first_token p95가 +8,108ms 뛰고
     * 9.5%가 "Acquire operation took longer than the configured maximum time"로 실패했다.
     * 그 시점 DB CPU 31% / app CPU 0.19로 <b>다른 자원은 전부 한가했다</b> —
     * 하드웨어가 아니라 설정한 적 없는 기본값이 용량을 정하고 있었다.
     * (load-test/results/2026-08-27-rag-ladder.md 3.3)
     *
     * <p>300은 {@code server.tomcat.max-connections}와 맞춘 값이다. 근거는 "LLM 클라이언트가
     * 서버가 받아들이는 수보다 더 좁은 제약이 되어서는 안 된다"는 것이고, 반대로 <b>동시성을
     * 제한하고 싶다면 그건 SDK 기본값이 아니라 명시적인 유입 제어여야 한다</b> —
     * 검색이 SearchConcurrencyLimiter로 한 것처럼. RAG 유입 제어는 아직 없다(부채).
     *
     * <p>connectionAcquisitionTimeout(기본 10초)은 일부러 그대로 둔다. 풀이 다시 마르면
     * 위와 똑같은 명확한 에러로 드러나는 편이 조용히 느려지는 것보다 낫다.
     */
    @Value("${bedrock.async-max-concurrency:300}")
    private int asyncMaxConcurrency;

    private final Map<String, BedrockRuntimeClient> syncClients = new ConcurrentHashMap<>();
    private final Map<String, BedrockRuntimeAsyncClient> asyncClients = new ConcurrentHashMap<>();

    public String defaultRegion() {
        return defaultRegion;
    }

    public BedrockRuntimeClient sync(String region) {
        return sync(region, null);
    }

    public BedrockRuntimeAsyncClient async(String region) {
        return async(region, null);
    }

    /**
     * endpointOverride가 있으면 실 AWS 대신 해당 URL(부하테스트 mock server)로 호출한다.
     * null/blank면 기존과 동일하게 리전 기본 엔드포인트를 쓴다.
     */
    public BedrockRuntimeClient sync(String region, String endpointOverride) {
        return syncClients.computeIfAbsent(cacheKey(region, endpointOverride), key -> {
            var builder = BedrockRuntimeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(credentials())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(SYNC_API_CALL_TIMEOUT)
                            .build());
            if (hasText(endpointOverride)) {
                builder.endpointOverride(URI.create(endpointOverride));
            }
            return builder.build();
        });
    }

    public BedrockRuntimeAsyncClient async(String region, String endpointOverride) {
        return asyncClients.computeIfAbsent(cacheKey(region, endpointOverride), key -> {
            var builder = BedrockRuntimeAsyncClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(credentials())
                    .overrideConfiguration(ClientOverrideConfiguration.builder()
                            .apiCallTimeout(ASYNC_API_CALL_TIMEOUT)
                            .build());
            // maxConcurrency는 두 경로 모두에 건다 — 부하테스트만 올리면 운영에 없는 천장을 재게 된다.
            // protocol은 mock 경로에서만 명시한다: buildWithDefaults가
            // standardOptions.merge(serviceDefaults)라 명시하지 않은 옵션은 서비스 기본값이 이긴다.
            // BedrockRuntime의 serviceHttpConfig()는 PROTOCOL=HTTP2/ALPN이므로 실 AWS 경로는 h2 유지.
            var httpClient = NettyNioAsyncHttpClient.builder().maxConcurrency(asyncMaxConcurrency);
            if (hasText(endpointOverride)) {
                // mock server(JDK HttpServer)는 h2 미지원 — 이 경로만 HTTP/1.1로 강제.
                builder.endpointOverride(URI.create(endpointOverride));
                httpClient.protocol(Protocol.HTTP1_1);
            }
            return builder.httpClientBuilder(httpClient).build();
        });
    }

    private String cacheKey(String region, String endpointOverride) {
        return region + "|" + (hasText(endpointOverride) ? endpointOverride : "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
