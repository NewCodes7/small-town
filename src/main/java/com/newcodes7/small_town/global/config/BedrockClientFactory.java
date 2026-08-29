package com.newcodes7.small_town.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
     * <p><b>선행조건 두 가지는 이후 충족됐다:</b>
     * <ol>
     *   <li><b>힙 예산</b> — eventstream 디코더 버퍼를 2 MiB → 32 KiB로 줄여 스트림당 live 힙이
     *       3.60 → 1.87 MB가 됐다(10장). OOM 임계가 동시 90 → 약 180으로 밀려났다
     *       ({@code live = 166 + N × 1.87 MB}).</li>
     *   <li><b>RAG 유입 제어</b> — {@code RagConcurrencyLimiter}(상한 45, 초과 시 429)가 생겼다(11장).</li>
     * </ol>
     *
     * <p><b>그래도 이 값은 여전히 50이다.</b> 올리는 것 자체가 새 사다리를 요구하고, 무엇보다
     * <b>리미터 상한이 이 값보다 낮아야</b> 초과분이 "풀 앞의 조용한 대기"가 아니라 429로 나간다.
     * permit이 컨트롤러 진입부터 스트림 종료까지 유지되므로
     * {@code rag_concurrency_in_use ≥ rag_answer_in_flight ≥ rag_answer_llm_stream_in_flight}이고,
     * 따라서 리미터 상한 L을 걸면 {@code llm_stream ≤ L}이 보장된다 — 45 &lt; 50이 그 보장을 만든다.
     * 이 값을 올릴 거면 리미터 상한도 함께 올릴 것(admin {@code /admin/search/weights}, 재배포 불필요).
     *
     * <p>근거 전문: load-test/results/2026-08-27-rag-ladder.md 3.3 · 5 · 10 · 11장
     */
    @Value("${bedrock.async-max-concurrency:50}")
    private int asyncMaxConcurrency;

    private final MeterRegistry meterRegistry;

    public BedrockClientFactory(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * async 풀 상한을 지표로 노출한다 — 대시보드에서 "지금 몇 개가 대기 중인가"를 유도하려면
     * 상한이 지표로 있어야 프로퍼티를 바꿔도 패널이 안 깨진다.
     * 검색의 {@code search_concurrency_limit}(SearchConcurrencyLimiter)과 같은 역할이고,
     * RAG 쪽 짝은 {@code rag_concurrency_limit}(RagConcurrencyLimiter)이다 — 대시보드는 둘을 겹쳐 그린다.
     *
     * <p>풀 정의상 다음이 성립한다 (2026-08-27 실측으로 확인, 결과 문서 8.5):
     * <pre>
     * 스트리밍 중 = min(rag_answer_llm_stream_in_flight, 이 값)
     * 대기 중     = max(0, rag_answer_llm_stream_in_flight - 이 값)
     * </pre>
     * {@code rag_answer_llm_stream_in_flight}는 generateStream() 호출 직전에 증가하는데 커넥션
     * 획득은 그 호출 안에서 일어나므로 <b>대기 중인 요청도 포함</b>하기 때문이다.
     */
    @PostConstruct
    void registerMetrics() {
        Gauge.builder("rag_answer_llm_max_concurrency", this, f -> f.asyncMaxConcurrency)
                .description("Configured maxConcurrency of the Bedrock async client "
                        + "(the ceiling on concurrent RAG answer streams)")
                .register(meterRegistry);
    }

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
