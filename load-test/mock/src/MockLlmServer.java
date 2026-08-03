import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 부하테스트용 LLM API mock server.
 *
 * Bedrock Converse(전처리 JSON) / ConverseStream(AWS eventstream wire 포맷) / Clova Embedding v2를
 * 실제 응답 형식 그대로 재현한다 — 백엔드는 운영과 동일한 BedrockRagLlmClient(netty async)·
 * EmbeddingApiService 경로로 호출하므로 부하 계측 정합성이 유지된다.
 *
 * 의존성 0: JDK HttpServer + virtual thread. 지연(lognormal)·장애 주입은 env로 조절 (MockConfig 참고).
 * SigV4 서명은 검증하지 않는다 (내부 네트워크 전용 — docker compose에서 호스트 포트 미공개).
 */
public final class MockLlmServer {

    public static void main(String[] args) throws IOException {
        MockConfig config = MockConfig.fromEnv();
        // backlog 명시(0=시스템 기본, 보통 50) — VUS 급증 시 accept 큐 지연 방지
        HttpServer server = HttpServer.create(new InetSocketAddress(config.port()), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/model", new BedrockHandlers(config)); // /model/{id}/converse[-stream]
        server.createContext("/v1/api-tools/embedding/v2", new ClovaHandler(config));
        server.createContext("/healthz", MockLlmServer::health);

        server.start();
        System.out.println("[mock-llm] listening on :" + config.port());
        System.out.println("[mock-llm] " + config);
    }

    private static void health(HttpExchange exchange) throws IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
