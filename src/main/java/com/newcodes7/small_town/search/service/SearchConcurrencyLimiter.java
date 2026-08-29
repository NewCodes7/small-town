package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.global.concurrency.ConcurrencyLimiter;
import com.newcodes7.small_town.search.repository.SearchConcurrencyConfigRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * 검색 API 유입 제어 — 동시 실행 수를 세마포어로 상한 짓고 초과분은 429로 흘려보낸다.
 * 세마포어·메트릭·DB 폴백 등 본체는 {@link ConcurrencyLimiter} 참고.
 *
 * <p><b>왜 컨트롤러 진입점인가.</b> "실제로 비싼 계산(하이브리드 코어)만 제한한다"는 선택지도
 * 있었지만 불필요하다. 동시성 제한은 부하 적응적이라 싼 요청은 알아서 빠져나가고, 캐시 히트라고
 * 공짜도 아니다 — 모든 검색이 logSearchAsync로 5개짜리 DB 풀에 쓰기를 건다. 게다가 코어 안쪽에
 * 걸면 이미 single-flight future에 합류한 조인자들이 429가 아니라 빈 결과("검색 결과 없음")를 받게 된다.
 *
 * <p>메트릭: {@code search_concurrency_requests_total{result}}, {@code search_concurrency_in_use},
 * {@code search_concurrency_limit}, {@code search_concurrency_acquire_wait}.
 */
@Service
public class SearchConcurrencyLimiter extends ConcurrencyLimiter {

    public static final String SCOPE_SEARCH = "SEARCH";

    /**
     * DB 로드 실패 시 폴백. 15는 실측 무릎(VU10 정점 14.3 RPS, VU15에서 꺾임)의 상단이고,
     * 300ms는 순간 버스트를 흡수하되 큐를 만들지 않는 길이다.
     */
    private static final Limits DEFAULTS = new Limits(15, 300);

    public SearchConcurrencyLimiter(
            SearchConcurrencyConfigRepository repository, MeterRegistry meterRegistry) {
        super(repository, meterRegistry, SCOPE_SEARCH, "search_concurrency", "검색", DEFAULTS);
    }
}
