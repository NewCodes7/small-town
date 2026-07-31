import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;

/**
 * Clova Embedding v2 mock — POST /v1/api-tools/embedding/v2.
 *
 * EmbeddingApiService가 읽는 result.embedding(1024차원)을 반환한다.
 * 벡터는 SHA-256(text) 시드 기반 결정적 생성 + L2 정규화 — 같은 쿼리는 항상 같은 벡터라
 * search_query_embedding 캐시·유사도 재현성이 유지된다.
 */
public final class ClovaHandler implements HttpHandler {

    private static final int DIMENSION = 1024;

    private final MockConfig config;

    public ClovaHandler(MockConfig config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String text = MiniJson.stringAt(MiniJson.parse(body), "text");
        if (text == null) {
            text = "";
        }

        LatencyModel.sleep(LatencyModel.lognormalMs(config.embedMedianMs(), config.embedSigma()));

        String response = "{\"status\":{\"code\":\"20000\",\"message\":\"OK\"},\"result\":{\"embedding\":"
                + vectorJson(text) + ",\"inputTokens\":" + Math.max(1, text.length() / 4) + "}}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        System.out.printf("[mock-llm] POST /v1/api-tools/embedding/v2 -> 200 (%dms)%n",
                (System.nanoTime() - start) / 1_000_000);
    }

    private String vectorJson(String text) {
        SplittableRandom random = new SplittableRandom(seedOf(text));
        double[] vector = new double[DIMENSION];
        double normSquared = 0;
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = random.nextDouble(-1, 1);
            normSquared += vector[i] * vector[i];
        }
        double norm = Math.sqrt(normSquared);
        StringBuilder sb = new StringBuilder(DIMENSION * 11);
        sb.append('[');
        for (int i = 0; i < DIMENSION; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("%.6f", vector[i] / norm));
        }
        sb.append(']');
        return sb.toString();
    }

    private long seedOf(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
