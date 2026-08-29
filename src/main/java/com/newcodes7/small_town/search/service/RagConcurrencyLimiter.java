package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.global.concurrency.ConcurrencyLimiter;
import com.newcodes7.small_town.search.repository.SearchConcurrencyConfigRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * RAG 채팅 유입 제어 — 동시 스트림 수를 세마포어로 상한 짓고 초과분은 429로 흘려보낸다.
 * 세마포어·메트릭·DB 폴백 등 본체는 {@link ConcurrencyLimiter} 참고.
 *
 * <p><b>왜 필요했나.</b> RAG에는 유입 제어가 없었고, 동시 스트림을 막고 있던 것은 Bedrock async
 * 클라이언트의 {@code maxConcurrency=50}(SDK 기본값)이었다 — 설계된 벌크헤드가 아니라 우연히 맞은 값이다.
 * 그걸 300으로 올리자 VU90부터 백엔드가 12분간 세 번 OOM으로 죽었고, DB CPU는 오히려 떨어졌다
 * (일이 실행된 게 아니라 큐에 쌓인 congestion collapse).
 * 근거 전문: load-test/results/2026-08-27-rag-ladder.md 5장 · 11장.
 *
 * <p><b>왜 45인가.</b> 두 가지가 같은 값을 가리킨다.
 * <ol>
 *   <li><b>실측으로 SLA를 지킨 최고 동시성이 VU45</b>다 — 런2/런3/런5/10.5에서 각각
 *       2.05 / 2.04 / 2.02 / 2.04 RPS로 네 번 재현됐고, 붕괴는 VU70에서 났다
 *       (first_token p95 7,176 → 15,284, +113%). 검색이 상한 15를 "실측 무릎의 상단"으로 잡은 것과 같은 방식이다.</li>
 *   <li><b>Bedrock async 풀(50)보다 낮아야 리미터가 실제로 셰딩을 한다.</b> permit은 컨트롤러 진입부터
 *       스트림 종료까지 유지되므로 구조적으로
 *       {@code rag_concurrency_in_use ≥ rag_answer_in_flight ≥ rag_answer_llm_stream_in_flight}이고,
 *       따라서 상한 L을 걸면 {@code llm_stream ≤ L}이 항상 성립한다. 45 &lt; 50이면 async 풀이 큐를
 *       만들 일이 없다 — 거절은 429로 즉시·관측 가능하게 일어나고 SDK 풀의 조용한 큐잉으로 새지 않는다.
 *       남는 5칸은 리미터를 타지 않는 관리자 RAG 테스트 페이지가 같은 풀을 쓰기 때문에 남긴 여유다.</li>
 * </ol>
 * 힙으로도 확인된다: {@code 166 + 45 × 1.87 = 250 MB} (10.5 실측 250 MB), heap max 512 대비 2.05배 여유.
 *
 * <p>⚠️ 이 값은 mock 지연(스트림 21.4초)에서 측정됐지만, 결과 문서 7장의 결론대로 <b>불변량은
 * 동시성이고 RPS가 아니다</b> — LLM이 빨라지든 느려지든 동시 45의 힙·풀 점유는 그대로다.
 * 실 Bedrock 경로로 옮겨지지 않는 것은 "45 ≈ 2.05 RPS"라는 파생값 쪽이다.
 *
 * <p><b>300ms</b>: 상한 45 / 스트림 약 21초면 이탈률이 45/21 ≈ 2.1건/초라 300ms 창에 약 0.6건이
 * 빠져나간다 — 도착 지터를 흡수하되 큐를 만들지 않는 길이다. Tomcat이 virtual thread
 * ({@code spring.threads.virtual.enabled=true})라 대기 자체는 거의 공짜다.
 *
 * <p>메트릭: {@code rag_concurrency_requests_total{result}}, {@code rag_concurrency_in_use},
 * {@code rag_concurrency_limit}, {@code rag_concurrency_acquire_wait}.
 * 기존 {@code rag_answer_in_flight}와의 차이는 "문을 통과한 수" vs "서비스가 실제로 처리 중인 수"다
 * (컨트롤러 구간과 입력 검증 탈락분만큼 전자가 크다).
 */
@Service
public class RagConcurrencyLimiter extends ConcurrencyLimiter {

    public static final String SCOPE_RAG = "RAG";

    /** DB 로드 실패 시 폴백. 값 근거는 클래스 Javadoc 참고. */
    private static final Limits DEFAULTS = new Limits(45, 300);

    public RagConcurrencyLimiter(
            SearchConcurrencyConfigRepository repository, MeterRegistry meterRegistry) {
        super(repository, meterRegistry, SCOPE_RAG, "rag_concurrency", "RAG", DEFAULTS);
    }
}
