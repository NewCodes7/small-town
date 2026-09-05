package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.global.entity.SearchConcurrencyConfig;
import com.newcodes7.small_town.search.repository.SearchConcurrencyConfigRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RAG 동시 실행 상한 검증. Spring 컨텍스트 없이 리미터 단독으로 돌린다.
 *
 * <p>세마포어·resize 같은 공통 본체는 {@code ConcurrencyLimiter}에 있고
 * {@link SearchConcurrencyLimiterTest}가 그쪽을 이미 덮는다. 여기서는 <b>RAG에만 있는 것</b>을 본다 —
 * 스코프 이름(SEARCH 행을 잘못 읽으면 상한 15로 돌게 된다), 메트릭 접두사, 기본값 90/300.
 */
class RagConcurrencyLimiterTest {

    private SimpleMeterRegistry registry;
    private SearchConcurrencyConfigRepository repository;

    private RagConcurrencyLimiter limiterWith(Integer maxConcurrent, Integer acquireTimeoutMs) {
        SearchConcurrencyConfig config = maxConcurrent == null ? null
                : SearchConcurrencyConfig.builder()
                        .scopeName(RagConcurrencyLimiter.SCOPE_RAG)
                        .maxConcurrent(maxConcurrent)
                        .acquireTimeoutMs(acquireTimeoutMs)
                        .build();
        repository = mock(SearchConcurrencyConfigRepository.class);
        when(repository.findByScopeName(anyString())).thenReturn(Optional.ofNullable(config));
        registry = new SimpleMeterRegistry();
        RagConcurrencyLimiter limiter = new RagConcurrencyLimiter(repository, registry);
        limiter.init();
        return limiter;
    }

    private double counter(String result) {
        return registry.get("rag_concurrency_requests_total").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("DB 조회는 'RAG' 스코프로 한다 — 검색 행(상한 15)을 읽으면 안 된다")
    void 스코프는_RAG다() {
        limiterWith(45, 300);

        ArgumentCaptor<String> scope = ArgumentCaptor.forClass(String.class);
        verify(repository).findByScopeName(scope.capture());
        assertThat(scope.getValue()).isEqualTo("RAG");
        assertThat(scope.getValue()).isNotEqualTo(SearchConcurrencyLimiter.SCOPE_SEARCH);
    }

    @Test
    @DisplayName("DB에 설정이 없으면 기본값 90 / 300ms로 동작한다")
    void DB에_설정이_없으면_기본값으로_동작한다() {
        RagConcurrencyLimiter limiter = limiterWith(null, null);

        // 90의 근거는 RagConcurrencyLimiter Javadoc / 2026-08-29 문서 14장 (p99 무릎).
        assertThat(limiter.getLimits().maxConcurrent()).isEqualTo(90);
        assertThat(limiter.getLimits().acquireTimeoutMs()).isEqualTo(300);
    }

    @Test
    @DisplayName("DB 조회가 실패해도 RAG는 계속 동작한다 (설정 테이블 때문에 죽으면 안 된다)")
    void DB_조회가_실패해도_계속_동작한다() {
        repository = mock(SearchConcurrencyConfigRepository.class);
        when(repository.findByScopeName(anyString())).thenThrow(new RuntimeException("DB down"));
        registry = new SimpleMeterRegistry();

        RagConcurrencyLimiter limiter = new RagConcurrencyLimiter(repository, registry);
        limiter.init();

        assertThat(limiter.getLimits().maxConcurrent()).isEqualTo(90);
        assertThat(limiter.tryAcquire()).isTrue();
        limiter.release();
    }

    @Test
    @DisplayName("한도까지는 통과하고 초과분은 대기 없이 거절된다")
    void 한도까지는_통과하고_초과분은_거절된다() {
        RagConcurrencyLimiter limiter = limiterWith(2, 10);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire())
                .as("한도 2를 넘긴 세 번째 요청은 대기하지 않고 거절돼야 한다")
                .isFalse();
        assertThat(limiter.getInUse()).isEqualTo(2);

        limiter.release();
        assertThat(limiter.tryAcquire()).isTrue();

        limiter.release();
        limiter.release();
        assertThat(limiter.getInUse()).isZero();
    }

    @Test
    @DisplayName("메트릭 4종이 rag_ 접두사로 등록되고, 트래픽 0에서도 0으로 보인다")
    void 메트릭이_rag_접두사로_등록된다() {
        RagConcurrencyLimiter limiter = limiterWith(1, 0);

        // 거절 0건과 "지표가 아예 없는 것"이 대시보드에서 구분돼야 한다
        assertThat(counter("accepted")).isZero();
        assertThat(counter("rejected")).isZero();
        assertThat(registry.get("rag_concurrency_acquire_wait").timer().count()).isZero();
        assertThat(registry.get("rag_concurrency_limit").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("rag_concurrency_in_use").gauge().value()).isZero();

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        assertThat(counter("accepted")).isEqualTo(1.0);
        assertThat(counter("rejected")).isEqualTo(1.0);
        assertThat(registry.get("rag_concurrency_acquire_wait").timer().count())
                .as("거절된 요청의 대기 시간도 기록돼야 한다")
                .isEqualTo(2);
        assertThat(registry.get("rag_concurrency_in_use").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한도 변경은 즉시 반영되고 'RAG' 스코프로 저장된다")
    void 한도_변경이_즉시_반영된다() {
        RagConcurrencyLimiter limiter = limiterWith(45, 300);

        limiter.updateLimits(1, 0, "test");

        assertThat(registry.get("rag_concurrency_limit").gauge().value()).isEqualTo(1.0);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        ArgumentCaptor<SearchConcurrencyConfig> saved = ArgumentCaptor.forClass(SearchConcurrencyConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getScopeName()).isEqualTo("RAG");
        assertThat(saved.getValue().getMaxConcurrent()).isEqualTo(1);

        limiter.release();
    }

    @Test
    @DisplayName("잘못된 한도값은 거부되고 적용되지 않는다")
    void 잘못된_한도값은_거부된다() {
        RagConcurrencyLimiter limiter = limiterWith(45, 300);

        assertThatThrownBy(() -> limiter.updateLimits(0, 300, "test"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.updateLimits(45, -1, "test"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(limiter.getLimits().maxConcurrent()).isEqualTo(45);
        assertThat(limiter.getLimits().acquireTimeoutMs()).isEqualTo(300);
    }
}
