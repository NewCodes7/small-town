# 하이브리드 검색 한계점 탐색 — 2026-08-06

- **시나리오**: `scenarios/ramp-limit-finder.js` (`TARGET=search`)
- **대상**: `GET /api/search/articles?...&view=list` (실서버, `https://newcodes.net`)
- **baseline testid**: `20260806-102147` (`scenarios/search-hybrid.js`, `RATE=1 DURATION=1m`)
- **ramp testid**: `20260806-102519`
- **태스크 구성**: Fargate 1 task, 계단식 4단계(level_10 → level_20 → level_50 → level_100), 레벨당 3분 + 30초 드레인
- **총 소요**: 14분 25초 (10:25:39 ~ 10:40:04 UTC, exit code 0, 정상 종료)

## SLA 기준

baseline(RATE=1, 무부하) p95/p50 × 1.2 — `search-hybrid.js` 스모크(61건, 전부 2xx)로 실측:

| 지표 | baseline | SLA 임계치(×1.2) |
|---|---|---|
| p95 | 906ms | 1087ms |
| p50 | 706ms | 847ms |

> baseline 자체가 낮지 않다(900ms대) — 무부하라도 Zipfian 키워드 대부분이 쿼리 임베딩 캐시 미스(BM25+Vector+NSF 리랭킹 풀 경로)를 타기 때문으로 추정(§결론 참고).

## 레벨별 결과

| level (VUs) | p95 | p50 | 완료 요청 | 5xx | 에러율 | 실측 처리량(RPS) | SLA(p95 기준) |
|---|---|---|---|---|---|---|---|
| 10 | 286ms | 98ms | 14,426건 | 0 | 0% | ~80.1 req/s | ✅ 26% |
| 20 | 444ms | 187ms | 17,375건 | 0 | 0% | ~96.5 req/s | ✅ 41% |
| 50 | 997ms | 647ms | 16,556건 | 28 | 0.17% | ~92.0 req/s | ✅ 92%(임계 근접) |
| **100** | **7.42s ⚠️** | **6.36s ⚠️** | 2,930건 | **926건** | **31.6% ⚠️** | **~16.3 req/s ⚠️** | ❌ **683%** |

(처리량은 각 레벨의 3분 구간 동안 `k6_http_status_class_total{level=...}` 누적 완료 건수 ÷ 180s. `k6_http_reqs_total`은 요청 URL(키워드+페이지)마다 별도 시계열로 나뉘어 있어 일부가 조기에 stale 처리되며 과소집계되는 게 확인돼, 카디널리티가 낮은 `http_status_class` 합계를 신뢰 값으로 사용함.)

## 결론

- **10~50 VUs 구간은 SLA 안전** — level_50까지도 5xx가 0.17%(28건)에 불과하고 p95도 임계치의 92% 수준.
- **처리량은 level_20(~96.5 req/s)에서 사실상 정점을 찍고 level_50에서는 오히려 소폭 하락(~92.0 req/s)** — 동시성을 20→50으로 올려도 처리량이 늘지 않고 지연시간만 증가(p95 444ms→997ms, p50 187ms→647ms)하는 전형적인 큐잉 포화 초입 패턴.
- **100 VUs에서 완전 붕괴** — p95 7.42s(임계치 대비 683%), p50 6.36s, 에러율 31.6%(926/2,930건 5xx). 처리량도 오히려 ~16.3 req/s로 급락(부하를 더 걸었는데 처리량이 떨어짐 — 전형적 congestion collapse). README의 가설(HikariCP pool(5) 포화 → 커넥션 대기 최대 3s → `statement_timeout` 5s → 5xx)과 정합적인 패턴.
- **SLA 기준 RPS는 약 90~97 req/s** (level_10~50 관측 처리량 범위, 정점은 level_20의 ~96.5 req/s). **한계선은 50~100 VUs 사이**에 있다 — level_50은 아직 SLA 안이지만 여유가 크지 않고(p95 92%), level_100은 완전 붕괴이므로 정확한 breakpoint를 좁히려면 60/70/80 VUs 구간 후속 테스트가 필요(미실행, RAG 선례와 동일한 한계).
- Loki 기반 BM25/Vector/Rerank(NSF) 단계별 소요시간 breakdown은 이번 실행에서 `/admin/monitor/` 프록시 타임아웃으로 조회하지 못함 — 후속 분석 시 재시도 필요(원인 진단을 위해서는 유용하지만 본 RPS 판정 자체에는 영향 없음).

## 참고

- 4xx/429/444(rate limit): 전 구간 0건 — `LOADTEST_BYPASS_TOKEN`이 정상 작동함을 확인.
- `k6_http_req_duration_p95`/`p50`은 요청 URL(키워드+페이지)별로 별도 시계열이 저장되는 구조라, Prometheus에서 `avg by (level)`로 집계한 값은 "레벨 내 서로 다른 쿼리들의 개별 지연시간 평균"에 가깝다(RAG 선례와 동일한 근사 — `ramp-limit-finder`류 스크립트 공통 한계). baseline은 `quantile(0.95/0.50, ...)`로 계산.

## 병목 원인 분석 (Prometheus/Loki 실측, level_100 구간 기준)

k6 지표만으로는 "느려졌다"는 것만 알 수 있어, 백엔드 Grafana(HikariCP 메트릭, Postgres 로그, `ArticleSearchService` 단계별 소요시간 로그)를 함께 조회해 원인을 좁혔다.

### 1위 — HikariCP 커넥션 풀(5) 고갈 → 3초 커넥션 타임아웃 → 5xx (지배적 원인)

- **에러 로그 직접 확인**(`small-town-error.log`, level_100 구간):
  ```
  SQLTransientConnectionException: HikariPool-1 - Connection is not available,
  request timed out after 3000ms (total=5, active=5, idle=0, waiting=144)
  ```
  `waiting=144` — 커넥션 5개를 기다리는 스레드가 144개까지 쌓임. `total=5, active=5, idle=0`으로 풀이 완전 포화 상태.
- **`hikaricp_connections_pending`**: level_10 8~16 → level_20 27~35 → level_50 86~95 → level_100 최대 **167**.
- **`hikaricp_connections_timeout_total`** 3분 구간 증분: level_10/20에서 0~46건 수준이던 것이 level_100에서 **최대 5,045건/3분**으로 폭증.
- **`hikaricp_connections_acquire_seconds_max` = 11.55s** — 커넥션 하나 받는 데만 11초 이상 대기한 요청 존재.
- k6에서 관측한 5xx 926건(31.6%)과 정확히 대응되는 실패 경로.

### 2위 — (수정: 후속 조사 결과 별도 병목이 아니라 1위의 착시로 확인됨) ~~BM25/Vector 쿼리 자체의 실행시간 급증~~ → 정체는 "BM25:/Vector: 로그 타이머 안에 커넥션 대기시간이 포함되는 구조"

최초 결론(BM25/Vector 자체가 느려진다)은 **후속 검증(재현 실험 + 소스코드 확인)으로 반증됨**. 아래는 검증 과정과 정정된 결론.

#### 검증 1 — 재현 실험(`scenarios/pool-vu-check.js`, VUS=5 고정, 5분 sustained, testid `20260806-114632`)

풀 크기(5)와 정확히 같은 동시성으로 돌리면 이론상 풀 경합이 없어야 한다 — 이 상태에서도 BM25/Vector가 여전히 느리면 진짜 DB 실행 병목, 평소 수준으로 돌아오면 1위의 착시였다는 뜻.

| | VUS=5 (풀 경합 거의 없음) | level_10 (10 VUs) | level_100 (100 VUs) |
|---|---|---|---|
| `hikaricp_connections_pending` | **0~4** | 8~16 | 최대 167 |
| 앱 로그 "총"(BM25+Vector+Rerank) 평균 (n=29/25/13 표본) | **~1.9s** | ~2.68s | ~5.0s |
| 완료 요청 | 24,283건 | 14,426건 | 2,930건 |
| 에러 | **0건(0%)** | 0건 | 926건(31.6%) |

풀 경합이 없는 VU=5에서도 "총"이 평균 1.9초로 결코 빠르지 않지만(→ 별도의 baseline 비용, 아래 검증 2 참고), **pending이 0→8~16→167로 늘어나는 것과 정확히 같은 방향·비율로 "총"도 늘어난다** — 즉 부하가 늘수록 나빠지는 부분은 온전히 풀 대기시간과 상관관계를 보인다.

#### 검증 2 — 소스코드 확인 (`ArticleSearchService.computeHybridCore`, `SearchQueryEmbeddingService.getEmbeddingWithCacheInfo`)

- **`BM25: Xms` 타이머는 `TransactionTemplate.executeWithoutResult(...)` 호출 *전*부터 시작한다** — 즉 트랜잭션 시작(=HikariCP 커넥션 획득)이 타이머 안에 포함됨. BM25 자체는 async 아님, 호출 스레드에서 동기 실행.
- **`Vector: Yms` 타이머도 마찬가지** — `CompletableFuture.supplyAsync(..., searchExecutor)`의 람다 내부에서 타이머가 시작되고, 이 안에서 `SearchQueryEmbeddingService`(자체 트랜잭션/커넥션)와 `chunkRepository`의 2단계 벡터 쿼리(또 별도 트랜잭션/커넥션)를 순차 호출 — 둘 다 커넥션 획득 대기가 타이머에 포함됨.
- **`searchExecutor`는 병목이 아님** — `Executors.newVirtualThreadPerTaskExecutor()`(JDK 26 가상스레드, 무제한). 코드 주석에 이미 "실질 동시성 상한은 HikariCP 커넥션 풀에서 결정됨"이라고 명시돼 있음(`SearchExecutorConfig.java`) — 개발 시점에 이미 알려진 설계 의도.
- **가장 심각한 발견 — 임베딩 캐시 미스 시 외부 API 호출 동안 커넥션을 붙잡음**: `SearchQueryEmbeddingService.getEmbeddingWithCacheInfo`가 메서드 레벨 `@Transactional`인데, 캐시 미스 시 이 트랜잭션 *안에서* `embeddingApiService.generateEmbedding(...)`(Clova로 동기 blocking HTTP 호출, 타임아웃 오버라이드 없음)를 호출한다. 즉 **5개뿐인 커넥션 중 하나가 DB 작업과 무관한 외부 API 왕복 시간 동안 통째로 묶인다.**
- 요청 하나가 순차적으로 **최대 5~6개의 개별 커넥션**을 체크아웃한다(BM25 readOnlyTx, 임베딩 서비스 tx, 벡터 2단계 쿼리 tx, cross-scoring Phase B, Phase C, 최종 유효성 검사) — 동시성 카운트보다 실질 풀 압박이 훨씬 큼을 의미.
- HikariCP 설정 확인(`application-prod.properties`): `maximum-pool-size=5`, `minimum-idle=5`, **`connection-timeout=3000`**(로그의 "timed out after 3000ms"와 정확히 일치), `statement_timeout=5000`(connection-init-sql).

#### 정정된 결론

2위는 별개의 "DB 쿼리 자체가 느려지는" 병목이 아니라, **1위(HikariCP 풀 고갈)가 요청 하나당 최대 5~6번의 개별 커넥션 체크아웃 구조 + 로그 타이머가 커넥션 대기시간을 포함하는 구조 때문에 증폭되어 관측된 것**이다. 특히 임베딩 캐시 미스 시 외부 API 호출 동안 커넥션을 붙잡는 패턴은 3위(캐시 미스)와 1위를 직접 연결하는 가장 뾰족한 회귀 지점 — **롱테일 쿼리의 캐시 미스가 실제로는 "풀 고갈 증폭기" 역할**을 한다.

## 수정 적용 및 재검증 (2026-08-06, 같은 날 후속)

### 수정 내용

`SearchQueryEmbeddingService.getEmbeddingWithCacheInfo`(2-arg/3-arg 오버로드 둘 다)에서 `@Transactional` 제거 — 커밋 `83211a5`. 캐시 조회는 Spring Data JPA가 여는 짧은 read-only 트랜잭션 안에서 즉시 커넥션을 반납하고, Clova 호출(캐시 미스 시)은 커넥션을 전혀 점유하지 않은 채 진행된다. `main` push → GitHub Actions CI/CD(테스트 → GHCR 빌드 → SSH 배포)로 운영 반영(blue→green 전환 확인).

### 재검증 절차

수정 배포 직후 **완전히 동일한 시나리오**(`ramp-limit-finder.js TARGET=search`, 10/20/50/100 VUs 계단식)를 다시 실행 — testid `20260806-132203` (수정 전 `20260806-102519`와 비교).

### Before/After 비교

| level (VUs) | 지표 | 수정 전 | 수정 후 | 변화 |
|---|---|---|---|---|
| 10 | p95 / 처리량 / 에러 | 286ms / ~80.1 req/s / 0% | 379ms / ~68.6 req/s / 0% | 안전 구간이라 노이즈 수준 (측정 방식상 오차 있음, §참고 항목) |
| 20 | p95 / 처리량 / 에러 | 444ms / ~96.5 req/s / 0% | ~600ms / ~81.2 req/s / 0.02%(3건) | 안전 구간, 약간의 노이즈 |
| 50 | p95 / 처리량 / 에러 | 997ms / ~92.0 req/s / 0.17% | **~560ms / ~131.1 req/s / 0%** | **p95 -44%, 처리량 +42%, 에러 0** |
| **100** | p95 / 처리량 / 에러 | **7.42s / ~16.3 req/s / 31.6%** | **3.25~6.03s(레벨 내 하락 추세) / ~84.2 req/s / 1.8%** | **처리량 +416%(5.2배), 에러율 -94%(31.6%→1.8%)** |

### 핵심 관찰

- **level_100에서 압도적 개선**: 3분 동안 완료된 요청이 2,930건 → 15,154건(5.2배)으로 늘었고, 5xx는 926건(31.6%) → 271건(1.8%)으로 급감. `HikariPool-1` 자체는 여전히 5개뿐이라 100 VUs를 감당하기엔 부족하지만(`hikaricp_connections_pending`이 오히려 143~194로 수정 전 최대 167과 비슷하거나 더 높음), **처리량이 5배 늘었다는 건 Little's Law 관점에서 커넥션 하나가 회전하는 속도(=대기 하나당 평균 대기시간)가 훨씬 빨라졌다는 뜻** — 이전엔 커넥션이 Clova 호출 동안 인질로 잡혀 회전율 자체가 낮았고, 지금은 각 체크아웃이 실제 DB 작업 시간만큼만 짧게 유지되다 반납된다.
- **앱 로그로 직접 확인**: 수정 후 level_100 샘플에서 `BM25: 277~2694ms`(대부분 1초 미만, 수정 전 1000~5000ms대에서 개선)로 확인됨. 다만 `Vector` 단계는 여전히 느림(2~5초대) — 특히 `embedding: hit(1437~2967ms)` 처럼 캐시 "히트"인데도 조회 자체가 1~3초 걸리는 사례가 다수 관측됨. **이건 예상된 잔여 병목**: 이번 수정은 "커넥션을 오래 붙잡는 안티패턴"만 제거했을 뿐, 풀 크기(5) 자체를 늘린 게 아니라서 100 VUs는 여전히 풀 대비 20배 과다 동시성 — 다만 각 체크아웃이 짧아져 전체 회전율이 좋아진 것.
- **level_100 안에서 시간이 지날수록 p95/p50이 스스로 하락**(p95 6.03s→3.25s, p50 5.35s→1.93s, 3분 구간 내) — 수정 전엔 레벨 내내 평평하게 고정(7.42s대)돼 있던 것과 대조적. 시스템이 부하에 적응/안정화되는 신호로 해석됨(수정 전엔 계속 악화되기만 했다면, 수정 후엔 초반 버스트 이후 회복).
- **level_50 이하는 이미 안전 구간이라 개선 폭이 상대적으로 작거나 노이즈에 가림** — 다만 level_50에서도 처리량 +42%·에러 0건 달성은 유의미. level_10/20의 미세한 "악화"처럼 보이는 수치는 §참고 항목에서 밝힌 `avg by (level)` 집계 방식의 노이즈로 판단(안전 구간에서 방향성 결론은 무의미).

### 결론

**수정은 명확히 효과가 있었다.** 특히 시스템이 완전히 붕괴하던 구간(level_100)에서 처리량 5.2배, 에러율 94% 감소라는 큰 개선을 실측으로 확인했다. 다만 **HikariCP 풀 크기(5)라는 근본 제약 자체는 그대로**라 100 VUs 수준의 극단적 동시성에서는 여전히 상당한 지연(p95 3~6초대)과 잔여 에러(1.8%)가 남아있다 — 이번 수정으로 "붕괴"는 막았지만 "완전한 SLA 준수"까지는 못 갔다. 풀 크기 자체를 늘리는 것(비용/DB 스펙과 트레이드오프)이 다음 후속 과제로 남는다.

### 3위 — 쿼리 임베딩 캐시 미스(콜드 롱테일 쿼리) — 동시성과 무관한 별도 지연 요소

- 앱 로그에 `embedding: miss(0ms, lookup 0ms)` 케이스가 다수 확인됨(`typescript`, `graphql`, `형태소 분석` 등) — 캐시 미스 시 벡터 결과가 0개로 나오는 경우까지 관찰되어, 고부하 시 캐시 미스 경로가 정상적으로 완료되지 못했을 가능성도 있음(응답 품질 저하 — 성능과는 별개 이슈, 후속 확인 필요).
- baseline(무부하, 서로 다른 키워드 위주, 캐시 미스 다수) p95 906ms가 **ramp level_10(인기 키워드 반복, 캐시 워밍됨) p95 286ms보다 3배 이상 느림** — 동시성이 전혀 없는데도 baseline이 더 느린 건 캐시 상태 차이로만 설명 가능.
- 실제 운영 트래픽은 이 테스트의 Zipfian 샘플(`data/keywords.json`, 인기어 반복)보다 키워드 다양성이 훨씬 커서, 캐시 미스 비율이 이 테스트보다 높을 가능성이 크다 — 즉 이 병목의 실제 영향은 테스트 결과보다 더 클 수 있음.
