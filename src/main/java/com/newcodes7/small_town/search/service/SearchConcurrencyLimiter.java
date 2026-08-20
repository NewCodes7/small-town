package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.global.entity.SearchConcurrencyConfig;
import com.newcodes7.small_town.search.repository.SearchConcurrencyConfigRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검색 API 유입 제어 — 동시 실행 수를 세마포어로 상한 짓고 초과분은 429로 흘려보낸다.
 *
 * <p><b>왜 레이트 리밋이 아니라 동시성 제한인가.</b> nginx는 이미 IP별 120r/m을 걸고 있지만
 * (nginx/default.conf), 서로 다른 IP가 20개면 그대로 통과한다. 게다가 레이트 리밋은 "요청이
 * 얼마나 비싼지"를 모른다. 동시성 제한은 그 자체로 부하 적응적이다 — 캐시 히트로 50ms에
 * 끝나는 요청은 permit을 금방 놓아 한도 15로도 초당 수백 건이 지나가고, 요청이 느려질수록
 * permit이 오래 물려 자동으로 조여진다. 싼 요청을 따로 구분해줄 필요가 없다.
 *
 * <p><b>왜 컨트롤러 진입점인가.</b> "실제로 비싼 계산(하이브리드 코어)만 제한한다"는 선택지도
 * 있었지만 불필요하다. 위 성질 때문에 싼 요청은 알아서 빠져나가고, 캐시 히트라고 공짜도 아니다
 * — 모든 검색이 logSearchAsync로 5개짜리 DB 풀에 쓰기를 건다. 게다가 코어 안쪽에 걸면 이미
 * single-flight future에 합류한 조인자들이 429가 아니라 빈 결과("검색 결과 없음")를 받게 된다.
 *
 * <p><b>거절을 로그로 남기지 않는다.</b> 거절이 쏟아지는 상황은 정확히 과부하 상황이고,
 * prod 로깅은 AsyncAppender 없이 RollingFileAppender 직결이라 appender 락 경합 + 파일 write가
 * 그대로 요청 경로에 얹힌다(ArticleSearchService의 같은 주석 참고). 막으려는 문제를 로그가
 * 다시 만드는 셈이라, 관측은 전적으로 아래 메트릭에 맡긴다.
 *
 * <p><b>한도는 JVM 인스턴스별이다.</b> blue/green은 nginx가 한쪽으로만 트래픽을 보내므로 정상 운영에서는
 * 실효 한도와 같지만, 전환 구간이나 이후 스케일아웃 시에는 인스턴스 수만큼 곱해진다는 점에 유의할 것.
 *
 * <p>한도는 DB(search_concurrency_config)에서 읽고 운영 중 변경할 수 있다. DB 로드가 실패해도
 * DEFAULTS로 계속 동작한다 — 설정 테이블 하나 때문에 검색이 죽으면 안 된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchConcurrencyLimiter {

    public static final String SCOPE_SEARCH = "SEARCH";

    public record Limits(int maxConcurrent, int acquireTimeoutMs) {}

    /**
     * DB 로드 실패 시 폴백. 15는 실측 무릎(VU10 정점 14.3 RPS, VU15에서 꺾임)의 상단이고,
     * 300ms는 순간 버스트를 흡수하되 큐를 만들지 않는 길이다.
     */
    private static final Limits DEFAULTS = new Limits(15, 300);

    private final SearchConcurrencyConfigRepository repository;
    private final MeterRegistry meterRegistry;

    private final AdjustableSemaphore semaphore = new AdjustableSemaphore(DEFAULTS.maxConcurrent());
    private volatile Limits limits = DEFAULTS;

    private Counter rejectedCounter;
    private Counter acceptedCounter;
    private Timer acquireWaitTimer;

    @PostConstruct
    public void init() {
        // 메트릭은 DB 로드 성공 여부와 무관하게 항상 등록한다 — 거절 0건도 "0"으로 보여야
        // 대시보드에서 "지표가 없는 것"과 "거절이 없는 것"이 구분된다.
        acceptedCounter = Counter.builder("search_concurrency_requests_total")
                .tag("result", "accepted")
                .description("검색 동시성 제한을 통과한 요청 수")
                .register(meterRegistry);
        rejectedCounter = Counter.builder("search_concurrency_requests_total")
                .tag("result", "rejected")
                .description("검색 동시성 제한에 걸려 429로 거절된 요청 수")
                .register(meterRegistry);
        acquireWaitTimer = Timer.builder("search_concurrency_acquire_wait")
                .description("permit 획득까지 대기한 시간(거절된 요청 포함)")
                .register(meterRegistry);
        Gauge.builder("search_concurrency_in_use", semaphore,
                        s -> (double) (s.total() - s.availablePermits()))
                .description("현재 사용 중인 permit 수")
                .register(meterRegistry);
        Gauge.builder("search_concurrency_limit", semaphore, s -> (double) s.total())
                .description("현재 적용 중인 동시 실행 상한")
                .register(meterRegistry);

        refreshCache();
    }

    /**
     * permit 획득을 시도한다. true를 돌려받은 호출자는 <b>반드시</b> finally에서
     * {@link #release()}를 호출해야 한다 — false일 때 release를 부르면 permit이 늘어난다.
     */
    public boolean tryAcquire() {
        Limits current = this.limits;
        long startNanos = System.nanoTime();
        try {
            boolean acquired = semaphore.tryAcquire(current.acquireTimeoutMs(), TimeUnit.MILLISECONDS);
            acquireWaitTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            (acquired ? acceptedCounter : rejectedCounter).increment();
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquireWaitTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            rejectedCounter.increment();
            return false;
        }
    }

    /** {@link #tryAcquire()}가 true를 반환한 경우에만 호출할 것. */
    public void release() {
        semaphore.release();
    }

    public Limits getLimits() {
        return limits;
    }

    /** 현재 사용 중인 permit 수 (admin 화면 표시용). */
    public int getInUse() {
        return semaphore.total() - semaphore.availablePermits();
    }

    @Transactional
    public void updateLimits(int maxConcurrent, int acquireTimeoutMs, String updatedBy) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent는 1 이상이어야 합니다: " + maxConcurrent);
        }
        if (acquireTimeoutMs < 0) {
            throw new IllegalArgumentException("acquireTimeoutMs는 0 이상이어야 합니다: " + acquireTimeoutMs);
        }

        SearchConcurrencyConfig config = repository.findByScopeName(SCOPE_SEARCH)
                .orElseGet(() -> SearchConcurrencyConfig.builder().scopeName(SCOPE_SEARCH).build());
        config.setMaxConcurrent(maxConcurrent);
        config.setAcquireTimeoutMs(acquireTimeoutMs);
        config.setUpdatedBy(updatedBy);
        repository.save(config);

        applyLimits(new Limits(maxConcurrent, acquireTimeoutMs));
        log.info("[검색 동시성] 한도 업데이트: maxConcurrent={}, acquireTimeoutMs={}",
                maxConcurrent, acquireTimeoutMs);
    }

    private void refreshCache() {
        try {
            Limits loaded = repository.findByScopeName(SCOPE_SEARCH)
                    .map(c -> new Limits(c.getMaxConcurrent(), c.getAcquireTimeoutMs()))
                    .orElse(DEFAULTS);
            applyLimits(loaded);
            log.info("[검색 동시성] 설정 로드 완료: maxConcurrent={}, acquireTimeoutMs={}",
                    loaded.maxConcurrent(), loaded.acquireTimeoutMs());
        } catch (Exception e) {
            // 설정 테이블을 못 읽었다고 검색이 죽으면 안 된다 — 폴백으로 계속 간다
            log.error("[검색 동시성] DB 로드 실패, 기본값 유지(maxConcurrent={}): {}",
                    limits.maxConcurrent(), e.getMessage());
        }
    }

    private void applyLimits(Limits newLimits) {
        semaphore.resize(newLimits.maxConcurrent());
        this.limits = newLimits;
    }

    /**
     * 운영 중 permit 수를 바꿀 수 있는 Semaphore.
     * {@code reducePermits}가 protected라 상속으로만 접근된다.
     *
     * <p>fair 모드로 둔다: 짧은 획득 대기(수백 ms)와 함께 쓰이므로 barging을 허용하면
     * 먼저 온 요청이 계속 밀려 대기시간이 들쭉날쭉해진다.
     */
    private static final class AdjustableSemaphore extends Semaphore {

        private static final long serialVersionUID = 1L;

        private int total;

        AdjustableSemaphore(int permits) {
            super(permits, true);
            this.total = permits;
        }

        /**
         * 상한을 조정한다. 이미 나가 있는 permit은 회수하지 않는다 — 축소 시에는
         * 사용 가능한 permit에서 먼저 깎이고, 나머지는 in-flight 요청이 반납하면서 자연히 맞춰진다.
         */
        synchronized void resize(int newTotal) {
            int delta = newTotal - total;
            if (delta > 0) {
                release(delta);
            } else if (delta < 0) {
                reducePermits(-delta);
            }
            total = newTotal;
        }

        synchronized int total() {
            return total;
        }
    }
}
