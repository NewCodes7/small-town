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
     * ConverseStream(답변 생성) async 클라이언트의 동시 요청 상한. <b>기본 50 — 올리지 말 것.</b>
     *
     * <p>이 값을 명시하지 않으면 AWS SDK 기본값 50
     * ({@code SdkHttpConfigurationOption.DEFAULT_MAX_CONNECTIONS})이 걸린다. 2026-08-27 사다리에서
     * 그 50이 RAG 동시 답변 스트림의 실질 천장임이 드러났고(VU45 에러 0 / VU70 first_token p95
     * +8,108ms·에러 9.5%), 그때 DB CPU는 31%뿐이라 "하드웨어가 아니라 설정한 적 없는 기본값이
     * 용량을 정하고 있다"는 판단으로 300(= server.tomcat.max-connections)으로 올려 재측정했다.
     *
     * <p><b>그 실험은 실패했고, 그래서 이 값은 다시 50이다.</b> 300으로 올린 채 VU 45/90/140/190을
     * 돌리자 VU90부터 무너져 <b>백엔드가 12분간 세 번 OOM으로 죽고 재기동</b>했다
     * (힙 used 506 / committed 512 MB, {@code -XX:+ExitOnOutOfMemoryError}).
     * DB CPU는 오히려 0.60 → 0.28로 <b>떨어졌다</b> — 일이 실행된 게 아니라 큐에 쌓인 congestion
     * collapse다. HikariCP pending 34 → 129, 획득 대기 0.19s → 1.10s.
     *
     * <p>즉 <b>SDK 기본값 50은 우연히 벌크헤드 역할을 하고 있었다.</b> 이 경로의 진짜 다음 한계는
     * DB CPU가 아니라 <b>JVM 힙 512MB</b>이고, 파국적으로(OOM → 재기동) 무너진다.
     *
     * <p>올리기 전에 반드시 선행되어야 하는 것:
     * <ol>
     *   <li><b>RAG 유입 제어</b> — 검색의 {@code SearchConcurrencyLimiter}처럼 상한 초과를 429로
     *       거절하는 명시적 장치. 현재 RAG에는 없다(부채). 상한을 올린다는 건 그 뒤에 받아줄
     *       장치가 있다는 뜻이어야 한다</li>
     *   <li><b>힙 예산 재산정</b> — 512는 검색 부하 기준으로 산정된 값이다
     *       (load-test/results/2026-08-20-jvm-heap-sizing.md). 동시 스트림 N개가 얼마를 쓰는지
     *       측정하지 않은 채 동시성만 올리면 같은 결과가 난다</li>
     * </ol>
     *
     * <p>근거 전문: load-test/results/2026-08-27-rag-ladder.md 3.3 · 5장
     */
    @Value("${bedrock.async-max-concurrency:50}")
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
