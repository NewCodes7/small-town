# RAG에서 가상 스레드는 얼마를 벌어주는가 — 3팔 A/B — 2026-08-29

> `2026-08-27-rag-ladder.md`의 후속. 그 문서가 "현재 용량은 VU45 ≈ 2.05 RPS"를 확정했다면,
> 이 문서는 **그 용량이 가상 스레드 덕분인지**를 실측으로 가른다.

## 0. 왜 3팔인가

가상 스레드는 2026-07-04 커밋 `db44fb93`("refactor: virtual thread 설정")에서 도입됐다.
그 커밋이 바꾼 것은 세 가지다:

```
spring.threads.virtual.enabled       (없음=false) → true
server.tomcat.threads.max/min-spare  40 / 5       → 삭제(가상 스레드라 미적용)
searchExecutor                       TPE(3, 6, q50, CallerRuns) → newVirtualThreadPerTaskExecutor()
autocompleteExecutor                 TPE(6, 12, q100, CallerRuns) → newVirtualThreadPerTaskExecutor()
```

2팔(전/후)만 재면 나오는 수치는 정직하지 않다. **"6스레드 풀이 45보다 느리다"는 것은
가상 스레드의 효과가 아니라 풀 크기의 효과일 수 있기 때문이다.** 그래서 세 번째 팔을 넣었다.

| arm | `spring.threads.virtual.enabled` | tomcat `threads.max` | `searchExecutor` | 커밋 |
|---|---|---|---|---|
| **A** 개선 후 (현재 운영) | `true` | (가상, 미적용) | `newVirtualThreadPerTaskExecutor()` | `67828695` |
| **B** 개선 전 (과거 실제) | `false` | 40 | `ThreadPoolExecutor(3, 6, q50, CallerRuns)` | `451b0f0d` |
| **C** 대조군 | `false` | 200 (Boot 기본) | `newCachedThreadPool()` | (예정) |

- **A vs B** = 실제로 한 개선의 효과 (헤드라인)
- **A vs C** = 가상 스레드 <b>고유</b>의 효과. `newCachedThreadPool()`은 가상 스레드 executor와
  **의미가 같다** — 태스크당 스레드 하나, 상한 없음. 따라서 차이는 **스레드 타입 하나뿐**이다.
- **B vs C** = B의 손실 중 "풀이 작아서"인 몫

### 0.1 왜 `searchExecutor`가 RAG를 지배하는가

`RagChatController`/`RagChatLoadTestController`가 SSE 스트림 전체를 이 executor에 넘긴다:

```java
CompletableFuture.runAsync(() -> ragAnswerService.streamAnswerForLoadTest(...), searchExecutor)
        .whenComplete((v, t) -> ragConcurrencyLimiter.release());
```

그리고 `BedrockRagLlmClient`가 `.join()`으로 **스트림이 끝날 때까지(mock 기준 약 21초)**
그 스레드를 블로킹한다. 즉 **최대 동시 스트림 수 = `searchExecutor`의 실효 동시성**이고,
`RagConcurrencyLimiter` 상한 45와는 무관하게 묶인다.

Tomcat 스레드는 지배 요인이 아니다 — 컨트롤러가 `SseEmitter`를 반환하는 순간 async dispatch로
요청 스레드가 반납되기 때문이다. 그래서 arm B의 `threads.max=40`은 스트림 길이를 붙잡지 않는다.
(다만 거절 경로는 `tryAcquire`의 300ms 대기 동안 요청 스레드를 문다 — VU90에서 볼 지점이다.)

### 0.2 단일 변수 유지

arm 간에 **스레드 모델만** 다르도록 다음은 세 팔 모두 동일하게 뒀다:

- `ContextExecutorService` 래핑 (trace context 전파) — 빼면 span 연결이 달라져 지연 해석이 오염된다
- `destroyMethod="close"` (종료 경로)
- `RagConcurrencyLimiter` 상한 **45**, `bedrock.async-max-concurrency` **50**
- HikariCP 풀 5, `hnsw.ef_search=250`, 벡터 임계값 0.6, TOP_ARTICLES 5 / CHUNKS_PER_ARTICLE 3

## 1. 측정 방법

`2026-08-27-rag-ladder.md` 1장의 방법을 그대로 쓴다. 달라진 것은 사다리뿐이다.

- 시나리오 `scenarios/ramp-limit-finder-rag.js`, `MODE=cache-miss`, mock 기본 페이싱(iteration ≈ 21초)
- 경로 `/api/rag/answer/loadtest` — 실경로와 **같은 리미터**를 탄다
- 사다리 **VU 5 / 15 / 45 / 90**, 레벨 7분, 간격 480초(= 60초 드레인), 과도구간 앞 120초 제외
- 완료는 `sse_terminal_total{terminal="done"}`으로만 센다 (200을 받고도 스트림이 안 끝날 수 있다)
- 판정 지연은 `sse_first_token`(TTFT). `sse_stream_duration`은 mock의 토큰 페이싱 상수가 지배한다
- 수집: `scripts/collect-rag-results.py`, 스레드·힙은 별도 질의(부록 A)

### 1.1 사다리를 왜 이렇게 잡았나

| VU | 의도 |
|---|---|
| 5 | 세 팔 모두 비포화 — **"같은 시스템을 재고 있다"는 등가성 확인** |
| 15 | arm B의 실효 동시성(6)을 넘김 — 격차가 열리는 지점 |
| 45 | 운영 상한 지점 — **헤드라인 수치** |
| 90 | 리미터 초과 — 셰딩 비용 비교 |

### 1.2 이번 런에서 고친 것 — k6 이미지 재빌드

`load-test/docker/Dockerfile`이 `scenarios/`를 이미지에 굽는다. 11.7에서 **두 번** 걸린 함정이라
이번에는 사다리를 돌리기 전에 다시 빌드해 push하고, 이미지 안의 파일 md5가 로컬과 같은지 확인했다:

```
8f7def79b47ddaeedd069c905b7efd71  scenarios/ramp-limit-finder-rag.js
c1ffafe554ad9d4e267309387b78d493  lib/sse.js
```

덕분에 이번에는 429 백오프(`REJECT_BACKOFF_MS=5000`, 서버 `Retry-After`와 같은 값)가 실제로 걸렸다 —
**VU90의 `shed` 수치를 이번에는 제공 부하로 읽을 수 있다.**

### 1.3 배경 조건

| | arm A | arm B | arm C |
|---|---|---|---|
| testid | `20260829-053841` | (예정) | (예정) |
| 창 (UTC) | 05:39~06:10 | (예정) | (예정) |
| 서버 | green | blue | (예정) |
| BM25 `segs` / `mut` | 8 / 18 | 8 / 18 | (예정) |

`segs`/`mut`가 같다는 것은 **BM25 인덱스 상태가 동일**하다는 뜻이다 — 세그먼트와 짝짓지 않은
용량값은 재현되지 않는다는 것이 검색 쪽에서 얻은 교훈이다.

---

## 2. arm A (개선 후, 가상 스레드) — `20260829-053841`

| VU | done | RPS | bad | shed(429) | ft50 | ft95 | sd50 | DB CPU | appCPU | core-s/req | blks/req | acq_s | pend | in_use max | llm_stream max |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 5 | 99 | 0.23 | 0 | 0 | 4,392 | 6,890 | 21,981 | 0.084 | 0.021 | 0.355 | 24,641 | 0.0004 | 0 | 5 | 5 |
| 15 | 292 | 0.68 | 0 | 0 | 4,300 | 6,861 | 22,043 | 0.190 | 0.042 | 0.273 | 23,190 | 0.0003 | 0 | 15 | 15 |
| 45 | 871 | **2.01** | 0 | 0 | 4,398 | 7,153 | 21,971 | 0.480 | 0.086 | 0.232 | 22,635 | 0.0007 | 0 | 45 | 45 |
| 90 | 879 | 1.99 | 0 | 3,552 | 4,488 | 7,266 | 22,151 | 0.502 | 0.095 | 0.240 | 21,324 | 0.0026 | 0 | 45 | 45 |

**세 가지가 확인된다.**

**(1) VU45까지 처리량이 선형이다.** 0.23 / 0.68 / 2.01은 각각 VU/21.7, VU/22.1, VU/22.4다 —
Little's Law(`λ = L/W`)에서 W가 일정하다는 뜻이고, 부하가 올라도 요청이 느려지지 않았다는 것이다.

**(2) 지연이 부하와 무관하다.** ft50이 4,392 / 4,300 / 4,398 / 4,488로 **전 레벨 평평**하다.
ft95도 6,890~7,266 안에 있다. 큐가 생기지 않았다는 직접 증거다(`pend` 0, `acq_s` 0.4~2.6ms).

**(3) 11.6이 재현됐다.** VU45의 2.01 RPS는 이틀 전 11.6의 **2.01과 정확히 같다.**
다른 날, 다른 컨테이너, 다른 사다리에서 같은 값이 나왔다 — 이 측정계가 신뢰할 만하다는 뜻이다.

시간 불변량도 안정적이다: `blks/req` 24,641 → 21,324(13% 감소, 레벨이 오를수록 PG 버퍼가 뜨거워지는
방향이라 정상), `core-s/req` 0.355 → 0.240.

### 2.1 스레드·힙 (arm A)

| VU | live threads (max) | live (avg) | peak | heap used MB | heap committed MB |
|---|---|---|---|---|---|
| 5 | 62 | 59.3 | 67 | 293.0 | 310.0 |
| 15 | 67 | 63.4 | 68 | 211.7 | 231.0 |
| 45 | 66 | 65.1 | 68 | 274.8 | 296.0 |
| 90 | 67 | 65.0 | 68 | 281.4 | 303.0 |

**플랫폼 스레드 수가 동시성과 무관하게 평평하다** (62 → 67, VU를 18배 올리는 동안 +5).
`jvm_threads_live_threads`는 플랫폼 스레드만 세므로, 동시 스트림 45개가 **캐리어를 물지 않고
언마운트되어 있다**는 것이 이 숫자로 보인다. 가상 스레드의 정의 그대로다.

---

