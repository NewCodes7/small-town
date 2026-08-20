package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.global.entity.SearchConcurrencyConfig;
import com.newcodes7.small_town.search.repository.SearchConcurrencyConfigRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 검색 동시 실행 상한 검증. Spring 컨텍스트 없이 리미터 단독으로 돌린다.
 */
class SearchConcurrencyLimiterTest {

    private SimpleMeterRegistry registry;
    private SearchConcurrencyConfigRepository repository;

    private SearchConcurrencyLimiter limiterWith(Integer maxConcurrent, Integer acquireTimeoutMs) {
        SearchConcurrencyConfig config = maxConcurrent == null ? null
                : SearchConcurrencyConfig.builder()
                        .scopeName(SearchConcurrencyLimiter.SCOPE_SEARCH)
                        .maxConcurrent(maxConcurrent)
                        .acquireTimeoutMs(acquireTimeoutMs)
                        .build();
        repository = mock(SearchConcurrencyConfigRepository.class);
        when(repository.findByScopeName(anyString())).thenReturn(Optional.ofNullable(config));
        registry = new SimpleMeterRegistry();
        SearchConcurrencyLimiter limiter = new SearchConcurrencyLimiter(repository, registry);
        limiter.init();
        return limiter;
    }

    private double counter(String result) {
        return registry.get("search_concurrency_requests_total").tag("result", result).counter().count();
    }

    @Test
    void 한도까지는_통과하고_초과분은_거절된다() {
        SearchConcurrencyLimiter limiter = limiterWith(2, 10);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire())
                .as("한도 2를 넘긴 세 번째 요청은 대기하지 않고 거절돼야 한다")
                .isFalse();
        assertThat(limiter.getInUse()).isEqualTo(2);

        // permit을 놓으면 다음 요청이 다시 통과한다
        limiter.release();
        assertThat(limiter.tryAcquire()).isTrue();

        limiter.release();
        limiter.release();
        assertThat(limiter.getInUse()).isZero();
    }

    @Test
    void 거절과_통과가_각각_메트릭에_기록된다() {
        SearchConcurrencyLimiter limiter = limiterWith(1, 0);

        // 등록만 되고 기록이 없는 상태에서도 meter는 존재해야 한다(0으로 보여야 구분이 된다)
        assertThat(counter("accepted")).isZero();
        assertThat(counter("rejected")).isZero();

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        assertThat(counter("accepted")).isEqualTo(1);
        assertThat(counter("rejected")).isEqualTo(1);
        assertThat(registry.get("search_concurrency_acquire_wait").timer().count())
                .as("거절된 요청의 대기시간도 기록돼야 튜닝에 쓸 수 있다")
                .isEqualTo(2);
        assertThat(registry.get("search_concurrency_limit").gauge().value()).isEqualTo(1);
        assertThat(registry.get("search_concurrency_in_use").gauge().value()).isEqualTo(1);

        limiter.release();
    }

    @Test
    void DB에_설정이_없으면_기본값으로_동작한다() {
        SearchConcurrencyLimiter limiter = limiterWith(null, null);

        assertThat(limiter.getLimits().maxConcurrent()).isEqualTo(15);
        assertThat(limiter.getLimits().acquireTimeoutMs()).isEqualTo(300);
        assertThat(limiter.tryAcquire()).isTrue();
        limiter.release();
    }

    @Test
    void DB_조회가_실패해도_검색은_계속된다() {
        repository = mock(SearchConcurrencyConfigRepository.class);
        when(repository.findByScopeName(anyString())).thenThrow(new RuntimeException("DB 다운"));
        registry = new SimpleMeterRegistry();
        SearchConcurrencyLimiter limiter = new SearchConcurrencyLimiter(repository, registry);

        // 설정 테이블 하나 때문에 검색이 죽으면 안 된다 — init()이 예외를 삼키고 폴백으로 떠야 한다
        limiter.init();

        assertThat(limiter.getLimits().maxConcurrent()).isEqualTo(15);
        assertThat(limiter.tryAcquire()).isTrue();
        limiter.release();
    }

    @Test
    void 한도를_줄이면_즉시_반영된다() {
        SearchConcurrencyLimiter limiter = limiterWith(5, 0);

        limiter.updateLimits(1, 0, "test");

        assertThat(limiter.getLimits().maxConcurrent()).isEqualTo(1);
        assertThat(registry.get("search_concurrency_limit").gauge().value()).isEqualTo(1);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire())
                .as("줄인 한도가 곧바로 적용돼야 한다")
                .isFalse();

        limiter.release();
    }

    @Test
    void 한도를_늘리면_즉시_반영된다() {
        SearchConcurrencyLimiter limiter = limiterWith(1, 0);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        limiter.updateLimits(3, 0, "test");

        // 이미 나가 있던 permit 1개는 유지된 채 늘어난 만큼만 추가로 통과한다
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.getInUse()).isEqualTo(3);

        limiter.release();
        limiter.release();
        limiter.release();
    }

    @Test
    void 잘못된_한도값은_거부된다() {
        SearchConcurrencyLimiter limiter = limiterWith(5, 100);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> limiter.updateLimits(0, 100, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> limiter.updateLimits(5, -1, "test"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(limiter.getLimits().maxConcurrent())
                .as("거부된 변경은 적용되지 않아야 한다")
                .isEqualTo(5);
    }
}
