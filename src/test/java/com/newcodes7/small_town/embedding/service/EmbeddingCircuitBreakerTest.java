package com.newcodes7.small_town.embedding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.embedding.repository.EmbeddingCircuitConfigRepository;
import com.newcodes7.small_town.global.entity.EmbeddingCircuitConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Clova 임베딩 서킷 브레이커 검증.
 *
 * 가장 중요한 건 예외 분류다 — resilience4j는 record 술어가 false면 그 예외를 <b>성공으로</b>
 * 집계하므로, 4xx를 ignore가 아닌 record=false로 처리하면 장애를 성공으로 세게 된다.
 */
class EmbeddingCircuitBreakerTest {

    private SimpleMeterRegistry registry;

    private EmbeddingCircuitBreaker breakerWith(
            boolean enabled, double failureRate, double slowRate, int slowMs, int windowSize, int minCalls) {
        EmbeddingCircuitConfig config = EmbeddingCircuitConfig.builder()
                .scopeName(EmbeddingCircuitBreaker.SCOPE_CLOVA_EMBEDDING)
                .enabled(enabled)
                .failureRateThreshold(failureRate)
                .slowCallRateThreshold(slowRate)
                .slowCallDurationMs(slowMs)
                .waitDurationOpenMs(30000)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minCalls)
                .permittedCallsHalfOpen(3)
                .build();
        EmbeddingCircuitConfigRepository repository = mock(EmbeddingCircuitConfigRepository.class);
        when(repository.findByScopeName(anyString())).thenReturn(Optional.of(config));
        registry = new SimpleMeterRegistry();
        EmbeddingCircuitBreaker breaker = new EmbeddingCircuitBreaker(repository, registry);
        breaker.init();
        return breaker;
    }

    /** 지정한 예외를 count번 던진다 (차단기가 열리면 CallNotPermitted는 무시하고 계속). */
    private void failTimes(EmbeddingCircuitBreaker breaker, RuntimeException error, int count) {
        for (int i = 0; i < count; i++) {
            try {
                breaker.execute(() -> {
                    throw error;
                });
            } catch (RuntimeException ignored) {
                // 집계가 목적이므로 예외 자체는 버린다
            }
        }
    }

    @Test
    void 서버_오류가_임계치를_넘으면_열리고_이후_호출은_건너뛴다() {
        EmbeddingCircuitBreaker breaker = breakerWith(true, 50, 100, 5000, 10, 5);

        failTimes(breaker, new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR), 5);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.get("embedding_circuit_state").gauge().value()).isEqualTo(1);

        // 열린 뒤에는 supplier를 아예 실행하지 않는다
        assertThatThrownBy(() -> breaker.execute(() -> "호출되면 안 됨"))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(registry.get("embedding_circuit_calls_total").tag("result", "not_permitted").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 타임아웃_커넥션_오류도_실패로_집계된다() {
        EmbeddingCircuitBreaker breaker = breakerWith(true, 50, 100, 5000, 10, 5);

        // ResourceAccessException은 읽기 타임아웃·커넥션 lease 타임아웃이 올라오는 형태다
        failTimes(breaker, new ResourceAccessException("Read timed out"), 5);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 응답_파싱_실패도_실패로_집계된다() {
        EmbeddingCircuitBreaker breaker = breakerWith(true, 50, 100, 5000, 10, 5);

        // 장애 시 Clova가 에러 페이지를 돌려주면 파싱 단계에서 터진다
        failTimes(breaker, new RuntimeException("Embedding API 응답에 embedding이 없습니다"), 5);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 일반_4xx는_집계에서_제외돼_차단기를_열지_않는다() {
        EmbeddingCircuitBreaker breaker = breakerWith(true, 50, 100, 5000, 10, 5);

        // 키 만료(401)나 잘못된 요청(400)은 차단해도 나아지지 않는다 — 차단은 원인을 가릴 뿐이다
        failTimes(breaker, new HttpClientErrorException(HttpStatus.UNAUTHORIZED), 20);

        assertThat(breaker.getState())
                .as("4xx만으로는 열리지 않아야 한다")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getFailureRate())
                .as("ignore된 예외는 실패로도 성공으로도 집계되지 않는다(호출 수 미달 시 -1)")
                .isEqualTo(-1.0f);
    }

    @Test
    void 응답없음_429와_408은_백오프_신호라_실패로_집계된다() {
        EmbeddingCircuitBreaker tooManyRequests = breakerWith(true, 50, 100, 5000, 10, 5);
        failTimes(tooManyRequests, new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS), 5);
        assertThat(tooManyRequests.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        EmbeddingCircuitBreaker requestTimeout = breakerWith(true, 50, 100, 5000, 10, 5);
        failTimes(requestTimeout, new HttpClientErrorException(HttpStatus.REQUEST_TIMEOUT), 5);
        assertThat(requestTimeout.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void 느린_호출이_임계치를_넘으면_에러가_없어도_열린다() throws Exception {
        // Clova 장애는 에러보다 느려짐으로 오는 경우가 많다 — 성공 응답만으로도 열려야 한다
        EmbeddingCircuitBreaker breaker = breakerWith(true, 100, 80, 20, 5, 5);

        for (int i = 0; i < 5; i++) {
            breaker.execute(() -> {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "느리지만 성공";
            });
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getSlowCallRate()).isEqualTo(100.0f);
    }

    @Test
    void 비활성화하면_차단기를_거치지_않는다() {
        EmbeddingCircuitBreaker breaker = breakerWith(false, 50, 100, 5000, 10, 5);

        failTimes(breaker, new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR), 20);

        assertThat(breaker.getState())
                .as("킬 스위치가 꺼져 있으면 실패가 집계되지 않는다")
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.execute(() -> "그대로 호출됨")).isEqualTo("그대로 호출됨");
    }

    @Test
    void 리셋하면_즉시_닫힌다() {
        EmbeddingCircuitBreaker breaker = breakerWith(true, 50, 100, 5000, 10, 5);
        failTimes(breaker, new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR), 5);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        breaker.reset();

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.execute(() -> "재개")).isEqualTo("재개");
    }

    @Test
    void DB_조회가_실패해도_임베딩은_계속된다() {
        EmbeddingCircuitConfigRepository repository = mock(EmbeddingCircuitConfigRepository.class);
        when(repository.findByScopeName(anyString())).thenThrow(new RuntimeException("DB 다운"));
        registry = new SimpleMeterRegistry();
        EmbeddingCircuitBreaker breaker = new EmbeddingCircuitBreaker(repository, registry);

        breaker.init();

        assertThat(breaker.getSettings().failureRateThreshold()).isEqualTo(50.0);
        assertThat(breaker.execute(() -> "기본값으로 동작")).isEqualTo("기본값으로 동작");
    }
}
