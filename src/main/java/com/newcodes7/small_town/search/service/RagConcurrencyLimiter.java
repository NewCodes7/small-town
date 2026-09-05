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
 * <p><b>왜 90인가.</b> <b>first_token p99가 무부하(VU45) 대비 120%를 넘는 지점</b>이 VU90과 VU105
 * 사이이고, 90이 그 아래 최고 레벨이다 (p99 배수 1.09 / VU105는 1.27). 세 런에서 재현됐다
 * (1.01 · 0.97 · 1.09). 검색이 상한 15를 "실측 무릎의 상단"으로 잡은 것과 같은 방식이다.
 *
 * <p><b>귀속.</b> 이 값을 정한 건 <b>꼬리 지연 하나</b>다. 상한을 정한 런은 손잡이를 하나만 움직였다 —
 * Bedrock 풀 250·리미터 200을 둘 다 비구속으로 고정하고 VU만 바꿨다. VU105에서 풀은 안 닿았고
 * (llm_stream 최대 104/250), 힙·DB CPU(1.16/2.0)·앱 CPU·HikariCP 정상상태 타임아웃(0)이 전부 여유였다.
 * 남은 건 p99뿐이다. 근거: load-test/results/2026-08-29-rag-virtual-thread-ab.md 14장.
 *
 * <p>⚠️ <b>p95로 보면 안 된다.</b> 같은 구간에서 p95는 1.00 → 1.02 → 1.02 → 1.07로 평평해
 * VU120까지 전부 통과로 읽힌다. 동시성이 드러나는 곳은 중앙값도 p95도 아니라 꼬리다(9.3-1).
 *
 * <p><b>이전 값 45가 왜 틀렸나(순환).</b> 45의 근거는 "실측 SLA 무릎이 VU45"였는데, 그 사다리는
 * <b>리미터가 없던 상태</b>에서 돌았고 동시성을 막던 건 위의 풀 50뿐이었다. 스트림 21.4초·풀 50에서
 * VU70이면 20건이 대기하므로 예상 추가 대기 {@code 21.4 × 20/50 ≈ 8.6초}인데 실측 TTFT p95 증가가
 * 8.1초였다 — "붕괴"는 자원 벽이 아니라 풀의 큐잉이었다. 즉 45는 우리가 정한 값이 아니라
 * AWS SDK 기본값 50의 그림자였다. 90은 우리가 정한 판정 기준이 직접 가리킨 값이다.
 *
 * <p>⚠️ <b>{@code bedrock.async-max-concurrency}는 이 값보다 커야 한다</b>(현재 120 = 90+30).
 * permit이 컨트롤러 진입부터 스트림 종료까지 유지되므로 구조적으로
 * {@code rag_concurrency_in_use ≥ rag_answer_in_flight ≥ rag_answer_llm_stream_in_flight}이고,
 * 상한 L을 걸면 {@code llm_stream ≤ L}이 항상 성립한다. 풀이 L보다 작으면 초과분이 429가 아니라
 * SDK 풀 앞의 <b>조용한 대기</b>가 되어 셰딩이 관측되지 않는다. 여유 30칸에는 리미터를 타지 않는
 * 관리자 RAG 테스트 페이지 몫이 포함된다.
 *
 * <p>힙: 가상 스레드 기준 스트림당 0.94~1.31MB로 재측정됐으므로(2026-08-29 문서) 동시 90이면
 * 약 166 + 90×1.31 = 284MB, heap max 512 대비 1.8배 여유다. 14장 실측 heap used도 이 범위였다.
 *
 * <p>⚠️ <b>이 상한은 DB를 지켜주지 않는다</b> — 동시성 상한이지 RPS 상한이 아니기 때문이다.
 * DB 천장을 "10.3 RPS"로 고정 인용하면 안 된다. 요청당 DB 비용은 {@code bm25_index_docs_mutable}에
 * 따라 움직여서, 14장 시점(mut 46)의 천장 외삽치는 약 7.8 RPS였다. L=90은 그 51%다.
 * <b>LLM이 빨라지면 같은 90이 DB를 넘긴다</b>(rag-ladder 13.3).
 *
 * <p><b>300ms</b>: 상한 90 / 스트림 약 21초면 이탈률이 90/21 ≈ 4.3건/초라 300ms 창에 약 1.3건이
 * 빠져나간다 — 도착 지터를 흡수하되 큐를 만들지 않는 길이다. Tomcat이 virtual thread
 * ({@code spring.threads.virtual.enabled=true})라 대기 자체는 거의 공짜다.
 *
 * <p>⚠️ 아직 안 쟀다: <b>램프 속도</b>. 사다리에서 VU가 계단처럼 뛰는 전환 순간마다 HikariCP 풀(5)이
 * 순간 고갈돼 요청 몇 건이 실패했다(정상상태는 전 레벨 0). "동시 90까지 얼마나 빨리 올라가도 되는가"는
 * 별개 축이고, 300ms 창이 이걸 얼마나 흡수하는지는 미측정이다(14.8).
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
    private static final Limits DEFAULTS = new Limits(90, 300);

    public RagConcurrencyLimiter(
            SearchConcurrencyConfigRepository repository, MeterRegistry meterRegistry) {
        super(repository, meterRegistry, SCOPE_RAG, "rag_concurrency", "RAG", DEFAULTS);
    }
}
