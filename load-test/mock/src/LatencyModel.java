import java.util.concurrent.ThreadLocalRandom;

/** lognormal 지연 샘플링 + 장애 주입 판정. virtual thread 위에서 Thread.sleep으로 지연을 재현한다. */
public final class LatencyModel {

    private LatencyModel() {
    }

    /** median × exp(sigma·gaussian) — LLM 응답시간의 오른쪽 긴 꼬리를 근사한다. */
    public static long lognormalMs(double medianMs, double sigma) {
        return Math.round(medianMs * Math.exp(sigma * ThreadLocalRandom.current().nextGaussian()));
    }

    public static long jitterMs(double baseMs, double jitterMs) {
        return Math.round(baseMs + ThreadLocalRandom.current().nextDouble() * jitterMs);
    }

    public static boolean hit(double rate) {
        return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
    }

    public static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
