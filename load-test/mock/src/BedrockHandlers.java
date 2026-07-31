import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bedrock Runtime mock — POST /model/{urlencoded-modelId}/converse | /converse-stream.
 *
 * converse: RagQueryPreprocessService의 JSON 스키마({corporations, keywords, vectorQuery})를
 *   그대로 재현한다. keywords에서는 k6 nonce "(test:uuid)"를 제거해 BM25 retrieval이 실제 아티클을
 *   찾게 하고(notfound 방지), vectorQuery에는 nonce를 유지해 임베딩 캐시 미스(실호출)를 재현한다.
 * converse-stream: AWS eventstream 프레임으로 messageStart → contentBlockDelta* → contentBlockStop
 *   → messageStop → metadata를 스트리밍한다. 본문 말미에 [QUERIES]{...}[/QUERIES] 태그를 포함해
 *   RagAnswerService의 holdback 파싱 경로를 태운다.
 */
public final class BedrockHandlers implements HttpHandler {

    // contentBlockDelta 1개당 문자 수 — QUERIES 태그가 자연스럽게 청크 경계에 걸치도록 작게 유지
    private static final int TOKEN_CHARS = 8;

    private static final String[] SENTENCE_POOL = {
            "이 기업은 대규모 트래픽 환경에서 **캐시 계층**을 도입해 응답 시간을 크게 단축했습니다 [출처1].",
            "초기에는 단일 DB 구성으로 시작했으나 조회 부하가 늘며 **읽기 복제본**을 분리했습니다 [출처2].",
            "배포 파이프라인에 **blue/green 전환**을 적용해 무중단 배포를 달성한 사례입니다 [출처3].",
            "성능 측정 결과 p95 레이턴시가 **40% 개선**되었고 에러율은 절반으로 줄었습니다 [출처1].",
            "메시지 큐 기반 **비동기 처리**로 피크 시간대의 요청 폭증을 흡수했다고 설명합니다 [출처4].",
            "장애 대응 과정에서 **서킷 브레이커** 패턴을 도입해 연쇄 장애를 차단했습니다 [출처5].",
            "인덱스 재설계와 쿼리 튜닝으로 슬로우 쿼리를 **10배** 빠르게 만들었습니다 [출처2].",
            "운영 지표는 **Prometheus/Grafana**로 수집하며 임계치 알림을 구성했습니다 [출처3].",
    };

    private static final String QUERIES_TAG =
            "\n\n[QUERIES]{\"queries\":[\"mock 부하테스트 검색어\",\"캐시 도입 사례\",\"서킷 브레이커 적용\"]}[/QUERIES]";

    private final MockConfig config;

    public BedrockHandlers(MockConfig config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        long start = System.nanoTime();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int status = 200;
        try {
            if (LatencyModel.hit(config.errorRate())) {
                status = 429;
                sendThrottlingError(exchange);
            } else if (path.endsWith("/converse-stream")) {
                converseStream(exchange, body);
            } else if (path.endsWith("/converse")) {
                converse(exchange, body);
            } else {
                status = 404;
                sendJson(exchange, 404, "{\"message\":\"unknown path: " + MiniJson.esc(path) + "\"}");
            }
        } catch (Exception e) {
            // 클라이언트가 스트림 도중 끊는 경우(k6 abort 등) 포함 — 로그만 남기고 종료
            System.out.println("[mock-llm] " + path + " 처리 중단: " + e);
        } finally {
            exchange.close();
            System.out.printf("[mock-llm] POST %s -> %d (%dms)%n",
                    path, status, (System.nanoTime() - start) / 1_000_000);
        }
    }

    /** 전처리 Converse — RagQueryPreprocessService 스키마의 JSON 텍스트를 표준 envelope로 반환. */
    private void converse(HttpExchange exchange, String body) throws IOException {
        String userText = extractUserText(body);
        String question = extractQuestion(userText);

        long delay = LatencyModel.lognormalMs(config.preprocessMedianMs(), config.preprocessSigma());
        if (LatencyModel.hit(config.slowRate())) {
            delay += config.slowExtraMs();
        }
        LatencyModel.sleep(delay);

        String preprocessJson = "{\"corporations\": [], \"keywords\": \"" + MiniJson.esc(stripNonce(question))
                + "\", \"vectorQuery\": \"" + MiniJson.esc(question) + "\"}";
        int inputTokens = estimateTokens(userText);
        int outputTokens = estimateTokens(preprocessJson);
        String response = "{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\""
                + MiniJson.esc(preprocessJson) + "\"}]}},\"stopReason\":\"end_turn\",\"usage\":{\"inputTokens\":"
                + inputTokens + ",\"outputTokens\":" + outputTokens + ",\"totalTokens\":" + (inputTokens + outputTokens)
                + "},\"metrics\":{\"latencyMs\":" + delay + "}}";
        sendJson(exchange, 200, response);
    }

    /** 답변 ConverseStream — eventstream 프레임을 TTFT/토큰 간격 지연과 함께 스트리밍. */
    private void converseStream(HttpExchange exchange, String body) throws IOException {
        long ttft = LatencyModel.lognormalMs(config.ttftMedianMs(), config.ttftSigma());
        if (LatencyModel.hit(config.slowRate())) {
            ttft += config.slowExtraMs();
        }
        String answer = buildAnswerText();

        exchange.getResponseHeaders().set("Content-Type", "application/vnd.amazon.eventstream");
        exchange.sendResponseHeaders(200, 0); // chunked
        OutputStream out = exchange.getResponseBody();

        writeEvent(out, "messageStart", "{\"role\":\"assistant\"}");
        LatencyModel.sleep(ttft);

        int deltaCount = 0;
        for (int offset = 0; offset < answer.length(); offset += TOKEN_CHARS) {
            String token = answer.substring(offset, Math.min(offset + TOKEN_CHARS, answer.length()));
            writeEvent(out, "contentBlockDelta",
                    "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"" + MiniJson.esc(token) + "\"}}");
            deltaCount++;
            LatencyModel.sleep(LatencyModel.jitterMs(config.tokenIntervalMs(), config.tokenJitterMs()));
        }

        writeEvent(out, "contentBlockStop", "{\"contentBlockIndex\":0}");
        writeEvent(out, "messageStop", "{\"stopReason\":\"end_turn\"}");
        int inputTokens = estimateTokens(body);
        writeEvent(out, "metadata", "{\"usage\":{\"inputTokens\":" + inputTokens + ",\"outputTokens\":" + deltaCount
                + ",\"totalTokens\":" + (inputTokens + deltaCount) + "},\"metrics\":{\"latencyMs\":" + ttft + "}}");
        out.flush();
    }

    private void writeEvent(OutputStream out, String eventType, String payload) throws IOException {
        out.write(EventStreamCodec.event(eventType, payload));
        out.flush();
    }

    /** MOCK_ANSWER_TOKENS × TOKEN_CHARS 길이만큼 문장 풀을 순환해 답변 본문을 만든다. */
    private String buildAnswerText() {
        int targetChars = config.answerTokens() * TOKEN_CHARS - QUERIES_TAG.length();
        StringBuilder sb = new StringBuilder(targetChars + QUERIES_TAG.length() + 64);
        int sentenceIndex = 0;
        int caseNumber = 1;
        sb.append("## ").append(caseNumber).append(". Mock 기업 사례\n\n");
        while (sb.length() < targetChars) {
            sb.append(SENTENCE_POOL[sentenceIndex % SENTENCE_POOL.length]).append("\n\n");
            sentenceIndex++;
            if (sentenceIndex % 4 == 0 && sb.length() < targetChars) {
                caseNumber++;
                sb.append("## ").append(caseNumber).append(". Mock 기업 사례\n\n");
            }
        }
        sb.append(QUERIES_TAG);
        return sb.toString();
    }

    /** Converse 요청 JSON에서 마지막 user 메시지 텍스트를 꺼낸다. */
    private String extractUserText(String body) {
        Object root = MiniJson.parse(body);
        Object messages = MiniJson.at(root, "messages");
        if (messages instanceof java.util.List<?> list && !list.isEmpty()) {
            String text = MiniJson.stringAt(list.get(list.size() - 1), "content", 0, "text");
            if (text != null) {
                return text;
            }
        }
        return "";
    }

    /** 멀티턴 augmented question("...\n현재 질문: {q}")에서 현재 질문만 분리한다. */
    private String extractQuestion(String userText) {
        int marker = userText.lastIndexOf("현재 질문: ");
        String question = marker >= 0 ? userText.substring(marker + "현재 질문: ".length()) : userText;
        return question.strip();
    }

    /** k6 nonce "(test:uuid)"와 특수문자를 제거해 BM25 keywords로 쓸 수 있게 정리한다. */
    private String stripNonce(String question) {
        return question
                .replaceAll("\\(test:[^)]*\\)", " ")
                .replaceAll("[^\\p{IsHangul}\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    private void sendThrottlingError(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("x-amzn-errortype", "ThrottlingException");
        sendJson(exchange, 429, "{\"message\":\"Too many requests (mock injected)\"}");
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
