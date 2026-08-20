package com.newcodes7.small_town.embedding.service;

import com.newcodes7.small_town.embedding.repository.EmbeddingCircuitConfigRepository;
import com.newcodes7.small_town.global.entity.EmbeddingCircuitConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Clova 임베딩 호출 서킷 브레이커.
 *
 * <p><b>왜 필요한가.</b> {@link EmbeddingApiService}는 실패를 전부 삼키고 success=false를 돌려주므로,
 * Clova가 죽거나 느려져도 매 캐시 미스 요청이 계속 호출을 시도한다. 호출자(vectorFuture.get)는 5초에
 * 포기하지만 태스크는 살아남아 커넥션 풀(maxConnPerRoute=10)을 물고, 뒤따르는 요청은 lease 대기(2초)에
 * 걸린다. 차단기가 열리면 호출 자체를 건너뛰고 <b>이미 존재하는 폴백</b>으로 흐른다 —
 * 임베딩 null → VectorSearchService 빈 결과 → ArticleSearchService가 BM25-only로 진행.
 * 즉 새로 만들 폴백 경로가 없다는 점이 이 지점에 차단기를 두는 가장 큰 이유다.
 *
 * <p><b>왜 애노테이션이 아니라 프로그래밍 방식인가.</b> {@code EmbeddingApiService}는 2-arg 진입점이
 * 같은 클래스의 3-arg 메서드를 호출하는 self-invocation 구조라, 프록시 기반 AOP는 그 내부 홉을
 * 가로채지 못한다. 어느 쪽에 애노테이션을 달아도 한쪽 경로가 뚫린다(3-arg에 달면 배치 경로가,
 * 2-arg에 달면 검색 경로가). 메서드 본문에서 감싸면 진입 경로와 무관하게 항상 적용된다.
 *
 * <p><b>실패 분류.</b> resilience4j는 record 술어가 false를 돌려주면 그 예외를 <i>성공으로</i>
 * 집계한다(CircuitBreakerStateMachine.handleThrowable). 그래서 "집계하지 않을 것"은 반드시
 * ignore 쪽으로 빼야 한다. 4xx는 차단기를 열어도 나아지지 않으므로(키 만료·잘못된 요청) 무시하되,
 * 408/429는 백오프 신호이므로 실패로 집계한다.
 *
 * <p>설정 변경 시에는 CircuitBreaker 인스턴스를 새로 만든다 — 슬라이딩 윈도우가 초기화되지만,
 * 임계치를 바꾼 시점에 이전 관측치를 이어 쓰는 것이 오히려 혼란스럽다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingCircuitBreaker {

    public static final String SCOPE_CLOVA_EMBEDDING = "CLOVA_EMBEDDING";
    private static final String CB_NAME = "clova-embedding";

    /** DB 로드 실패 시 폴백. 값 근거는 V1_38 마이그레이션 주석 참고. */
    private static final Settings DEFAULTS = new Settings(true, 50.0, 80.0, 2000, 30000, 20, 10, 3);

    public record Settings(
            boolean enabled,
            double failureRateThreshold,
            double slowCallRateThreshold,
            int slowCallDurationMs,
            int waitDurationOpenMs,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            int permittedCallsHalfOpen) {}

    private final EmbeddingCircuitConfigRepository repository;
    private final MeterRegistry meterRegistry;

    private volatile Settings settings = DEFAULTS;
    private volatile CircuitBreaker circuitBreaker;

    private Counter notPermittedCounter;

    @PostConstruct
    public void init() {
        applySettings(DEFAULTS);   // 메트릭이 참조할 인스턴스를 먼저 만든다
        registerMetrics();
        refresh();
    }

    /**
     * 차단기를 통과시켜 호출한다.
     *
     * @throws CallNotPermittedException 차단기가 OPEN이라 호출을 건너뛴 경우
     */
    public <T> T execute(Supplier<T> supplier) {
        if (!settings.enabled()) {
            return supplier.get();
        }
        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (CallNotPermittedException e) {
            notPermittedCounter.increment();
            throw e;
        }
    }

    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }

    public Settings getSettings() {
        return settings;
    }

    public float getFailureRate() {
        return circuitBreaker.getMetrics().getFailureRate();
    }

    public float getSlowCallRate() {
        return circuitBreaker.getMetrics().getSlowCallRate();
    }

    @Transactional
    public void updateSettings(Settings newSettings, String updatedBy) {
        validate(newSettings);

        EmbeddingCircuitConfig config = repository.findByScopeName(SCOPE_CLOVA_EMBEDDING)
                .orElseGet(() -> EmbeddingCircuitConfig.builder().scopeName(SCOPE_CLOVA_EMBEDDING).build());
        config.setEnabled(newSettings.enabled());
        config.setFailureRateThreshold(newSettings.failureRateThreshold());
        config.setSlowCallRateThreshold(newSettings.slowCallRateThreshold());
        config.setSlowCallDurationMs(newSettings.slowCallDurationMs());
        config.setWaitDurationOpenMs(newSettings.waitDurationOpenMs());
        config.setSlidingWindowSize(newSettings.slidingWindowSize());
        config.setMinimumNumberOfCalls(newSettings.minimumNumberOfCalls());
        config.setPermittedCallsHalfOpen(newSettings.permittedCallsHalfOpen());
        config.setUpdatedBy(updatedBy);
        repository.save(config);

        applySettings(newSettings);
        log.info("[임베딩 차단기] 설정 업데이트: {}", newSettings);
    }

    /** 차단기를 강제로 닫는다 (장애 복구 확인 후 즉시 재개용). */
    public void reset() {
        circuitBreaker.reset();
        log.info("[임베딩 차단기] 수동 리셋 — 상태 CLOSED로 복귀");
    }

    private static void validate(Settings s) {
        if (s.failureRateThreshold() <= 0 || s.failureRateThreshold() > 100) {
            throw new IllegalArgumentException("failureRateThreshold는 0 초과 100 이하여야 합니다: " + s.failureRateThreshold());
        }
        if (s.slowCallRateThreshold() <= 0 || s.slowCallRateThreshold() > 100) {
            throw new IllegalArgumentException("slowCallRateThreshold는 0 초과 100 이하여야 합니다: " + s.slowCallRateThreshold());
        }
        if (s.slidingWindowSize() < 1) {
            throw new IllegalArgumentException("slidingWindowSize는 1 이상이어야 합니다: " + s.slidingWindowSize());
        }
        if (s.minimumNumberOfCalls() < 1) {
            throw new IllegalArgumentException("minimumNumberOfCalls는 1 이상이어야 합니다: " + s.minimumNumberOfCalls());
        }
        if (s.permittedCallsHalfOpen() < 1) {
            throw new IllegalArgumentException("permittedCallsHalfOpen은 1 이상이어야 합니다: " + s.permittedCallsHalfOpen());
        }
        if (s.slowCallDurationMs() < 1 || s.waitDurationOpenMs() < 1) {
            throw new IllegalArgumentException("slowCallDurationMs/waitDurationOpenMs는 1 이상이어야 합니다");
        }
    }

    private void refresh() {
        try {
            Settings loaded = repository.findByScopeName(SCOPE_CLOVA_EMBEDDING)
                    .map(c -> new Settings(
                            Boolean.TRUE.equals(c.getEnabled()),
                            c.getFailureRateThreshold(),
                            c.getSlowCallRateThreshold(),
                            c.getSlowCallDurationMs(),
                            c.getWaitDurationOpenMs(),
                            c.getSlidingWindowSize(),
                            c.getMinimumNumberOfCalls(),
                            c.getPermittedCallsHalfOpen()))
                    .orElse(DEFAULTS);
            applySettings(loaded);
            log.info("[임베딩 차단기] 설정 로드 완료: {}", loaded);
        } catch (Exception e) {
            // 설정 테이블을 못 읽었다고 임베딩이 죽으면 안 된다 — 폴백으로 계속 간다
            log.error("[임베딩 차단기] DB 로드 실패, 기본값 유지: {}", e.getMessage());
        }
    }

    private void applySettings(Settings s) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) s.failureRateThreshold())
                .slowCallRateThreshold((float) s.slowCallRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(s.slowCallDurationMs()))
                .waitDurationInOpenState(Duration.ofMillis(s.waitDurationOpenMs()))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(s.slidingWindowSize())
                .minimumNumberOfCalls(s.minimumNumberOfCalls())
                .permittedNumberOfCallsInHalfOpenState(s.permittedCallsHalfOpen())
                // 집계에서 뺄 것은 반드시 ignore로 뺀다 — record 술어가 false면 '성공'으로 잡힌다
                .ignoreException(EmbeddingCircuitBreaker::isIgnored)
                .build();
        CircuitBreaker cb = CircuitBreaker.of(CB_NAME, config);
        // 로그는 상태 전이에만 남긴다. 열려 있는 동안 건너뛴 호출마다 찍으면 장애 시 로그가 폭증하고,
        // prod는 AsyncAppender 없이 RollingFileAppender 직결이라 그 자체가 부하가 된다.
        cb.getEventPublisher().onStateTransition(event -> log.warn(
                "[임베딩 차단기] 상태 전이: {} → {} (실패율 {}%, 느린 호출 {}%)",
                event.getStateTransition().getFromState(), event.getStateTransition().getToState(),
                cb.getMetrics().getFailureRate(), cb.getMetrics().getSlowCallRate()));
        this.circuitBreaker = cb;
        this.settings = s;
    }

    /**
     * 차단기 집계에서 제외할 예외인지 판정한다.
     *
     * <p>4xx는 차단기를 열어도 나아지지 않는다 — 키 만료(401)나 잘못된 요청(400)이라면 차단은
     * 원인을 가릴 뿐이다. 다만 408(timeout)/429(rate limit)는 "잠시 물러나라"는 신호이므로 실패로 센다.
     * 그 밖의 예외(5xx, 타임아웃·커넥션 계열의 ResourceAccessException, 응답 파싱 실패)는 전부 실패다 —
     * 특히 파싱 실패는 장애 시 Clova가 에러 페이지를 돌려주는 형태로 나타난다.
     */
    static boolean isIgnored(Throwable t) {
        if (t instanceof HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            return status != 408 && status != 429;
        }
        return false;
    }

    private void registerMetrics() {
        notPermittedCounter = Counter.builder("embedding_circuit_calls_total")
                .tag("result", "not_permitted")
                .description("차단기가 OPEN이라 호출하지 않고 건너뛴 임베딩 요청 수")
                .register(meterRegistry);
        // 게이지는 this에 바인딩한다 — 설정 변경 시 circuitBreaker 인스턴스가 교체되기 때문
        Gauge.builder("embedding_circuit_state", this, EmbeddingCircuitBreaker::stateCode)
                .description("0=CLOSED, 1=OPEN, 2=HALF_OPEN, 3=기타(강제/비활성)")
                .register(meterRegistry);
        Gauge.builder("embedding_circuit_failure_rate", this, b -> b.getFailureRate())
                .description("슬라이딩 윈도우 실패율(%). 호출 수가 최소치 미만이면 -1")
                .register(meterRegistry);
        Gauge.builder("embedding_circuit_slow_call_rate", this, b -> b.getSlowCallRate())
                .description("슬라이딩 윈도우 느린 호출 비율(%). 호출 수가 최소치 미만이면 -1")
                .register(meterRegistry);
    }

    private double stateCode() {
        return switch (circuitBreaker.getState()) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
            default -> 3;
        };
    }
}
