/**
 * env 기반 mock 동작 설정.
 *
 * 지연은 lognormal(median, sigma) 분포로 샘플링한다 — 실측 캘리브레이션 절차는 load-test/README.md 참고.
 * 기본값 합(TTFT + 토큰수×간격)이 BedrockClientFactory ASYNC_API_CALL_TIMEOUT(90s)을 넘으면
 * 모든 스트림이 SDK 타임아웃으로 끊기므로 기동 시 검증해서 fail-fast 한다.
 */
public record MockConfig(
        int port,
        double preprocessMedianMs, double preprocessSigma,
        double ttftMedianMs, double ttftSigma,
        double tokenIntervalMs, double tokenJitterMs,
        int answerTokens,
        double embedMedianMs, double embedSigma,
        double errorRate,
        double slowRate, long slowExtraMs) {

    private static final long ASYNC_TIMEOUT_BUDGET_MS = 80_000; // SDK 90s - 여유 10s

    public static MockConfig fromEnv() {
        MockConfig config = new MockConfig(
                intEnv("MOCK_PORT", 9099),
                doubleEnv("MOCK_PREPROCESS_MEDIAN_MS", 800), doubleEnv("MOCK_PREPROCESS_SIGMA", 0.4),
                doubleEnv("MOCK_TTFT_MEDIAN_MS", 1500), doubleEnv("MOCK_TTFT_SIGMA", 0.5),
                doubleEnv("MOCK_TOKEN_INTERVAL_MS", 30), doubleEnv("MOCK_TOKEN_JITTER_MS", 20),
                intEnv("MOCK_ANSWER_TOKENS", 350),
                doubleEnv("MOCK_EMBED_MEDIAN_MS", 150), doubleEnv("MOCK_EMBED_SIGMA", 0.3),
                doubleEnv("MOCK_ERROR_RATE", 0),
                doubleEnv("MOCK_SLOW_RATE", 0), (long) doubleEnv("MOCK_SLOW_EXTRA_MS", 60_000));

        double expectedStreamMs = config.ttftMedianMs
                + config.answerTokens * (config.tokenIntervalMs + config.tokenJitterMs / 2);
        if (expectedStreamMs > ASYNC_TIMEOUT_BUDGET_MS) {
            throw new IllegalStateException(
                    "예상 스트림 시간 %.0fms가 Bedrock async 타임아웃 예산 %dms를 초과 — MOCK_ANSWER_TOKENS/MOCK_TOKEN_INTERVAL_MS를 줄여라"
                            .formatted(expectedStreamMs, ASYNC_TIMEOUT_BUDGET_MS));
        }
        return config;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static double doubleEnv(String name, double defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Double.parseDouble(value.trim());
    }
}
