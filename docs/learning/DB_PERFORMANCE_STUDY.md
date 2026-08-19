# 백엔드 성능 최적화를 위한 DB 학습 자료

**검색 부하테스트에서 겪은 12개의 병목을, 원리 → 재현 → 실측 → 면접 답변까지 이어붙인 문서**

작성: 2026-08-17 · 대상 시스템: NewCodes 하이브리드 검색 (Spring Boot 4.1 / JDK 26 /
PostgreSQL 17.10 + pgvector 0.8.2 + ParadeDB pg_search) · DB 호스트 2 vCPU / 1GB RAM

---

## 이 문서를 읽는 법

이 문서는 세 종류의 문장을 섞어 쓴다. 구분해서 읽으면 좋다.

| 표시 | 뜻 |
|---|---|
| **원리** | 외부 1차 출처(공식 문서 등)로 뒷받침되는 일반적 사실. 링크가 붙어 있다. |
| **실측** | 이 프로젝트에서 직접 측정한 값. 저장소의 어느 문서/커밋에서 나왔는지 표시했다. |
| **판단** | 그 상황에서 내가 내린 선택과 근거. 다른 환경에서는 다를 수 있다. |

숫자를 인용할 때는 "어디서 잰 값인지"를 항상 같이 적었다. 면접에서 숫자를 말할 때
가장 위험한 건 틀린 숫자가 아니라 **출처가 없는 숫자**다. "합성 데이터 벤치에서 잰
값입니다", "프로덕션 부하테스트 1회 실행이라 반복 검증은 못 했습니다" 같은 단서를
붙일 수 있느냐가 실무 경험의 진위를 가른다.

### 별점의 의미

각 장 머리에 붙은 별점은 **면접에서 나올 확률 × 답변 난이도**다.

- **★★★** — 백엔드 면접 단골. 안 나오면 이상한 주제. 원리부터 트레이드오프까지 말할 수 있어야 한다.
- **★★** — 나오면 확실히 가산점. 특히 "성능 최적화 경험 있나요?"의 답변 재료.
- **★** — 배경지식. 먼저 꺼낼 필요는 없지만 꼬리질문에 막히면 안 되는 것.

---

## 목차

**0부. 지도**
- [0.1 내 경험 12개 → 원리 매핑](#01-내-경험-12개--원리-매핑)
- [0.2 전체 이야기: 병목은 세 번 옮겨 다녔다](#02-전체-이야기-병목은-세-번-옮겨-다녔다)

**1부. 계기판 — 병목을 어떻게 특정하는가**
- [1장. 처리량·지연·포화도의 언어](#1장-처리량지연포화도의-언어-)
- [2장. EXPLAIN (ANALYZE, BUFFERS) 읽는 법](#2장-explain-analyze-buffers-읽는-법-)
- [3장. pg_stat_statements와 "안 잡히는 비용"](#3장-pg_stat_statements와-안-잡히는-비용-)
- [4장. 블록 회계 — 요청당 132MB를 관계 단위로 쪼개기](#4장-블록-회계--요청당-132mb를-관계-단위로-쪼개기-)
- [5장. 통계와 autovacuum](#5장-통계와-autovacuum-)

**2부. 쿼리와 플래너**
- [6장. 프리페어드 스테이트먼트와 플랜 캐시](#6장-프리페어드-스테이트먼트와-플랜-캐시-)
- [7장. CTE 인라인과 MATERIALIZED](#7장-cte-인라인과-materialized-)
- [8장. 인덱스가 있는데 왜 안 타는가](#8장-인덱스가-있는데-왜-안-타는가-)
- [9장. 중복 검증 조인 제거](#9장-중복-검증-조인-제거-)
- [10장. N+1](#10장-n1-)

**3부. 트랜잭션과 커넥션**
- [11장. 커넥션 풀 사이징](#11장-커넥션-풀-사이징-)
- [12장. 트랜잭션 스코프 최소화](#12장-트랜잭션-스코프-최소화-)
- [13장. OSIV](#13장-osiv-)
- [14장. 커넥션 점유 회계](#14장-커넥션-점유-회계-)

**4부. 서버 파라미터**
- [15장. 병렬 쿼리를 끄다](#15장-병렬-쿼리를-끄다-)
- [16장. 나머지 레버들](#16장-나머지-레버들-)

**5부. 검색 인덱스의 물리**
- [17장. 벡터 검색 2단계 퍼널](#17장-벡터-검색-2단계-퍼널-)
- [18장. 세그먼트 인덱스의 쓰기 버퍼 vs 읽기 지연](#18장-세그먼트-인덱스의-쓰기-버퍼-vs-읽기-지연-)
- [19장. TOAST](#19장-toast-)

**6부. 애플리케이션 레벨**
- [20장. 계산 재활용의 함정](#20장-계산-재활용의-함정-)
- [21장. 병렬화](#21장-병렬화-)
- [22장. 캐시 계층](#22장-캐시-계층-)

**7부. 방법론**
- [23장. A/B 판정 프로토콜](#23장-ab-판정-프로토콜-)
- [24장. 오진 3건 복기](#24장-오진-3건-복기-)

**8부. 면접 대비**
- [25장. 예상 질문 25개와 답변 골격](#25장-예상-질문-25개와-답변-골격)
- [26장. 숫자 암기 카드](#26장-숫자-암기-카드)
- [27장. 용어집](#27장-용어집)
- [28장. 참고자료](#28장-참고자료)

---
---

# 0부. 지도

## 0.1 내 경험 12개 → 원리 매핑

| # | 내가 한 일 | 핵심 원리 | 장 | 면접 |
|---|---|---|---|---|
| 1 | `max_parallel_workers_per_gather=0` | 병렬 쿼리의 프로세스 회계, 오버서브스크립션 | 15 | ★★ |
| 2 | `paradedb.global_mutable_segment_rows` 1000→50 | 세그먼트 인덱스의 write buffer vs read latency | 18 | ★★ |
| 3 | 벡터 파라미터 행별 재파싱 제거 (`AS MATERIALIZED`) | generic plan + CTE 인라인의 결합 사고 | 6, 7 | ★★★ |
| 4 | 유의어 조회 N+1 → 조인 1쿼리 | N+1, 커넥션 체크아웃, 직렬 지연 | 10 | ★★★ |
| 5 | 유의어 확장 `OR` → `UNION ALL` + 인덱스 추가 | 인덱스를 못 타는 조건 형태, FK 인덱스 | 8 | ★★★ |
| 6 | 벡터 쿼리의 중복 `article` 조인 제거 | 검증 위치 설계, 블록 회계 | 9 | ★★ |
| 7 | 트랜잭션에서 외부 API·CPU 작업 분리 | 트랜잭션 스코프 ≠ 비즈니스 스코프 | 12 | ★★★ |
| 8 | OSIV 해제 | 커넥션 리스 시간, 지연 로딩의 대가 | 13 | ★★★ |
| 9 | 1단계 후보 벡터 점수를 캐시로 재활용 | 계산 재활용과 추정량 일관성 | 20 | ★★ |
| 10 | 벡터 보충에도 2단계(HNSW 퍼널) 적용 | 근사 검색의 단계화, recall/비용 트레이드오프 | 17 | ★★ |
| 11 | Clova 임베딩 호출과 유의어 확장 쿼리 병렬화 | I/O 대기 겹치기, 트랜잭션과 비동기의 상호작용 | 21 | ★★ |
| 12 | (위 전부를 판정한) 부하테스트 A/B 프로토콜 | 변인 분리, 과도구간, 예측 선언 | 23, 24 | ★★ |

## 0.2 전체 이야기: 병목은 세 번 옮겨 다녔다

성능 작업을 "느린 걸 빠르게 만드는 일"로 이해하면 절반만 맞다. 실제로 한 일의 대부분은
**지금 무엇이 한계를 정하고 있는지 다시 판정하는 것**이었고, 그 답은 세 번 바뀌었다.

```
1기) "커넥션 풀(5)이 작다"        → 풀을 8/10/15로 올려봤더니 10부터 오히려 나빠졌다
                                    → 진짜 원인은 커넥션을 오래 붙잡는 코드였다

2기) "DB CPU가 천장이다"          → 요청당 DB CPU를 측정하니 2코어 ÷ 0.68 = 2.94 rps
                                    → 쿼리 비용을 실제로 깎기 시작 (재파싱·N+1·OR·조인)

3기) "일도 안 하면서 잡고 있다"   → 커넥션 점유 시간 중 쿼리가 도는 비율이 40%뿐이었다
                                    → OSIV를 걷어내니 포화 처리량이 2배
                                    → 그러자 병목이 다시 DB CPU로 돌아왔다
```

이 순환이 성능 작업의 정상적인 모습이다. **병목은 없어지지 않고 이동한다.** 하나를 고치면
다음 것이 드러난다. 면접에서 "성능을 몇 % 개선했다"보다 훨씬 강한 답변은
**"무엇이 병목인지 어떻게 판정했고, 고친 뒤 병목이 어디로 옮겨갔는지"** 를 말하는 것이다.

> 저장소 근거: [`docs/operations/DB_LOAD_REDUCTION.md`](../operations/DB_LOAD_REDUCTION.md),
> [`docs/operations/PGSS_SEARCH_COST.md`](../operations/PGSS_SEARCH_COST.md),
> [`load-test/results/2026-08-17-osiv-connection-hold-ab.md`](../../load-test/results/2026-08-17-osiv-connection-hold-ab.md)

---
---

# 1부. 계기판 — 병목을 어떻게 특정하는가

> 이 부는 "내 경험"이 아니라 **경험을 판정할 수 있게 만든 도구들**이다. 여기가 흔들리면
> 뒤의 모든 개선이 "그냥 그런 것 같았다"가 된다. 면접에서도 개선 항목 자체보다
> **"그게 개선인 걸 어떻게 알았나요?"** 에서 갈린다.

## 1장. 처리량·지연·포화도의 언어 ★★

> **한 줄 요약** — 지연(latency)과 처리량(throughput)은 다른 축이고, 둘을 잇는 것은 동시성이다.
> **내 경험** — VU 사다리(1/2/5/10)로 포화점을 찾고, 그 포화점이 어디로 이동하는지로 개선을 판정했다.

### 1.1 세 가지 지표

- **지연(latency)** — 요청 하나가 끝나는 데 걸린 시간. p50/p95/p99로 본다. 평균은 거의 쓸모없다.
  꼬리(p95)가 길어지는 것이 사용자가 체감하는 "느려짐"이다.
- **처리량(throughput, RPS)** — 초당 처리한 요청 수.
- **포화도(saturation)** — 자원이 대기열을 만들기 시작한 정도. 커넥션 풀 대기 시간,
  DB의 실행 중 세션 수, CPU run queue 같은 것.

이 셋의 관계가 **Little's Law**다:

```
평균 동시 처리 건수 L  =  도착률 λ (RPS)  ×  평균 체류 시간 W (latency)
```

[Little's Law](https://en.wikipedia.org/wiki/Little%27s_law)는 대기열의 형태와 무관하게
성립하는 항등식이다. 성능 작업에서 이 식을 쓰는 방식은 보통 이렇게 뒤집는 것이다:

> **동시에 쓸 수 있는 자원 L이 고정되어 있으면(예: 커넥션 풀 5), 처리량 λ는 체류 시간 W에
> 반비례한다. λ = L / W.**

이게 이 문서 전체를 관통하는 식이다. 커넥션 풀 5개로 요청당 커넥션을 430ms 붙잡으면
이론상 초당 `5 / 0.430 = 11.6`건이 한계다. 점유를 195ms로 줄이면 `5 / 0.195 = 25.6`건이 된다.
**쿼리를 하나도 안 고쳐도 처리량이 2배가 되는 이유가 이 나눗셈에 있다** (13장).

### 1.2 사다리(ramp) 테스트: 포화점을 찾는 도구

부하테스트를 "몇 명까지 버티나"로만 쓰면 정보가 거의 안 나온다. 유용한 건 **VU(가상 사용자)를
계단식으로 올리며 RPS 곡선의 모양을 보는 것**이다.

```
VU  1 → 2 → 5 → 10 → 20 → …
RPS 2.6  5.6  5.1   2.3     …
             ^^^^^^^^^^^^
             정점을 지나 꺾이면 = 포화
```

곡선 모양이 알려주는 것:

| 모양 | 해석 |
|---|---|
| VU를 올려도 RPS가 비례해 오른다 | 아직 포화 아님. **지연 바운드** 구간 (자원 여유 있음) |
| RPS가 평평해진다 | 어떤 자원이 100% 찼다. 그 자원이 병목 |
| RPS가 **꺾여 내려간다** | 포화를 넘어 경합 비용이 붙었다 (컨텍스트 스위칭, 락, 타임아웃 재시도) |

**실측 (2026-08-17)**: OSIV 해제 전에는 정점이 VU2(5.57 RPS)였고 VU5부터 무너졌다.
해제 후에는 정점이 VU5(11.35 RPS)로 이동했다. **"정점의 위치와 높이가 같이 바뀌었다"**는 것이
"자원 하나가 풀렸다"는 서명이다.

> 판정 팁 — 정점에서의 **커넥션 획득 대기 시간**을 같이 봐야 한다. 정점에서 대기가 0에
> 가까우면 풀은 병목이 아니고, 대기가 길면 풀이 병목이다. 우리 케이스는 개선 후
> 정점(VU5)의 획득 대기가 **0.001초**여서 "이제 풀은 병목이 아니다"라고 말할 수 있었다.

### 1.3 요청당 자원 소비 — RPS보다 예민한 지표

RPS는 노이즈가 크다. 우리 환경에서는 같은 구성을 두 번 돌려도 ±11%가 흔들렸다.
그래서 판정 지표를 바꿨다:

```
core-s/req = (DB 호스트가 쓴 CPU 초) ÷ (그 구간 처리한 요청 수)
blks/req   = (쿼리들이 만진 8KB 블록 수) ÷ (요청 수)
```

**실측**: 커넥션 체크아웃 2회 vs 3회를 RPS로는 못 갈랐는데, `core-s/req`로는 `workers=0`에서
0.62~0.79, `workers=2`에서 1.22~1.48로 **2.1배 차이**가 또렷이 나왔다
([`PGSS_SEARCH_COST.md` 1장](../operations/PGSS_SEARCH_COST.md)).

**이 지표의 진짜 가치는 "안 변한 것"을 증명할 때다.** OSIV 해제에서 `blks/req`가
17,621 → 16,903으로 **거의 그대로**였던 것이, "DB에 시킨 일의 양은 똑같은데 처리량이
2배가 됐다 = 쿼리를 빠르게 한 게 아니라 커넥션을 빨리 돌려준 것"이라는 주장의 증거가 됐다.

### 1.4 USE 방법론

Brendan Gregg의 [USE Method](https://www.brendangregg.com/usemethod.html)는 자원마다
세 가지를 보라고 한다: **Utilization(사용률) / Saturation(포화·대기) / Errors(에러)**.

우리 케이스에 대입하면:

| 자원 | Utilization | Saturation | 해석 |
|---|---|---|---|
| DB CPU (2코어) | 70% | 실행 중 세션 2.13 | 개선 후 여기가 천장 |
| 커넥션 풀 (5) | 활성 3.25/5 | 획득 대기 0.001s | 병목 아님 |
| 앱 CPU | 21% | — | 여유 |
| 디스크 | 49% busy | — | 여유 |

**"전부 절반씩 놀고 있는데 처리량이 안 오른다"**는 상태가 관측되면, 그건 자원 부족이 아니라
**구조적 직렬화**나 **유휴 점유**를 의심해야 한다는 신호다. 우리 케이스에서 이 신호가
OSIV를 찾아낸 출발점이었다 (13장).

---

## 2장. EXPLAIN (ANALYZE, BUFFERS) 읽는 법 ★★★

> **한 줄 요약** — 실행계획에서 가장 많은 정보를 주는 건 노드 이름이 아니라
> **부모와 자식의 시간 차이**, 그리고 `loops`다.
> **내 경험** — 부모 86.5ms / 자식 1.2ms라는 격차 하나로 "행별 재파싱"을 특정했다.

### 2.1 기본 형태

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE) SELECT ...;
```

- `ANALYZE` — 실제로 실행하고 실측 시간을 붙인다. (⚠️ INSERT/UPDATE도 **진짜 실행된다**.
  트랜잭션으로 감싸고 롤백할 것)
- `BUFFERS` — 만진 블록 수를 붙인다. `shared hit`(캐시 히트) / `shared read`(디스크에서 읽음).
- `VERBOSE` — 각 노드의 출력 표현식(target list)을 보여준다. **이게 결정적일 때가 있다** (2.4).

### 2.2 숫자를 읽는 규칙 4개

```
Nested Loop  (cost=0.29..812.4 rows=850 width=16)
             (actual time=0.234..86.542 rows=850 loops=1)
```

1. **`actual time=시작..종료`** — "첫 행이 나온 시각 .. 마지막 행이 나온 시각"이다.
   소요 시간이 아니라 **누적 시각**이며, 자식 노드의 시간을 포함한다.
2. **`loops=N`이면 표시된 시간은 1회 평균이다.** 총비용은 `actual time × loops`.
   Nested Loop 안쪽에서 `loops=850`을 놓치면 비용을 850분의 1로 착각한다.
3. **`rows`는 예측값(cost 줄) vs 실측값(actual 줄)을 대조하는 용도다.** 둘이 10배 이상
   벌어지면 통계 문제를 의심한다 (5장).
4. **부모 시간 − 자식 시간 합 = 그 노드 자체가 쓴 시간.** 여기가 4번의 핵심이다.

### 2.3 우리 사례: 자식이 1.2ms인데 부모가 86.5ms

```
Nested Loop (actual time=0.234..86.542 rows=850)
  Output: cac.article_id, (ccv.embedding_normalized <#> l2_normalize(($1)::halfvec)), ...
     -> Nested Loop     (actual time=0.029..1.200 rows=850)    <- 자식 합계 1.2ms
     -> Index Scan ccv  (actual time=0.001..0.001 loops=850)   <- 조인도 사실상 공짜
```

조인은 1.2ms에 끝났는데 부모가 86.5ms다. **차액 85ms는 전부 "이 노드가 행마다 계산한
표현식"이다.** 그리고 `Output:`에 `l2_normalize(($1)::halfvec)`가 그대로 박혀 있다 —
11.8KB짜리 파라미터 텍스트를 **행마다 파싱하고 있다는 직접 증거**다 (7장에서 전체 설명).

> 이 패턴은 일반화된다. **스캔·조인은 싼데 노드 시간이 큰 경우, 범인은 거의 항상
> target list나 필터의 표현식이다.** 함수 호출, 캐스트, 서브쿼리, JSON 파싱 등.

### 2.4 `VERBOSE`가 결정적인 이유: `$1`이 남아 있는가

```
-- generic plan (파라미터가 실행 시점까지 미지수)
Output: ... (ccv.embedding_normalized <#> l2_normalize(($1)::halfvec))

-- custom plan (파라미터가 상수로 접힌 뒤 계획됨)
Output: ... avg(((- ((c.embedding_normalized <#> '[-0.0340271,-0.053375244,...]')))))
```

`$1`이 남아 있으면 generic plan, 값이 박혀 있으면 custom plan이다.
**운영에서 "이 쿼리가 generic plan에 빠졌는가"를 확인하는 가장 빠른 방법**이 이것이다 (6장).

### 2.5 스캔 노드의 뜻

| 노드 | 언제 나오나 | 주의점 |
|---|---|---|
| `Seq Scan` | 테이블 전체 읽기 | 작은 테이블에선 정상이고 오히려 빠르다 |
| `Index Scan` | 인덱스로 위치를 찾아 힙을 읽음 | 선택도가 높으면(많이 매칭) 오히려 느릴 수 있다 |
| `Index Only Scan` | 인덱스만으로 답이 나옴 | visibility map이 최신이어야 효과. `Heap Fetches` 확인 |
| `Bitmap Index Scan` + `Bitmap Heap Scan` | 중간 선택도 | 여러 인덱스를 `BitmapAnd`/`BitmapOr`로 합칠 수 있다 |
| `Gather` / `Gather Merge` | 병렬 쿼리 | `Workers Planned/Launched` 확인 (15장) |
| `Materialize` / `Sort` / `Hash` | 블로킹 연산 | `work_mem` 초과 시 디스크 스필 (`external merge Disk: …`) |

### 2.6 실무 체크리스트

- [ ] `Rows Removed by Filter`가 크면 → 인덱스나 조건 순서를 다시 본다.
- [ ] `Heap Fetches`가 크면 → Index Only Scan이 무늬만이다. VACUUM 상태 확인.
- [ ] `shared read`가 크면 → 캐시 미스. `shared_buffers`·워킹셋 문제.
- [ ] `Sort Method: external merge Disk:`가 보이면 → `work_mem` 부족.
- [ ] `loops`가 큰 노드가 있으면 → 곱해서 총비용을 계산한다.
- [ ] `actual rows`와 `estimated rows`가 10배 이상 다르면 → `ANALYZE` 먼저.

> 원리 출처: [PostgreSQL — Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)

---

## 3장. pg_stat_statements와 "안 잡히는 비용" ★★

> **한 줄 요약** — 계기판은 자기가 세는 것만 보여준다. **어떤 비용은 어떤 계기판에도 안 잡힌다.**
> **내 경험** — 블록 카운터만 보다가 TOAST를 범인으로 지목했는데, 진범은 블록을 하나도
> 건드리지 않는 CPU 파싱이었다.

### 3.1 pg_stat_statements가 주는 것

[`pg_stat_statements`](https://www.postgresql.org/docs/current/pgstatstatements.html)는
쿼리를 정규화(리터럴을 `$n`으로 치환)해 `queryid` 단위로 누적 통계를 쌓는다.

| 컬럼 | 의미 | 쓰임 |
|---|---|---|
| `calls` | 호출 횟수 | "이게 요청당 몇 번 도는가" |
| `total_exec_time` / `mean_exec_time` | 총/평균 실행 시간 | 예산 배분 |
| `shared_blks_hit` / `read` | 만진 블록(캐시/디스크) | 블록 회계 (4장) |
| `rows` | 반환 행 수 | 오버페치 탐지 |

**분석의 기본 단위는 `total_exec_time` 기준 상위 N개**다. 평균이 느린 쿼리보다
"빠른데 너무 자주 도는 쿼리"가 예산을 더 먹는 경우가 훨씬 많다. N+1이 딱 그 형태다 (10장).

> ⚠️ **운영 적용 시 주의 (실제로 사고 났던 것)** — `shared_preload_libraries`를
> `ALTER SYSTEM SET`으로 바꾸면 **기존 값을 덮어써서** 재시작 시 다른 확장(pg_search, pg_cron)이
> 사라진다. 우리는 이걸로 **운영 13분 중단**을 냈다. `postgresql.conf`를 직접 수정하고,
> 재시작 전에 설정 파싱을 오프라인으로 검증하는 절차가 맞다.
> ([`PGSS_SEARCH_COST.md` 부록 B](../operations/PGSS_SEARCH_COST.md))

### 3.2 `queryid` 파편화 — N+1의 사촌

Hibernate가 `IN (:ids)`를 `IN ($1, $2, ... $n)`으로 펼치면 **n이 달라질 때마다 SQL 텍스트가
달라진다.** 그러면:

- `pg_stat_statements`에서 같은 쿼리가 수십 개의 `queryid`로 흩어져 **총비용이 안 보인다.**
- 서버 프리페어드 스테이트먼트 캐시도 변형마다 따로 잡는다 (6장에서 이어짐).

해결은 `= ANY(CAST(:ids AS bigint[]))`로 배열 파라미터 하나를 넘기는 것.
그러면 텍스트가 하나로 고정된다. **다만 이 변경엔 부작용이 있었다** — 6.5절에서 다룬다.

### 3.3 어떤 계기판에도 안 잡히는 비용

이게 이 장의 핵심이다. 우리가 실제로 당한 것:

| 비용 | 어디에 잡히나 |
|---|---|
| TOAST 청크 읽기 | `shared_blks_read`, `toast_blks_*` — **또렷하게 찍힌다** |
| 파라미터 텍스트 재파싱 (순수 CPU) | **아무 데도 안 찍힌다.** `total_exec_time` 총합에 섞일 뿐 |

블록 카운터를 보면 "블록 접근의 64%가 TOAST 인덱스 조회"라는 사실이 눈에 확 들어온다.
그래서 TOAST를 범인으로 지목했다. **관측이 틀린 게 아니라, 세면 보이는 쪽으로 추론이 쏠린 것이다.**
실제로 그 블록들은 대부분 캐시 히트라 마이크로초였고, 시간은 카운터에 안 잡히는 쪽에서 샜다.

> **면접에서 쓸 수 있는 문장**: "관측 가능한 지표만 보면 관측 가능한 원인만 찾게 됩니다.
> 그래서 저는 지표로 범인을 지목한 뒤, 반드시 그 가설이 예측하는 **다른** 관측치를 하나 더
> 정해놓고 확인합니다." — 이게 24장의 교훈이다.

### 3.4 함께 보는 뷰

```sql
-- 지금 무엇이 돌고 무엇을 기다리는가
SELECT state, wait_event_type, wait_event, count(*)
FROM pg_stat_activity WHERE datname = current_database()
GROUP BY 1,2,3 ORDER BY 4 DESC;

-- 버퍼 캐시 히트율 (99% 밑이면 워킹셋이 shared_buffers를 넘었다는 신호)
SELECT round(100.0*blks_hit/NULLIF(blks_hit+blks_read,0), 2) AS hit_ratio
FROM pg_stat_database WHERE datname = current_database();
```

`wait_event_type`이 `Lock`이면 락 경합, `IO`면 디스크, `LWLock`이면 내부 경합,
**`NULL`이면서 `state='active'`면 순수 CPU**다. 우리 병목은 계속 마지막 형태였다.

> 원리 출처: [PostgreSQL — Monitoring Stats](https://www.postgresql.org/docs/current/monitoring-stats.html)

---

## 4장. 블록 회계 — 요청당 132MB를 관계 단위로 쪼개기 ★★

> **한 줄 요약** — "느리다"를 "요청당 8KB 블록 몇 개를, 어느 테이블에서, 왜 읽었나"로 번역하면
> 개선 대상이 저절로 정렬된다.
> **내 경험** — 요청당 16,903블록(132MB)을 관계 단위로 귀속시켜 다음 작업 두 개를 특정했다.

PostgreSQL의 I/O 단위는 **8KB 블록(페이지)** 이다. 캐시에 있든(`hit`) 디스크에서 읽든(`read`)
"블록을 만졌다"는 사실은 카운트된다. 요청당 블록 수는 **캐시 상태에 덜 흔들리는
"일의 양" 지표**여서 A/B 판정에 좋다.

**실측 (2026-08-17, VU5 기준)**: 요청당 16,903블록 ≈ 132MB. 이걸 쪼개면:

| 귀속 | 블록 | 비중 | 정체 |
|---|---|---|---|
| HNSW 인덱스 순회 | ~7,200 | 43% | `ef_search=250` × `candidateLimit`이 정하는 값 |
| **`article` 테이블 PK 조회** | **3,457** | **21%** | **요청당 1,113회의 중복 조회** ← 제거함 (9장) |
| 유효성 검증 쿼리 | ~290 | 1.7% | 정상 |
| 그 외 | 나머지 | | |

여기서 나온 판단:

- 21% 조각은 **의미를 안 바꾸고** 지울 수 있다 → 즉시 실행 (9장).
- 43% 조각은 recall과 직결된 파라미터라 **품질 판정 없이는 못 건드린다** → 별건으로 분리.

**두 조각을 섞어서 "43+21=64%를 줄이겠다"고 말하지 않은 것**이 중요하다. 실제로 초안에서
그렇게 썼다가 커밋 `e2bd259`에서 정정했다. 성능 개선안을 낼 때 **확정 효과와 조건부 효과를
같은 문장에 넣으면 안 된다.**

---

## 5장. 통계와 autovacuum ★★

> **한 줄 요약** — 플래너는 통계로 계획을 세운다. 통계가 낡으면 "인덱스가 있는데 안 탄다"가 된다.
> **내 경험** — `n_live_tup`이 204인데 실제 행이 18,585였다. autovacuum이 사실상 안 돌고 있었다.

### 5.1 왜 플래너가 통계에 의존하는가

PostgreSQL 플래너는 비용 기반(cost-based)이다. "이 조건을 만족하는 행이 몇 개일까"를
`pg_statistic`의 히스토그램·MCV(most common values)·distinct 추정치로 계산하고,
그 추정 행 수로 스캔 방식과 조인 순서를 정한다.

**추정이 틀리면 계획이 틀린다.** 100만 행 중 10행이 매칭될 거라 보면 Index Scan을 고르지만,
실제로 50만 행이 매칭되면 그 Index Scan은 Seq Scan보다 훨씬 느리다.

### 5.2 통계는 누가 갱신하나

- `ANALYZE` — 수동 갱신.
- **autovacuum/autoanalyze** — 백그라운드로 도는 데몬. 기본값은
  "변경된 행이 `autovacuum_analyze_threshold(50) + 0.1 × 전체 행`을 넘으면" 실행.
- VACUUM은 통계 갱신 외에 **죽은 튜플 회수**와 **visibility map 갱신**(= Index Only Scan의 전제)도 한다.

### 5.3 우리가 발견한 것

```
article_analyzed_content : n_live_tup = 204     (실제 18,585)
clova_chunk_vectors      : n_live_tup = 1,573   (실제 154,698)
```

100배 차이다. 이 상태에서 뜬 EXPLAIN은 `rows=84` 같은 값을 내놨다.
**"실행계획이 이상한데 왜 그런지 모르겠다"의 상당수는 여기서 시작한다.**

원인 후보는 (a) autovacuum이 이 테이블에 도달하지 못함, (b) 대량 쓰기 직후 통계 갱신 전,
(c) `pg_stat_*` 카운터 리셋. 확인 순서는:

```sql
SELECT relname, n_live_tup, n_dead_tup, last_analyze, last_autoanalyze, last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 20;
```

대량 rebuild가 있는 테이블은 **테이블별 오버라이드**로 임계값을 낮추는 게 정석이다:

```sql
ALTER TABLE article_analyzed_content
  SET (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_scale_factor = 0.05);
```

> 원리 출처: [PostgreSQL — Routine Vacuuming](https://www.postgresql.org/docs/current/routine-vacuuming.html),
> [Planner Statistics](https://www.postgresql.org/docs/current/planner-stats.html)

### 5.4 면접 포인트

"인덱스를 탔다/안 탔다"만 말하면 초급, **"플래너가 왜 그렇게 추정했는지"** 를 말하면 중급이다.
그리고 **"통계를 고친 뒤에도 안 바뀌면 그건 조건의 형태 문제"** 라고 이어가면 8장으로 연결된다.

---
---

# 2부. 쿼리와 플래너

## 6장. 프리페어드 스테이트먼트와 플랜 캐시 ★★★

> **한 줄 요약** — 같은 쿼리가 **6번째 실행부터 갑자기 4배 느려질 수 있고**, 그 상태에서
> 다시 빠져나오지 않는다.
> **내 경험** — 이 계단을 재현해서 벡터 쿼리 비용의 정체를 특정했다. 17.4배 개선의 출발점.
> **면접 가치** — 아는 사람이 드물어서 말하면 확실히 남는다. 단, 원리를 정확히 말해야 한다.

### 6.1 프리페어드 스테이트먼트란

SQL 실행은 원래 **파싱 → 재작성 → 계획 → 실행** 4단계다. 같은 형태의 쿼리를 반복 실행할 때
앞의 세 단계를 매번 반복하는 건 낭비이므로, "쿼리 형태를 미리 등록해두고 값만 바꿔 실행"하는
장치가 프리페어드 스테이트먼트다.

```sql
PREPARE p(int) AS SELECT * FROM article WHERE corporation_id = $1;
EXECUTE p(42);
```

부수 효과로 **SQL 인젝션 방지**가 따라온다. 값이 SQL 텍스트에 섞이지 않고 별도 채널로 가기 때문.
JDBC의 `PreparedStatement`가 파라미터화 쿼리를 권장받는 이유다.

### 6.2 custom plan vs generic plan

여기서부터가 핵심이다. 파라미터가 있는 계획에는 두 종류가 있다.

| | custom plan | generic plan |
|---|---|---|
| 언제 계획하나 | **실행할 때마다**, 실제 파라미터 값을 알고 | **한 번만**, 값을 모르는 채로 |
| 계획에 남는 것 | 값이 상수로 박힘 (`'[-0.03, ...]'`) | 자리표시자 `$1`이 그대로 |
| 장점 | 값에 최적화된 계획 (선택도 정확) | 계획 비용을 매번 안 냄 |
| 단점 | 매 실행마다 계획 비용 | 값에 따라 나쁜 계획일 수 있음 |

PostgreSQL 공식 문서의 규칙:

> 처음 **5회**는 custom plan으로 실행하고 그 계획 비용의 **평균**을 계산한다. 그다음 generic plan을
> 만들어 그 추정 비용과 비교하고, generic이 "재계획을 반복하는 게 나을 만큼 비싸지 않으면"
> 이후로는 generic plan을 쓴다.
> — [PostgreSQL — PREPARE](https://www.postgresql.org/docs/current/sql-prepare.html)

이 판단은 `plan_cache_mode`로 덮어쓸 수 있다:
`auto`(기본) / `force_custom_plan` / `force_generic_plan`
([Query Planning 설정](https://www.postgresql.org/docs/current/runtime-config-query.html)).

**중요: 한 번 generic으로 넘어가면 되돌아오지 않는다.** 그 커넥션에서 그 statement는 계속
generic이다. 커넥션은 풀에서 재사용되므로 **운영은 사실상 항상 generic 상태에 있다.**

### 6.3 JDBC 쪽 절반 — pgjdbc `prepareThreshold`

Java 쪽에도 같은 종류의 임계값이 있다. pgjdbc는 **같은 SQL을 `prepareThreshold`회(기본 5)
실행한 뒤에야** 서버 측 프리페어드 스테이트먼트로 승격시킨다. 그 전까지는 매번
unnamed statement로 보내므로 서버는 실제 값으로 계획한다(= custom plan과 같은 효과).

> 기본 `prepareThreshold`는 5이고, `0`으로 두면 서버 측 프리페어드 스테이트먼트를 아예 끈다.
> — [pgJDBC — Server Prepared Statements](https://jdbc.postgresql.org/documentation/server-prepare/)

즉 **커넥션마다** 다음 순서를 밟는다:

```
실행 1~4회 : 클라이언트 측 (매번 값과 함께 전송)   → 서버는 값을 보고 계획
실행 5회   : 서버 측 statement 생성
실행 5회 이후 : 서버에서 custom plan 5회 → 이후 generic plan 고착
```

이 두 임계값이 겹쳐서, **"운영 트래픽이 조금만 쌓이면 모든 커넥션이 generic plan"** 이 된다.
로컬에서 한두 번 실행해보고 "빠른데요?"라고 말하면 안 되는 이유다.

### 6.4 그래서 무엇이 문제였나 (우리 사례)

우리 벡터 쿼리는 이렇게 생겼다:

```sql
WITH query_vec AS (SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec)
SELECT ... FROM clova_chunk_vectors ccv, query_vec q
WHERE ...
```

`:queryEmbedding`은 float 1024개를 텍스트로 만든 것 — **실측 11,823바이트**다.
DB는 이걸 파싱해서 2,048바이트 이진 배열로 바꿔야 벡터 연산을 할 수 있다.

- **custom plan일 때**: `$1`이 상수 취급이라 **계획 단계에서 한 번 계산**되고 결과가 계획에 박힌다.
  (상수 접기 / constant folding)
- **generic plan일 때**: `$1`은 실행 시점까지 값을 모르는 자리다. 상수 접기가 불가능하다.
  그리고 이 표현식이 조인의 행별 계산식 안에 들어가 있으면(7장) **행마다 실행된다.**

**실측 (합성 데이터, `plan_cache_mode=auto`, 연속 10회)**:

```
1: 4.61  2: 4.27  3: 4.28  4: 4.27  5: 4.27   ms   <- custom plan
6: 20.97 7: 19.23 8: 19.27 9: 19.55 10: 19.54 ms   <- generic 전환, 이후 고착
```

**6회차에서 4.6배로 뛰고 안 돌아온다.** 이 계단이 이 문제의 서명이다.

행당 비용을 분해한 결과 (2000행 테이블, generic 고정):

| 행마다 하는 일 | 행당 비용 |
|---|---|
| 파라미터 문자열(11.8KB) 꺼내 복사 | ~23µs |
| 텍스트 → halfvec 파싱 (숫자 1024개) | ~63µs |
| `l2_normalize` (1024회 곱셈 + sqrt) | ~11µs |
| **소계 — 준비 작업** | **~97µs** |
| `<#>` 실제 거리 계산 (원래 목적) | **~2.5µs** |

**목적인 연산의 38배를 준비에 쓰고 있었다.** 그리고 850개 청크와 비교하는 쿼리라면
같은 문자열을 850번 파싱해 850번 같은 벡터를 만들고 850번 버린다.

예측과 실측이 4% 이내로 맞아떨어진다:

| 쿼리 | 파싱 대상 행 | 예측 (행수×97µs) | 실측 |
|---|---|---|---|
| 레거시 cross-scoring | 850 | 82.9ms | **86.0ms** |
| 퍼널 Stage 2 | 170 | 16.6ms | **19.2ms** |

### 6.5 왜 어떤 쿼리는 안 걸렸나 — 그리고 `= ANY` 전환의 부작용

같은 코드베이스의 **본검색 쿼리는 generic으로 전환되지 않았다.** 이유:

`plan_cache_mode=auto`는 **generic plan의 추정 비용이 custom plan 평균보다 크게 높지 않을 때만**
전환한다. 본검색은 `LIMIT :candidateLimit`이 HNSW 탐색 비용을 좌우하는데, 플래너가 그 값을
모르면 비용을 크게 잡는다 → generic이 비싸 보임 → **계속 custom plan에 머문다.**

> ⚠️ 이 경계는 **비용 추정에 달려 있어 데이터가 바뀌면 뒤집힐 수 있다.** 그래서 우리는
> 지금은 중립인 쿼리에도 같은 수정을 "보험으로" 넣었다 (5.64 → 5.42ms, 손해 없음).

그리고 여기서 **아무도 예상 못 한 상호작용**이 나온다:

- `IN (:articleIds)`는 Hibernate가 `IN ($1..$n)`으로 펼쳐 **n마다 SQL 텍스트가 달라진다.**
  → 각 변형이 커넥션당 `prepareThreshold=5`에 좀처럼 도달하지 못한다
  → **우연히 custom plan에 머물러 재파싱 폭탄을 피하고 있었다.**
- 관측성을 위해 `= ANY(CAST(:ids AS bigint[]))`로 바꾸자 텍스트가 하나로 고정됐다
  → **generic plan에 빠르게 진입해 고착된다.**

즉 **"queryid를 하나로 모으는 좋은 리팩터링"이 성능상으로는 순손해였을 수 있다.**
결론은 "되돌린다"가 아니라 **"generic plan에서도 싸지도록 근본을 고친다"**(7장)였다.

> **면접에서 이 이야기의 값어치**: 최적화 두 개가 서로 반대로 작용하는 사례이고,
> "관측성을 위한 변경이 성능에 영향을 줬다"는 흔치 않은 경험이다.

### 6.6 진단·대응 레시피

**① 이 쿼리가 지금 generic인가?**

```sql
EXPLAIN (VERBOSE) EXECUTE my_stmt(...);
-- 출력에 $1이 남아 있으면 generic, 값이 박혀 있으면 custom
```

**② 코드 배포 없이 전체 가설을 판정하는 법** (운영 접근이 제한될 때 유용)

`DB_URL`에 `?prepareThreshold=0`을 붙이면 pgjdbc가 unnamed statement를 쓰고
PG가 항상 실제 값으로 계획한다 → **모든 쿼리가 custom plan 쪽 숫자로 돌아간다.**
`.env`만 고치고 재기동하면 되므로 1회 실행으로 가설 전체를 검증할 수 있다.

**③ 세 가지 대응 방향**

| 대응 | 언제 | 대가 |
|---|---|---|
| 쿼리를 generic-safe하게 고친다 (7장) | **1순위** | 없음 (권장) |
| `plan_cache_mode=force_custom_plan` | 파라미터별 선택도 편차가 극단적일 때 | 매 실행 계획 비용 |
| `prepareThreshold=0` | 임시 진단, 또는 pgbouncer 등과의 호환 | 계획 비용 + 프로토콜 왕복 |

> 참고: [Vlad Mihalcea — PostgreSQL plan_cache_mode](https://vladmihalcea.com/postgresql-plan-cache-mode/)

---

## 7장. CTE 인라인과 MATERIALIZED ★★★

> **한 줄 요약** — PG 12부터 "한 번만 참조되는 CTE는 본문에 인라인된다". 이 좋은 최적화가
> 6장의 generic plan과 만나면 **행별 재계산 폭탄**이 된다.
> **내 경험** — 키워드 하나(`MATERIALIZED`)로 쿼리 두 개가 17.4배 / 6.7배 빨라졌다.

### 7.1 CTE의 역사

```sql
WITH q AS (SELECT ...) SELECT ... FROM q, t WHERE ...
```

- **PG 11 이하**: CTE는 **항상 별도로 1회 실행**되고 결과가 임시로 물질화(materialize)됐다.
  바깥 조건이 CTE 안으로 밀려 들어가지 못했기 때문에 이를 **"최적화 장벽(optimization fence)"**
  이라 불렀고, 많은 사람이 이 성질을 **의도적으로** 이용했다.
- **PG 12부터**: 부작용이 없고(비재귀·비DML) **한 번만 참조되는** CTE는 **기본적으로 인라인된다.**
  사용자는 `MATERIALIZED` / `NOT MATERIALIZED`로 명시적으로 제어할 수 있다.

> 출처: [depesz — Allow user control of CTE materialization](https://www.depesz.com/2019/02/19/waiting-for-postgresql-12-allow-user-control-of-cte-materialization-and-change-the-default-behavior/),
> [Michael Paquier — Postgres 12 WITH clause and materialization](https://paquier.xyz/postgresql-2/postgres-12-with-materialize/),
> [PostgreSQL — WITH Queries](https://www.postgresql.org/docs/current/queries-with.html)

일반적으로 인라인은 **좋은 변경**이다. 플래너가 CTE 내부까지 보고 조건을 밀어 넣거나
인덱스를 쓸 수 있게 되니까. 문제는 **"1회만 평가되길 기대하고 CTE를 쓴 코드"** 다.

### 7.2 두 조건이 겹쳐야 터진다

```
조건 A: CTE가 한 번만 참조됨          → PG 12+에서 인라인 → 표현식이 조인 안으로 들어감
조건 B: generic plan이라 상수 접기 불가 → 그 표현식이 행마다 평가됨
────────────────────────────────────────────────────────────────
결과: 11.8KB 파라미터를 행마다 파싱
```

**둘 중 하나만이면 아무 일도 안 일어난다.** 그래서 재현이 까다롭고, 로컬에서 몇 번 실행해보면
멀쩡하다. 6장의 "계단"을 알고 있어야 재현할 수 있다.

### 7.3 고친 방법

```sql
-- before
WITH query_vec AS (SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec)

-- after
WITH query_vec AS MATERIALIZED (
    SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec
)
```

`MATERIALIZED`가 CTE를 다시 장벽으로 만든다 → **딱 한 번 실행되고 1행 결과를 조인이 참조**한다.
generic plan이든 아니든 파싱은 쿼리당 1회다.

**실측 (저장소의 실제 SQL을 `PREPARE`해서, 워밍업 16회 후 라운드로빈 15회 중앙값)**:

| 쿼리 | BEFORE | AFTER | 판정 |
|---|---|---|---|
| `computeSimilarityForArticleIds` (레거시 보충) | **91.18ms** | **5.25ms** | **17.4배** |
| `computeSimilarityForArticleIdsTwoStage` (퍼널) | **24.04ms** | **3.57ms** | **6.7배** |
| `findArticlesByTwoStageSearch` (본검색) | 5.64ms | 5.42ms | 중립 (generic 미전환) |

CTE가 아예 없던 쿼리 2개(`findRelatedArticles*`)는 `CAST(...)`를 행마다 **2번** 평가하고
있어서, CTE를 새로 만들어 묶었다. 그중 하나는 최악의 경우 **154k행 × 2회 파싱**이었다.

### 7.4 ⚠️ 반대로, `MATERIALIZED`를 절대 쓰면 안 되는 자리

**HNSW 탐색키에는 적용 금지.**

```sql
ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024)) LIMIT 200
```

이 표현식은 단순 계산이 아니라 **인덱스 탐색키**다. CTE로 끌어올리면 인덱스가 죽는다:

```
인라인 : Limit -> Index Scan using idx_..._hnsw          0.62ms
CTE화  : Limit -> Sort -> Nested Loop -> Seq Scan        3.71ms   (20k행 기준)
```

154k행에서는 훨씬 큰 차이가 되고 `ef_search=250` 설정도 의미를 잃는다.

> **규칙: 재랭킹용 표현식만 끌어올린다. `ORDER BY … LIMIT`에 붙어 인덱스 스캔을 유도하는
> 표현식은 인라인으로 둔다.**

이 구분이 이 최적화의 진짜 난이도다. "MATERIALIZED를 붙이면 빨라진다"가 아니라
**"어떤 표현식이 인덱스 접근 경로의 일부인가"** 를 판단해야 한다.

### 7.5 일반화 — 이 문제의 다른 얼굴들

같은 사고 구조는 벡터가 아니어도 발생한다:

- `WHERE created_at >= to_timestamp(:param, 'YYYY-MM-DD')` — 함수 호출이 행마다
- `WHERE col = ANY(string_to_array(:csv, ','))` — 문자열 파싱이 행마다
- 큰 JSONB 파라미터를 `::jsonb`로 캐스트해 조인 조건에 쓰는 경우
- `WHERE lower(col) = lower(:param)` — 오른쪽 `lower(:param)`은 상수 접기 대상이지만
  generic이면 매번 평가 (비용은 작다)

**공통 처방**: 파라미터를 가공하는 표현식은 **행 집합과 무관한 1행짜리 스칼라**로 분리한다.
`MATERIALIZED` CTE, 혹은 비상관 스칼라 서브쿼리(`(SELECT ... )` 형태는 InitPlan으로 1회 평가)를 쓴다.

> 우리 코드에도 **우연히 올바른 형태**가 있었다. `(SELECT vec FROM query_vec)` 스칼라 서브쿼리를
> 두 번 쓰는 쿼리는 CTE가 2회 참조돼 인라인되지 않았고, 비상관이라 InitPlan으로 1회만 평가됐다.
> 우연이라도 **왜 안전한지 설명할 수 있어야** 그 형태를 유지할 수 있다.

---

## 8장. 인덱스가 있는데 왜 안 타는가 ★★★

> **한 줄 요약** — 인덱스를 못 타는 이유는 대개 **인덱스가 없어서가 아니라 조건의 형태 때문**이다.
> **내 경험** — 서로 다른 테이블 별칭에 걸린 `OR` 때문에 유의어 조회가 154~209ms 걸렸다.
> `UNION ALL`로 쪼개고 FK 인덱스를 추가해 로컬 재현 기준 **72.4ms → 0.078ms**.

### 8.1 인덱스가 무력화되는 대표 형태 6가지

| 형태 | 예 | 왜 |
|---|---|---|
| 컬럼에 함수/연산 적용 | `WHERE lower(email) = :v` | 인덱스는 `email` 기준. 표현식 인덱스가 따로 필요 |
| 암묵적 형변환 | `WHERE varchar_col = 123` | 좌변이 캐스트되면 인덱스 무효 |
| 선두 와일드카드 | `WHERE title LIKE '%검색%'` | B-tree는 접두어만. trigram(GIN) 필요 |
| 복합 인덱스의 선두 컬럼 누락 | idx(a,b)에 `WHERE b = :v` | 선두 컬럼 규칙 (PG 18의 skip scan은 예외적 완화) |
| **서로 다른 릴레이션에 걸친 `OR`** | `WHERE t1.x = :v OR t2.y = :v` | **이 장의 주제** |
| 통계가 틀려 플래너가 Seq Scan을 고름 | — | 5장 |

### 8.2 `OR`가 위험한 이유 — 단일 테이블 vs 조인

**단일 테이블의 OR는 대개 괜찮다.** 두 분기가 각각 인덱스를 탈 수 있으면 플래너가
`BitmapOr`로 두 TID 비트맵을 합치고 힙 페이지를 한 번씩만 방문한다.

```
Bitmap Heap Scan on term_synonym
  -> BitmapOr
       -> Bitmap Index Scan on idx_term_id
       -> Bitmap Index Scan on idx_synonym_term_id
```

**문제는 OR가 조인된 서로 다른 별칭에 걸릴 때다.**

```sql
-- 우리 쿼리 (문제 버전)
SELECT ... FROM term_synonym ts
  JOIN term t1 ON ts.term_id = t1.id
  JOIN term t2 ON ts.synonym_term_id = t2.id
WHERE t1.term IN (:terms) OR t2.term IN (:terms)
```

이 조건은 **어느 한 테이블의 제약(restriction)이 아니라 조인 결과에 대한 조건**이다.
그래서 플래너는 `t1`으로도 `t2`로도 구동(drive)할 수 없다 —
어느 쪽으로 시작해도 다른 쪽 분기의 행을 놓치기 때문이다.
결국 **조인을 전부 만든 뒤 Join Filter로 걸러낸다.** 비용이 검색어 개수가 아니라
**`term` 테이블 크기에 비례**하게 된다.

> 원리 참고: [CYBERTEC — Avoid OR for better PostgreSQL query performance](https://www.cybertec-postgresql.com/en/avoid-or-for-better-performance/)

### 8.3 `UNION ALL`로 쪼개기

```sql
-- 분기 1: t1 쪽에서 구동 (uk_term_term_type 인덱스 사용 가능)
SELECT ts.term_id, ts.synonym_term_id
FROM term t1 JOIN term_synonym ts ON ts.term_id = t1.id
WHERE t1.term IN (:terms)
UNION ALL
-- 분기 2: t2 쪽에서 구동 (역방향 인덱스 필요 → 새로 추가)
SELECT ts.term_id, ts.synonym_term_id
FROM term t2 JOIN term_synonym ts ON ts.synonym_term_id = t2.id
WHERE t2.term IN (:terms)
```

각 분기는 **한 테이블에 대한 제약**이 되어 인덱스 스캔으로 구동된다.

**실측 (로컬 재현, 동일 스키마·합성 데이터, 전부 shared buffer 히트)**:

| 데이터 크기 | before | after |
|---|---|---|
| term 150k / synonym 5k | 17.2ms / 1,567 buffers | **0.54ms / 66 buffers** |
| term 600k / synonym 20k | 72.4ms / 14,313 buffers | **0.078ms / 33 buffers** |

**데이터가 커질수록 격차가 벌어진다** — before는 테이블 크기에 비례하고,
after는 검색어 개수에만 비례하기 때문이다. 이 스케일링 차이를 말할 수 있으면
"운이 좋아 빨라진 것"이 아니라 **복잡도를 바꾼 것**임을 보일 수 있다.

### 8.4 `UNION` vs `UNION ALL` — 중복 처리는 의도적으로

`UNION`은 중복 제거를 위해 정렬/해시를 한다(비용 발생). `UNION ALL`은 그냥 이어 붙인다.
우리는 `UNION ALL`을 골랐는데, **양방향 관계가 이미 애플리케이션에서 정규화되어
(`term_id < synonym_term_id` 규칙 + `addSynonym()`의 중복 제거) 중복 행이 생기지 않기 때문**이다.
그리고 그 계약을 **테스트로 고정**했다.

> **면접 포인트**: "`UNION ALL`이 더 빠릅니다"만 말하면 절반이다.
> **"중복이 안 생긴다는 걸 무엇이 보장하나요?"** 에 답할 수 있어야 한다.

### 8.5 FK 컬럼 인덱스 누락

두 번째 분기(`ts.synonym_term_id = t2.id`)는 인덱스가 **아예 없어서** Seq Scan이었다.
`term_synonym.synonym_term_id`는 FK인데 인덱스가 없었다.

PostgreSQL은 **FK 소스 컬럼에 인덱스를 자동 생성하지 않는다.** (PK/UNIQUE만 자동)
없으면 두 가지가 느려진다:

1. **역방향 조회/조인** — "이 term을 참조하는 행들"을 찾을 때.
2. **부모 행 DELETE/UPDATE** — FK 제약 검사를 위해 자식 테이블을 훑어야 한다.
   여기서 **자식 테이블 전체 Seq Scan**이 발생한다. 삭제가 느린 흔한 원인.

> 원리 출처: [CYBERTEC — Foreign key indexing and performance in PostgreSQL](https://www.cybertec-postgresql.com/en/index-your-foreign-key/)

누락 인덱스는 쿼리로 찾을 수 있다:

```sql
SELECT conrelid::regclass AS table, conname
FROM pg_constraint c
WHERE contype = 'f'
  AND NOT EXISTS (
    SELECT 1 FROM pg_index i
    WHERE i.indrelid = c.conrelid
      AND (i.indkey::smallint[])[0:array_length(c.conkey,1)-1] @> c.conkey
  );
```

### 8.6 ⚠️ 가장 중요한 교훈: 인덱스만 추가해서는 플랜이 안 바뀐다

**실측**: 인덱스만 추가한 상태로 재보니 **14.0ms**였다 (원래 17.2ms).
**쿼리 재작성이 핵심이고, 인덱스는 두 번째 분기를 인덱스 구동으로 만드는 보조**였다.

이건 일반적으로 성립한다. **조건의 형태가 인덱스 사용을 막고 있으면, 인덱스를 아무리 만들어도
플래너는 그걸 쓸 수 없다.** "느리면 인덱스 추가"라는 반사신경이 위험한 이유다.
인덱스는 공짜가 아니다 — 쓰기 증폭, 디스크, VACUUM 부담이 따라온다.

### 8.7 보너스: 커버링 인덱스와 Index Only Scan

인덱스에 필요한 컬럼이 전부 있으면 힙을 안 읽고 답할 수 있다.

```sql
CREATE INDEX idx_chunk_article
    ON clova_article_chunk (article_id) INCLUDE (embedding_binary);
```

전제 조건이 있다: **visibility map이 최신이어야 한다**(= VACUUM이 돌아야 한다).
안 그러면 `Heap Fetches`가 커지면서 이득이 사라진다.

> 원리 출처: [PostgreSQL — Index-Only Scans and Covering Indexes](https://www.postgresql.org/docs/current/indexes-index-only-scans.html)

---

## 9장. 중복 검증 조인 제거 ★★

> **한 줄 요약** — "삭제된 글을 걸러야 한다"는 요구를 **가장 안쪽 루프에서** 처리하고 있었다.
> 같은 검증이 상위에 이미 있었다.
> **내 경험** — 요청당 블록의 21%(3,457블록, 27MB)가 이 중복 조회였다. 지우고 recall까지 얻었다.

### 9.1 무슨 일이 있었나

벡터 검색 쿼리들이 후보 CTE 안에서 이렇게 하고 있었다:

```sql
FROM clova_article_chunk cac
JOIN article a ON cac.article_id = a.id      -- deleted_at 확인만을 위한 조인
WHERE a.deleted_at IS NULL
```

**단위가 틀렸다.** 확인해야 하는 대상은 **아티클 100~300개**인데, 조인은 **청크 1,113행 기준**으로
매번 `article` PK를 조회한다. 아티클 하나당 청크가 여러 개니 같은 행을 반복해서 묻는 셈이다.

**실측**: 요청당 `article` 테이블 블록 3,457개(전체의 21%, 27MB), PK 조회 1,113회.

### 9.2 이미 상위에 검증이 있었다

`ArticleSearchService`의 Phase B가 후보 전체에 대해 **집합 쿼리 1회**로
`deleted_at IS NULL`을 검증하고(`findIdAndPublishedAtByIdIn`), 최종 노출을 `validArticleIds`가
막고 있었다. 게다가 BM25 쿼리 쪽(`article_analyzed_content`)에는 삭제 필터가 아예 없어서,
**"결과에 삭제 아티클이 섞일 수 있다"를 전제로 설계된 시스템**이었다.

즉 안쪽 조인은 **비용만 내고 보장은 추가하지 않는** 코드였다.

### 9.3 설계 원칙: 검증은 어느 계층에서 하는가

| 위치 | 비용 | 언제 맞나 |
|---|---|---|
| 가장 안쪽(행 단위) | 행 수에 비례 | 그 조건이 **탐색 공간을 줄일 때** (선택도가 높을 때) |
| 상위(집합 단위, 1회) | 후보 수에 비례 | 그 조건이 **결과를 거를 뿐일 때** |

`deleted_at IS NULL`은 후자다. 삭제 아티클은 극소수라 탐색 공간을 거의 안 줄인다.
**"거의 아무것도 못 거르는 필터를 가장 비싼 위치에 두는 것"** 이 안티패턴이다.

### 9.4 다만 전부 지우지는 않았다

필터 변형 쿼리(국내/카테고리/기업)는 `corp.is_domestic`, `a.corporation_id` 같은 **필터 컬럼**
때문에 조인이 실제로 필요하다. 그건 남겼다.
`embedding_binary IS NOT NULL`도 남겼다 — 퍼널 stage1은 HNSW top-k가 아니라 필터 스캔이라
NULL 벡터가 들어오면 해밍 거리가 NULL이 되어 집계 의미가 바뀐다.

> **"중복 조인을 다 지웠다"가 아니라 "지워도 되는 것만 지웠다"** 는 구분이 실무의 핵심이다.
> 그리고 **부수 효과를 테스트로 명문화**했다: 삭제된 아티클이 후보 슬롯 하나를 차지할 수 있다.
> 결과 정합성에는 영향이 없고(상위 게이트가 막는다) 손해는 후보 슬롯으로 한정된다.

### 9.5 뜻밖의 수확 — recall 개선

필터가 HNSW의 `ORDER BY … LIMIT` **위에** 있으면, 인덱스가 LIMIT만큼 뽑아온 뒤 필터가
그중 일부를 버린다. 그러면 **LIMIT을 못 채운다** — LIMIT 200을 요청했는데 40행만 반환되는
현상이 실제로 관측됐다. 조인을 제거하니 이 현상이 해소돼 **recall(재현율) 개선**이 기대된다.

이건 벡터 검색 일반의 함정이다. 근사 최근접(ANN) 인덱스는 "상위 k개"를 주는데,
그 뒤에 필터를 걸면 **k보다 적게 남는다.** 대응은 (a) 필터를 인덱스 스캔 안으로 밀어 넣거나
(b) k를 넉넉히 잡고(over-fetch) 거른 뒤 자르는 것이다.

---

## 10장. N+1 ★★★

> **한 줄 요약** — 면접 단골 1순위. 하지만 **"쿼리 수가 많다"보다 중요한 건 그게 무엇을 낭비하느냐**다.
> **내 경험** — 검색당 5.7쿼리를 1쿼리로 줄였다. 그런데 **DB 시간은 3ms(예산의 0.3%)였다.**
> 진짜 피해는 커넥션 체크아웃 5.7회와 직렬 TTFB였다.

### 10.1 정의

부모 N건을 가져온 뒤, 각 부모의 연관을 얻으려고 쿼리를 N번 더 날리는 패턴. 총 1 + N번.
JPA에서는 지연 로딩(lazy loading) 컬렉션을 순회할 때 자동으로 발생한다.

```java
List<Order> orders = orderRepository.findAll();          // 1번
for (Order o : orders) {
    o.getItems().size();                                  // N번
}
```

### 10.2 우리 케이스는 조금 달랐다 — 코드로 만든 N+1

JPA의 지연 로딩이 아니라 **3중 루프로 직접 만든** 형태였다:

```java
// SemanticTermExpansionService (before)
for (String keyword : keywords) {
    Term term = termRepository.findByTerm(keyword);            // ①
    List<Long> synonymIds = getSynonymTermIds(term.getId());   // ②
    for (Long id : synonymIds) {
        Term synonym = termRepository.findById(id);            // ③ 유의어 개수만큼
    }
}
```

**검색당 평균 5.7쿼리.** 고친 방법:

- `findSynonymPairsByTerms` — 양방향 조인 1쿼리로 유의어 쌍을 한 번에
- `findSynonymTermIdsByTermIds` — id별 반복 조회 → `IN` 1회
- 확장 결과 자체를 **Caffeine 캐시(30분/2000)** 에 저장

### 10.3 진짜 피해는 무엇이었나 ← 이 장의 핵심

| 지표 | 값 | 해석 |
|---|---|---|
| DB 실행 시간 | 3ms (예산의 0.3%) | **CPU 문제가 아니었다** |
| 커넥션 체크아웃 | 검색당 **5.7회** | ← 이게 문제 |
| 실행 위치 | BM25/벡터 병렬 실행 **전에 직렬로** | ← 이것도 문제 |

`@Transactional`이 없어서 **리포지토리 호출마다 Spring Data JPA(`SimpleJpaRepository`)가
자기 read-only 트랜잭션을 열었다.** 즉 쿼리 5.7개 = 커넥션 체크아웃 5.7회다.
커넥션 풀이 5인 환경에서 요청 하나가 체크아웃을 5.7번 하면, 풀 회전율에 그대로 곱해진다.

그리고 이 5.7쿼리가 **병렬 구간 이전에 직렬로** 붙어 있어서 TTFB에 통째로 얹혔다
(트레이스 실측 154~209ms — 8장의 OR 문제까지 겹쳐 있었다).

> **면접에서 차별화되는 지점**: "N+1을 없앴습니다"는 누구나 말한다.
> **"N+1의 비용은 DB CPU가 아니라 커넥션 점유와 왕복 지연이었고, 그래서 캐시가 아니라
> 쿼리 구조를 먼저 고쳤습니다"** 는 실측을 해본 사람만 말할 수 있다.

### 10.4 해결책 5가지와 선택 기준

| 방법 | 형태 | 언제 |
|---|---|---|
| **fetch join** | `JOIN FETCH` | 연관을 항상 같이 쓸 때. **컬렉션 2개 이상은 카테시안 곱 주의** |
| **`@EntityGraph`** | 어노테이션 | 같은 리포지토리 메서드에 fetch 계획만 다르게 붙일 때 |
| **batch fetching** | `@BatchSize`, `hibernate.default_batch_fetch_size` | 컬렉션이 여러 개라 fetch join이 곤란할 때. N번 → `IN` 배치 |
| **명시적 `IN` 조회** | 직접 쿼리 | 우리처럼 **JPA 밖에서** 루프를 돌고 있을 때 |
| **캐시** | Caffeine 등 | 결과가 결정적이고 변경이 드물 때 |

> 원리 참고: [Spring Data JPA N+1: Fetch Join and EntityGraph](https://sharpskill.dev/en/blog/spring-boot/spring-data-jpa-n-plus-1-fetch-join-entitygraph)

**우리는 "명시적 IN 조회 + 캐시" 조합**을 골랐다. 이유:
- 이 로직은 엔티티 그래프 순회가 아니라 **검색어 확장이라는 계산**이다.
- 입력(키워드)이 같으면 출력이 결정적이다 → 캐시 적중률이 높다.

### 10.5 캐시 설계에서 걸린 함정 — degrade 결과를 캐시하면 안 된다

확장이 실패하면 원본 키워드만 반환하는 **degrade 경로**가 있다. 그걸 그대로 캐시하면
**일시적 장애가 30분 동안 굳는다.**

`@Cacheable`은 "예외 없이 반환된 값"을 전부 캐시하므로 이 구분을 못 한다.
그래서 `CacheManager`를 주입해 **수동 get/put**으로 바꿨다 — 정상 경로에서만 put.

```java
Cache cache = cacheManager.getCache("searchTermExpansion");
var hit = cache.get(key, ExpansionResult.class);
if (hit != null) return hit;
ExpansionResult result = doExpand(keywords);
if (!result.isDegraded()) cache.put(key, result);   // degrade는 캐시하지 않는다
return result;
```

그리고 **무효화**: 유의어를 바꾸는 7개 메서드와 term 삭제 경로에
`@CacheEvict(allEntries = true)`를 걸었다. 캐시를 도입하면 **무효화 지점을 전수 조사**해야 한다.
이게 캐시의 진짜 비용이다.

### 10.6 N+1을 미리 잡는 법

- 개발 환경에서 `spring.jpa.show-sql=true` + `hibernate.generate_statistics=true`
- 테스트에서 **쿼리 카운트를 단언**한다 (datasource-proxy, `QueryCountHolder` 등)
- `pg_stat_statements`에서 `calls`가 비정상적으로 큰 쿼리 찾기
- APM 트레이스에서 같은 이름의 스팬이 반복되는 구간 찾기 (우리는 Tempo 트레이스에서 발견)

---
---

# 3부. 트랜잭션과 커넥션

> 2부가 "쿼리를 싸게 만들기"였다면 3부는 **"같은 쿼리로 더 많이 처리하기"** 다.
> 이쪽이 투자 대비 회수가 훨씬 컸다 — 쿼리를 하나도 안 고치고 포화 처리량을 2배로 만들었다.

## 11장. 커넥션 풀 사이징 ★★★

> **한 줄 요약** — 풀은 크게 잡을수록 좋은 게 아니다. **DB의 코어 수가 상한을 정한다.**
> **내 경험** — 5 → 8 → 10 → 15로 올려봤더니 10부터 오히려 나빠졌고, 15에서는 53초 정체가 났다.

### 11.1 커넥션이 비싼 이유

PostgreSQL은 **커넥션 하나당 OS 프로세스 하나**를 띄운다(프로세스 모델). 그래서:

- 커넥션 생성 = 프로세스 fork + 인증 + 초기화. 수 ms~수십 ms.
- 커넥션 유지 = 프로세스별 메모리(수 MB) + `work_mem`을 쓸 권리.
- 동시 실행 = **코어 수를 넘으면 컨텍스트 스위칭**.

그래서 앱은 커넥션 풀로 재사용한다. 풀의 목적은 "많이 만들기"가 아니라
**"적게 만들어 돌려쓰기"** 다.

### 11.2 HikariCP의 사이징 공식

HikariCP 위키의 권장 공식:

```
connections = (core_count × 2) + effective_spindle_count
```

여기서 `core_count`는 **DB 서버의 코어 수**다 (앱 서버가 아니다).
`effective_spindle_count`는 동시에 I/O를 처리할 수 있는 디스크 수 (SSD면 대략 1).

> 출처: [HikariCP — About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)

우리 DB는 **2 vCPU**다. → `2×2 + 1 = 5`. 그래서 기본값이 5다.

**핵심 직관**: 코어가 2개면 **진짜로 동시에 실행되는 쿼리는 2개뿐**이다.
커넥션을 20개 열어도 18개는 CPU를 기다린다. 그런데 대기하는 동안에도 메모리와
스케줄러 부담은 그대로 든다. **작은 풀 + 대기열**이 **큰 풀 + 경합**보다 빠른 이유다.

Little's Law로 다시 보면: 처리량 = 풀 크기 ÷ 평균 점유 시간. 풀 크기를 늘려도
**점유 시간이 경합 때문에 더 늘어나면 처리량은 오히려 준다.**

### 11.3 실측 — 8이 최적, 10부터 회귀, 15는 장애

`DB_POOL_SIZE`를 환경변수로 뽑아 같은 ramp 시나리오를 4번 돌렸다
([`2026-08-06-search-ramp-limit-finder.md`](../../load-test/results/2026-08-06-search-ramp-limit-finder.md)).

| level (VU) | pool 5 | pool 8 | pool 10 | pool 15 |
|---|---|---|---|---|
| 10 | 379ms / 68.6 rps | **277ms / 80.9** | 632ms / 53.2 | 457ms / 67.4 |
| 20 | ~600ms / 81.2 | **420~470ms / 102.3** | 714~849ms / 76.9 | 489~746ms / 88.0 |
| 50 | ~560ms / 131.1 | **512ms / 152.2** | 892~965ms / **44.1** ⚠️ | **53.2초 정체 후 폭주** ⚠️⚠️ |
| 100 | 6.03s / 84.2 / err 1.8% | **5.89s / 118.0 / 0.59%** | 10.68s / 33.8 / **5.82%** | 18.04s / 29.9 / 2.47% |

pool 15의 level_50에서는 **약 120초간 요청이 거의 멈춰 있다가 한꺼번에 처리되는**
정체-폭주(stall-burst) 패턴이 나왔다. 평균 처리량으로는 안 보이고, p95가 53초로 튄다.

**후속 정밀 분석**에서 그 구간에 DB 두 코어 모두 98~99%가 75초 이상 지속된 것이 확인됐다.
`max_connections=100`에는 한참 못 미치므로(15×2 인스턴스=30) **커넥션 한도 문제가 아니라
순수하게 동시성이 이 DB 스펙에 과했던 것**이다.

### 11.4 그런데 결론은 "풀을 8로 올리자"가 아니었다

이 실험 뒤에 **코드 개선(체크아웃 축소 + 병렬 워커 off)** 을 하고 나니, pool 5가 이전 pool 8을
전 지표에서 능가했다. 그래서 **풀은 5로 되돌렸다.**

그리고 최종적으로 OSIV를 걷어내니(13장) **풀 5로 포화 처리량이 2배**가 됐고,
정점에서 커넥션 획득 대기가 0.001초였다. 결론:

> **커넥션 풀(5)은 원래 병목이 아니었다. 풀을 유휴로 채우던 코드가 병목이었다.**

**면접에서 이 순서가 중요하다.** "풀 크기를 튜닝했다"가 아니라
**"풀 크기로 해결하려던 문제가 사실은 점유 패턴 문제였음을 실험으로 확인했다"** 가 답변이다.

### 11.5 풀 사이징 체크리스트

- [ ] 공식의 기준은 **DB 코어 수**. 앱 인스턴스가 여러 개면 **풀 크기 × 인스턴스 수**가 DB가 보는 값.
- [ ] `minimum-idle = maximum-pool-size`로 고정 크기 풀을 권장 (HikariCP 관례).
- [ ] `connection-timeout`은 짧게(우리는 3초). 길면 장애 시 스레드가 전부 대기에 묶인다.
- [ ] `leak-detection-threshold`(우리는 30초)로 커넥션 누수 조기 발견.
- [ ] 워크로드가 섞이면(검색 + RAG + 배치) 최적값이 달라진다. RAG는 요청 하나가 15~90초다.
      **하나의 풀을 성격이 다른 워크로드가 공유하는 것 자체가 리스크**다.

---

## 12장. 트랜잭션 스코프 최소화 ★★★

> **한 줄 요약** — 트랜잭션 경계는 **"원자성이 필요한 범위"** 이지 "비즈니스 로직의 범위"가 아니다.
> 트랜잭션이 열려 있는 동안 커넥션은 **점유**된다.
> **내 경험** — 외부 API(Clova 임베딩) 호출이 트랜잭션 안에 있어서, HTTP 응답을 기다리는
> 내내 커넥션을 붙잡고 있었다.

### 12.1 트랜잭션과 커넥션의 관계

Spring에서 `@Transactional` 메서드에 진입하면:

1. 커넥션을 풀에서 가져온다(체크아웃)
2. `setAutoCommit(false)` → 트랜잭션 시작
3. 메서드가 끝날 때까지 **그 커넥션은 이 스레드에 묶인다**
4. commit/rollback → 커넥션 반납

**3번이 핵심이다.** 트랜잭션 안에서 무엇을 하든 — DB를 안 만지고 있어도 —
커넥션은 반납되지 않는다.

> 참고: [Vlad Mihalcea — Spring Transaction and Connection Management](https://vladmihalcea.com/spring-transaction-connection-management/)

### 12.2 안티패턴: 트랜잭션이 외부 호출을 감싸는 것

```java
@Transactional                                  // ← 여기서 커넥션 체크아웃
public Embedding getEmbeddingWithCacheInfo(String query) {
    var cached = repository.findByQuery(query); // 5ms — 실제 DB 작업
    if (cached != null) return cached;
    return clovaApiClient.embed(query);         // 300~3000ms — DB 안 쓰는데 커넥션 점유
}
```

캐시 히트면 5ms 점유, **캐시 미스면 HTTP 응답이 올 때까지 점유**다.
롱테일 검색어(캐시 미스가 잦은 쿼리)가 들어올수록 풀 고갈이 증폭된다.

`@Transactional`을 떼면 Spring Data JPA가 리포지토리 호출마다 여는 **짧은 read-only 트랜잭션**
안에서 조회가 끝나고 **즉시 커넥션이 반납**된다. Clova 호출은 커넥션 없이 진행된다.

**왜 이런 코드가 생기나**: `@Transactional`을 "이 메서드는 DB를 쓴다"는 표시로 붙이는 습관 때문이다.
정확히는 **"이 범위가 원자적이어야 한다"** 는 선언이다.

### 12.3 Phase 분리 — 큰 트랜잭션을 쪼개기

하이브리드 검색은 원래 서비스 클래스 전체에 `@Transactional`이 걸려 있었다.
그래서 **벡터 검색 future를 기다리는 최대 5초 동안** 커넥션을 물고 있었다.

클래스 레벨 어노테이션을 걷어내고 `TransactionTemplate`으로 구간을 쪼갰다:

```
Phase A  [tx]  BM25 실행                    ← 커넥션 필요
         ---   vectorFuture.get() 대기       ← 커넥션 반납 (여기가 목적)
Phase B  [tx]  cross-scoring + 유효성 검증   ← 커넥션 필요
Phase C  ---   NSF 계산·DTO 조립·로깅        ← 커넥션 불필요
```

**Phase C를 트랜잭션 밖으로 뺀 것도 같은 이유다.** 점수 계산·정렬·로깅은 CPU 작업인데
커넥션을 쥔 채 하고 있었다.

> ⚠️ **그런데 이 개선은 배포 후 한동안 아무 효과가 없었다.** OSIV가 EntityManager를 요청 내내
> 열어두는 바람에 무력화돼 있었기 때문이다 (13장). **"트랜잭션을 쪼갰는데 왜 효과가 없지?"**
> 를 추적한 것이 OSIV를 찾은 경로였다.

### 12.4 함정 3가지

**① 앰비언트 트랜잭션 합류.**
비동기 작업을 "정리 차원에서" 트랜잭션 안으로 옮기면, 그 안의 쿼리들이
**바깥 트랜잭션에 합류**해 버린다. 우리 경우 벡터 검색을 `readOnlyTx` 안으로 옮겼다면
임베딩 SELECT와 2단계 쿼리가 합류해서 **Clova 호출 내내 커넥션을 무는 상태로 되돌아갔을 것**이다.
그래서 벡터 검색은 의도적으로 별도 executor(virtual thread)에 남겼다.

**② self-invocation.**
`@Transactional`은 프록시 기반이라 **같은 클래스 안에서 `this.method()`로 호출하면 안 먹는다.**
"분명 붙였는데 트랜잭션이 안 걸린다"의 최다 원인.

**③ read-only 플래그.**
`@Transactional(readOnly = true)`는 Hibernate의 flush 모드를 `MANUAL`로 바꿔
**더티 체킹 비용을 없앤다.** 또 일부 환경에서 라우팅(레플리카) 힌트로도 쓰인다.
조회 전용 경로에는 꼭 붙인다.

### 12.5 원칙 정리

> **트랜잭션 안에 넣으면 안 되는 것**: 외부 HTTP/gRPC 호출, 파일 I/O, 메시지 발행,
> 무거운 CPU 계산, 사용자 입력 대기, `Thread.sleep`, 다른 서비스의 락 획득.

원자성이 정말로 외부 호출과 엮여야 한다면(예: 결제) 그건 트랜잭션 확장이 아니라
**Saga / outbox 패턴 / 보상 트랜잭션**의 영역이다.

---

## 13장. OSIV ★★★

> **한 줄 요약** — Spring Boot 기본값 `spring.jpa.open-in-view=true`는 **HTTP 요청이 끝날 때까지
> 영속성 컨텍스트(그리고 사실상 DB 커넥션)를 붙잡는다.**
> **내 경험** — `/api/**`만 골라 껐더니 **포화 처리량 +114%**, 커넥션 점유/req 430 → 195ms.
> **`blks/req`는 그대로였다** — DB에 시킨 일은 똑같은데 처리량이 2배가 됐다.

### 13.1 OSIV가 하는 일

Open Session In View는 **뷰 렌더링(또는 JSON 직렬화)이 끝날 때까지 Hibernate Session을
열어두는 패턴**이다. 목적은 하나다: **컨트롤러/뷰에서 지연 로딩이 터지지 않게 하는 것.**

Spring Boot에서는 `OpenEntityManagerInViewInterceptor`가 요청 시작 시 EntityManager를 바인딩하고
요청 종료 시 닫는다. Boot 2.0부터는 켜져 있으면 **기동 시 경고 로그**를 찍는다.

```
spring.jpa.open-in-view is enabled by default. Therefore, database queries may be
performed during view rendering. Explicitly configure spring.jpa.open-in-view
to disable this warning
```

### 13.2 왜 커넥션까지 붙잡히나

여기가 오해가 많은 지점이다. **"세션이 열려 있다 = 커넥션이 잡혀 있다"가 항상 참은 아니다** —
Hibernate는 지연 커넥션 획득(lazy connection acquisition)을 할 수 있다.
그러나 실제 동작에서는:

- 트랜잭션이 한 번이라도 열리면 그 커넥션이 EntityManager에 붙는다.
- 트랜잭션이 끝나도 **EntityManager가 살아 있는 동안** 커넥션이 반납되지 않는 경로가 있다.
- 결과적으로 **요청 스레드가 응답을 다 쓸 때까지 커넥션 리스 시간이 늘어난다.**

> Vlad Mihalcea의 표현: 커넥션이 UI 렌더링 단계 내내 유지되어 **커넥션 리스 시간이 늘고,
> 커넥션 풀 혼잡 때문에 전체 트랜잭션 처리량이 제한된다.**
> — [The Open Session In View Anti-Pattern](https://vladmihalcea.com/the-open-session-in-view-anti-pattern/)

**우리는 이걸 로컬 프로브로 직접 확정했다**: 요청 안에서 짧은 트랜잭션 2개를 돌리고
그 **사이 대기 구간**의 Hikari 활성 커넥션을 재면 —

```
OSIV 적용 : 1   (반납 안 됨)
OSIV 해제 : 0   (반납됨)
```

이 프로브가 이 장 전체의 근거다. **추측을 실험으로 바꾸는 가장 싼 방법**이었다.

### 13.3 OSIV의 두 번째 죄: N+1을 숨긴다

지연 로딩이 뷰 렌더링 중에도 성공하므로, **서비스 계층에서 fetch 계획을 잘못 짜도 아무 일도
안 일어난다.** 그래서 N+1이 조용히 쌓인다. OSIV를 끄면 이것들이
`LazyInitializationException`으로 **시끄럽게 드러난다.**

> 이건 단점이 아니라 **장점**이다. 예외는 설계 결함의 알림이다.

### 13.4 어떻게 껐나 — 전부 끄지 않고 경로별로

우리 앱은 Thymeleaf 템플릿 56개가 뷰 렌더링 중 지연 로딩에 의존한다. 전부 끄면 그게 다 깨진다.
그래서:

1. `spring.jpa.open-in-view=false` — Boot의 **전 경로 자동 등록**(`JpaBaseConfiguration$JpaWebConfiguration`)을 물러나게 한다.
2. `WebMvcConfig`에서 인터셉터를 **직접 등록**하고 `/api/**`만 `excludePathPatterns`로 제외한다.

```java
@Configuration
static class OsivConfig implements WebMvcConfigurer {
    @Bean
    OpenEntityManagerInViewInterceptor osivInterceptor() {   // ★ 반드시 빈이어야 한다
        return new OpenEntityManagerInViewInterceptor();
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addWebRequestInterceptor(osivInterceptor())
                .excludePathPatterns("/api/**");
    }
}
```

> ⚠️ **인터셉터를 `new`로 만들면 안 된다.** `EntityManagerFactoryAccessor`가 `BeanFactoryAware`로
> EntityManagerFactory를 해석하므로, 빈이 아니면 EMF를 못 찾는다.
> 그리고 자기 참조 순환을 피하려고 **중첩 `@Configuration`** 에 뒀다.

### 13.5 무엇이 깨졌나 — 회귀 1건

`/api/**` 전 경로를 지연 로딩 관점에서 감사했고, **실제 회귀는 딱 하나** 나왔다:
`/api/liked-items`가 트랜잭션 밖에서 DTO를 만들며 `getCategory()`를 만지는데,
쿼리는 `corporation`만 fetch join하고 있었다. → `LEFT JOIN FETCH category` 추가.

**이 한 건이 "OSIV가 가려주고 있던 유일한 실제 결함"** 이었다는 것도 의미가 있다.
나머지 경로는 이미 fetch join이 되어 있거나 클래스 레벨 `@Transactional`이었거나
스칼라 접근뿐이었다.

그리고 **경로 스코프를 테스트로 고정**했다(`OsivPathScopeTest`).
누가 `open-in-view`를 되돌리거나 `excludePathPatterns`를 지우면
**성능 회귀가 조용히 재발**하기 때문이다. 성능 개선에는 **회귀 가드**가 따라와야 한다.

### 13.6 실측 결과

| 지표 (level_5) | before (2회) | **after** | 변화 |
|---|---|---|---|
| RPS (180초) | 5.57 / 5.03 | **11.35** | **+114%** |
| 정상상태 RPS | 6.53 / 5.43 | **10.72** | +79~97% |
| 클라이언트 p50 | 633 / 644ms | **360ms** | −44% |
| 클라이언트 p95 | 3,312 / 3,426ms | **559ms** | **−83%** |
| degraded 응답 비율 | 4.8% / 5.2% | **0.5%** | −90% |
| **`blks/req`** | 17,621 / 17,172 | **16,903** | **불변** ✅ |

커넥션 회계:

| 실행 | VU | 체크아웃/req | 점유/req | DB 실행/req | **쿼리중 비율** |
|---|---|---|---|---|---|
| before | 2 | 2.93회 | 430ms | 172ms | 40% |
| **after** | 2 | **5.90회** | **195ms** | 160ms | **82%** |
| before | 5 | 3.14회 | 858ms | 192ms | 22% |
| **after** | 5 | 5.72회 | **269ms** | 188ms | **70%** |

**체크아웃 횟수가 늘고 점유 시간이 줄었다.** 이게 가설의 서명이다 —
"요청 스레드의 트랜잭션 4개가 커넥션 1개를 공유하던 것"이
"각자 잡고 각자 반납하는 것"으로 바뀌었다는 뜻이다.

점유/req 195ms는 Tempo 트레이스의 SQL 스팬 합계(193ms)에 거의 붙었다.
**"일도 안 하면서 잡고 있던 시간"이 소멸했다.**

### 13.7 병목은 어디로 갔나

| 실행 / VU | RPS | DB CPU | DB 실행 중 세션 | 획득 대기 |
|---|---|---|---|---|
| before VU2 (정점) | 5.57 | 48% | 0.98 | 0.000s |
| before VU5 | 5.14 | 45% | 0.93 | **0.233s** |
| **after VU5 (정점)** | **11.02** | **70%** | **2.13** | **0.001s** |

- 정점이 VU2 → VU5로 이동하고 값이 2배가 됐다.
- **DB 실행 중 세션이 0.93 → 2.13** — 풀이 막고 있던 병렬성이 실제로 DB에 전달됐다.
  같은 풀 5, 같은 쿼리인데 DB를 2배로 먹이고 있다.
- DB CPU 45% → 70%. **천장을 정하는 건 다시 DB 2코어**가 됐다.

### 13.8 면접 답변 골격

> "Spring Boot는 OSIV가 기본 활성입니다. 뷰 렌더링 중 지연 로딩을 편하게 해주지만,
> 영속성 컨텍스트가 요청 전체에 걸쳐 열려 있어 커넥션 리스 시간이 길어집니다.
> 저희는 커넥션 점유 시간 중 실제 쿼리가 도는 비율이 **경합이 없는 VU1에서도 34~38%뿐**인 것을
> 보고 구조적 상수라고 판단했고, 로컬 프로브로 '트랜잭션 사이 대기 구간의 활성 커넥션이
> OSIV 적용 시 1, 해제 시 0'임을 확인했습니다.
> 다만 Thymeleaf 뷰 56개가 지연 로딩에 의존해서 전부 끄지 않고 `/api/**`만 인터셉터에서
> 제외했습니다. 결과적으로 쿼리는 하나도 안 고치고 — `blks/req`가 불변인 것으로 확인했습니다 —
> 포화 구간 처리량이 2배가 됐고, 병목이 커넥션 풀에서 DB CPU로 이동했습니다."

---

## 14장. 커넥션 점유 회계 ★★

> **한 줄 요약** — 커넥션 압력 = **체크아웃 횟수 × 1회 점유 시간**. 두 항을 따로 재야 한다.
> **내 경험** — 이 두 항이 반대로 움직이는 최적화가 실제로 있었다.

### 14.1 두 축

```
요청당 커넥션 압력(ms) = Σ (각 체크아웃의 점유 시간)
                       ≈ 체크아웃 횟수 × 평균 점유 시간
```

개선 방향은 두 가지고, **서로 상충할 수 있다**:

| 방향 | 방법 | 부작용 |
|---|---|---|
| 체크아웃 **횟수** 줄이기 | 트랜잭션 병합, N+1 제거 | 한 번에 더 오래 쥔다 → **공정성 악화** |
| 1회 **점유 시간** 줄이기 | 트랜잭션 분리, 외부 호출 분리, OSIV 해제 | 체크아웃 횟수가 는다 |

**실제로 겪은 트레이드오프**: Phase B와 C를 병합해 체크아웃 3회 → 2회로 줄였더니
정상 트래픽(10~50 VU)에서는 이득이었는데, **20배 과다 동시성(100 VU)에서는 RPS가
84.2 → 37.0으로 폭락**했다. 커넥션 하나를 더 오래 쥐면 대기자들의 순번이 더 늦게 돌아
이미 나쁜 상황을 더 나쁘게 만든다.

> **교훈**: "체크아웃 횟수를 줄이는 게 항상 좋다"는 틀렸다. **부하 수준에 따라 부호가 바뀐다.**
> 어느 구간을 최적화 대상으로 삼을지 먼저 정해야 한다.

### 14.2 "쿼리중 비율"이라는 지표

```
쿼리중 비율 = (요청당 DB 실행 시간) ÷ (요청당 커넥션 점유 시간)
```

이 값이 낮으면 **커넥션을 잡고 아무것도 안 하고 있다**는 뜻이다.

| 상황 | 비율 | 해석 |
|---|---|---|
| before, VU1 (경합 없음) | **38%** | 혼잡이 아니라 **구조적 상수** ← 결정적 단서 |
| before, VU5 | 22% | 혼잡까지 겹침 |
| after, VU2 | **82%** | 거의 순수 쿼리 시간 |

**경합이 없는 최저 부하에서 재는 것**이 포인트다. VU1에서도 38%면 그건 혼잡 탓이 아니다.
이 한 줄이 "풀을 키우자"에서 "점유를 줄이자"로 방향을 틀게 했다.

### 14.3 예측을 먼저 못 박고 대조하기

OSIV 해제 전에 **8개 지표의 예상값을 문서에 먼저 적었다.** 그리고 실측과 대조했다:

| 예측 | 실측 | |
|---|---|---|
| 체크아웃 2.93 → 5~6회로 **증가** | 5.9회 | ✅ |
| 점유 430ms → 200ms 부근 | 195ms | ✅ |
| 쿼리중 비율 40% → 80% 이상 | 82% | ✅ |
| VU2 RPS 거의 불변(지연 바운드) | 5.72 → 5.75 | ✅ |
| VU5 활성 커넥션 4.58/5 → 크게 하락 | 3.25/5 | ✅ |
| VU5 획득 대기 0.23s → 0에 근접 | 0.0008s | ✅ |
| VU5 임베딩 조회 120ms → 한 자릿수 ms | 2ms | ✅ |
| VU5 RPS → VU2를 넘어섬 | 11.35 ≫ 5.75 | ✅ |

**8개 전부 방향과 크기까지 맞았다.** 이렇게 하면 "1회 실행이라 통계적 신뢰도가 낮다"는
약점을 상당 부분 상쇄할 수 있다. 우연히 8개 예측이 다 맞을 확률은 낮기 때문이다.

> **면접에서 매우 강한 답변**: "개선 전에 어떤 지표가 어느 방향으로 얼마나 움직여야 하는지
> 먼저 적어두고, 그다음에 측정했습니다. 결과에 맞춰 해석을 만드는 걸 막기 위해서였습니다."

---
---

# 4부. 서버 파라미터

## 15장. 병렬 쿼리를 끄다 ★★

> **한 줄 요약** — 병렬 쿼리는 **쿼리 하나를 빠르게** 하지만 **프로세스 수를 3배**로 만든다.
> 코어가 적고 동시 요청이 많으면 순손해다.
> **내 경험** — `max_parallel_workers_per_gather=0`. 요청당 DB CPU가 **2.1배** 차이 났다.
> 그리고 이 결론에 도달하기까지 **측정 방식 때문에 한 번 잘못된 결론을 냈다.**

### 15.1 PostgreSQL 병렬 쿼리의 구조

플래너가 병렬이 이득이라고 판단하면 `Gather` 노드를 넣고, 실행 시 **worker 프로세스**를 띄운다.

```
Gather  (Workers Planned: 2, Workers Launched: 2)
  -> Parallel Seq Scan on ...
```

- `max_parallel_workers_per_gather` — **한 쿼리(정확히는 Gather 노드)가 쓸 수 있는 워커 수**. 기본 2.
- `max_parallel_workers` — 인스턴스 전체 워커 상한.
- `max_worker_processes` — 백그라운드 워커 프로세스 총 상한.

**중요**: leader 프로세스도 일한다. 그래서 `workers_per_gather=2`면 **쿼리 하나가 프로세스 3개**를 쓴다.

공식 문서의 경고:

> 병렬 쿼리는 비병렬 쿼리보다 **훨씬 많은 자원을 소비할 수 있다**. 각 워커는 완전히 별개의
> 프로세스이며 시스템에 **추가 사용자 세션과 거의 같은 영향**을 준다. 워커 4개를 쓰는 병렬 쿼리는
> 워커를 안 쓰는 쿼리보다 CPU·메모리·I/O 대역폭을 **최대 5배**까지 쓸 수 있다.
> — [PostgreSQL — How Parallel Query Works](https://www.postgresql.org/docs/current/how-parallel-query-works.html)

### 15.2 왜 우리 환경에서 손해였나

**DB 호스트는 2 vCPU다.**

```
검색 1건 = BM25 쿼리 1건 = leader + worker 2개 = 프로세스 3개
동시 검색 N건 → 필요한 프로세스 3N개 → 코어는 2개
```

동시 검색이 **1건만 넘어가도 코어를 초과**한다. 그러면 OS가 프로세스들을 번갈아 돌리고
(컨텍스트 스위칭), 프로세스 하나가 받는 CPU 조각이 줄어들어 **쿼리의 wall-clock 시간이 늘어난다.**
그리고 커넥션은 쿼리가 끝날 때까지 잡혀 있으므로 **점유 시간도 같이 늘어난다** (14장).

`EXPLAIN ANALYZE`로 실제 검색 경로의 BM25 쿼리가 **`Workers Planned: 2, Workers Launched: 2`**
로 워커를 진짜 띄우는 것을 확인했다(175.67ms). 추측이 아니라 실측 근거다.

**추가로 메모리 리스크가 있다.** `work_mem`은 **정렬/해시 노드 하나당** 할당량이라
워커가 있으면 곱해진다. `work_mem=16MB`면 leader+worker 2개로 **순간 48MB**까지 갈 수 있다.
1GB RAM에 available이 95MiB였던 우리 DB에서는 위험한 조합이었다.

### 15.3 실측 — 요청당 DB CPU 2.1배

| 구성 | core-s/req |
|---|---|
| `workers=0` | **0.62~0.79** |
| `workers=2` | 1.22~1.48 (**2.1배**) |

RPS로는 노이즈에 묻혀 안 보이던 차이가 요청당 CPU로는 또렷했다.
그리고 캐시 미스 모드 2×2 재측정에서 RPS로도 갈렸다:

- `workers=0`: level 2/5에서 RPS 2.4~2.5, p50 600~1,500ms
- `workers=2`: 같은 레벨에서 RPS 1.3대(**−45~50%**), p50 1,300~3,500ms(**최대 2배 이상**)

### 15.4 ⚠️ 그런데 처음엔 반대 결론을 냈었다 — 이 장의 진짜 교훈

시간 순서대로 보면 이렇다.

**1차 (2026-08-07, 캐시 히트 오염된 ramp)**
2×2 실험(코드 병합 여부 × workers 0/2)을 돌렸더니:
- `workers=0`을 **단독**으로 적용한 조합 D가 baseline A보다 **모든 레벨에서 나빴다**
  (68.6→45.3, 81.2→16.5, 131.1→84.9 rps).
- 결론: **"병합 코드와 workers=0을 둘 다 적용해야만 시너지로 이득, 하나만이면 손해"**

**2차 (2026-08-08, 캐시 미스 모드로 재측정)**
그 사이에 **"기존 ramp 테스트가 하이브리드 코어를 측정하지 못한다"** 는 사실이 발견됐다
(커밋 `75113fb`). 검색어가 반복돼 `hybridTopArticles` Caffeine 캐시(10분)에 걸리는 바람에
**정작 측정하려던 쿼리들이 실행되지 않고 있었다.** 그래서 고유 검색어를 쓰는 캐시 미스 모드를
만들고 2×2를 다시 돌렸더니:
- **`workers=0`의 이득은 체크아웃 횟수와 무관하게 독립적으로 존재**했다.
- 체크아웃 2회 vs 3회는 거의 모든 지표에서 **측정 가능한 효과가 없었다.**
- 즉 **1차의 "시너지" 결론은 재현되지 않았다.**

> **교훈 세 개**
> 1. **측정 대상이 실제로 실행되고 있는지부터 확인하라.** 캐시가 있으면 부하테스트는
>    캐시 앞단만 재고 있을 수 있다.
> 2. **결론은 측정 방식에 종속된다.** 1차 결론은 "거짓말"이 아니라 "그 측정 환경에서의 관측"이었다.
> 3. **재현되지 않은 결론은 명시적으로 폐기하라.** 문서에 그대로 남겨두면 다음 사람이
>    잘못된 전제로 판단한다.

### 15.5 그래서 언제 병렬 쿼리를 켜나

| 상황 | 권장 |
|---|---|
| OLTP, 짧은 쿼리, 동시성 높음, 코어 적음 | **끈다 (0)** — 우리 케이스 |
| 분석/리포트, 큰 스캔, 동시성 낮음, 코어 많음 | 켠다 (2~8) |
| 혼재 | 전역은 낮게, 배치 세션에서만 `SET`으로 올린다 |

`SET max_parallel_workers_per_gather = 4;`를 배치 작업 세션에서만 거는 방식이 안전하다.

> 부가 참고: [pgMustard — Increasing max parallel workers per gather](https://www.pgmustard.com/blog/max-parallel-workers-per-gather)

---

## 16장. 나머지 레버들 ★

> **한 줄 요약** — 파라미터 튜닝은 **곱셈 관계**를 이해하는 게 전부다. 값 하나를 단독으로 보면 틀린다.

### 16.1 우리가 실제로 쓰는 설정

```properties
# application-prod.properties
spring.datasource.hikari.maximum-pool-size=${DB_POOL_SIZE:5}
spring.datasource.hikari.minimum-idle=${DB_POOL_SIZE:5}
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.leak-detection-threshold=30000
spring.datasource.hikari.connection-init-sql=SET hnsw.ef_search=250; \
    SET statement_timeout='5000'; \
    SET max_parallel_workers_per_gather=${DB_MAX_PARALLEL_WORKERS:0};
spring.datasource.hikari.data-source-properties.preparedStatementCacheQueries=256
spring.datasource.hikari.data-source-properties.preparedStatementCacheSizeMiB=5
```

**`connection-init-sql`이 유용한 이유**: 서버 전역 설정을 못 바꾸는 상황에서도
**커넥션마다 세션 파라미터를 걸 수 있다.** 재배포 없이 환경변수만으로 실험 변인을
조절할 수 있어서 A/B 실험에 특히 좋았다.

### 16.2 메모리 관련 파라미터의 곱셈 관계

| 파라미터 | 우리 값 | 주의 |
|---|---|---|
| `shared_buffers` | 256MB (RAM 1GB의 25%) | 너무 크면 OS 캐시와 이중 점유 |
| `effective_cache_size` | 512MB | **할당이 아니라 플래너 힌트.** OS 캐시 포함 추정치 |
| `work_mem` | 16MB | **정렬/해시 노드마다** 할당. 위험식 아래 참고 |
| `maintenance_work_mem` | 64MB | VACUUM·인덱스 빌드용 |

```
최악의 work_mem 사용량 ≈ work_mem × 정렬/해시 노드 수 × (1 + 병렬 워커 수) × 동시 커넥션 수
```

`work_mem=16MB`, 노드 2개, 워커 2개, 커넥션 5개면 이론상 **480MB**다. RAM이 1GB인 서버에서는
OOM 사정권이다. **`work_mem`은 단독으로 판단하면 안 되고 풀 크기·병렬도와 세트로 봐야 한다.**

> 다만 우리는 실제 EXPLAIN에서 **디스크 스필 증거가 안 나와서** 낮추지 않았다.
> **"위험해 보이니 낮추자"가 아니라 "스필이 실제로 나는지 확인하고 정한다"** 가 순서다.

### 16.3 타임아웃

```
statement_timeout = 5000ms   -- 쿼리 하나가 5초 넘으면 죽인다
socketTimeout     = 600s     -- JDBC 소켓 (배치 쿼리 수용)
connection-timeout = 3000ms  -- 풀에서 커넥션 얻기까지
```

`statement_timeout`은 **장애 확산을 막는 안전장치**다. 느린 쿼리 하나가 커넥션을 무한정
잡으면 풀이 마르고 전체 API가 죽는다. 다만 배치 작업(인덱스 재생성 등)은 별도 경로에서
비활성화해야 한다 — 우리는 `BatchQueryService`로 분리했다.

### 16.4 `hnsw.ef_search`

pgvector HNSW의 탐색 폭. 기본 40이고 우리는 **250**으로 올렸다
(후보 LIMIT 200보다 크게 잡아야 LIMIT을 채울 수 있다).

```
ef_search ↑  →  recall ↑, 지연 ↑, 블록 접근 ↑
```

우리 요청당 블록의 **43%가 HNSW 순회**이므로, 이 값은 **성능 레버인 동시에 품질 레버**다.
그래서 "성능만 보고" 내리면 안 되고 recall 측정과 세트로 움직여야 한다 (17장).

---
---

# 5부. 검색 인덱스의 물리

## 17장. 벡터 검색 2단계 퍼널 ★★

> **한 줄 요약** — 정확한 거리 계산은 비싸다. **싼 근사로 후보를 줄이고 비싼 계산은 소수에만** 한다.
> **내 경험** — 보충(cross-scoring) 경로에도 이 퍼널을 적용해 DB 예산 33% → 약 3%를 노렸다.

### 17.1 벡터 검색의 비용 구조

임베딩 1024차원, 청크 154,698개. 전수 계산하면 **154,698 × 1024회 곱셈**이다.
그래서 두 가지 축으로 비용을 줄인다.

**축 1 — 인덱스 (탐색 공간 축소)**
HNSW(Hierarchical Navigable Small World)는 근사 최근접 이웃(ANN) 그래프 인덱스다.
전수 비교 대신 그래프를 따라가며 상위 k개를 찾는다. **정확도를 조금 포기하고 속도를 얻는다.**
`ef_search`가 탐색 폭이고, 크면 recall이 오르고 느려진다.

**축 2 — 양자화 (한 번의 비교를 싸게)**

| 표현 | 크기 | 연산 | 정확도 |
|---|---|---|---|
| `vector(1024)` (float32) | 4KB | 부동소수 내적 | 기준 |
| `halfvec(1024)` (float16) | 2KB | 반정밀 내적 | 거의 동일 |
| `bit(1024)` (binary) | 128B | **해밍 거리(XOR+popcount)** | 낮음 (후보 필터용) |

binary는 **32배 작고 비교가 정수 연산**이라 압도적으로 싸다. 대신 정보 손실이 크다.

> 참고: [pgvector README](https://github.com/pgvector/pgvector),
> [Jonathan Katz — Scalar and binary quantization for pgvector](https://jkatz05.com/post/postgres/pgvector-scalar-binary-quantization/)

### 17.2 우리 구조

```
Stage 1: embedding_binary bit(1024) + HNSW    → 후보 200~500개 (싸다, 인라인 128B)
Stage 2: embedding_normalized halfvec(1024)   → 후보만 정확 재랭킹 (코사인)
         → 아티클별 상위 3청크 평균 → threshold(0.52) → LIMIT
```

**`embedding_normalized`는 L2 정규화된 벡터**라서 내적(`<#>`)이 곧 코사인 유사도다.
정규화를 저장 시점에 해두면 쿼리 시점의 나눗셈이 사라진다 — **전처리로 런타임 비용을 옮기는**
전형적인 기법이다.

### 17.3 보충(cross-scoring) 경로에도 퍼널 적용

하이브리드 검색은 BM25와 벡터를 각각 돌린 뒤 NSF로 병합한다. 그런데 **한쪽에만 있는 결과**는
반대쪽 점수가 비어 있어서, 그 아티클들의 점수를 따로 계산해야 한다(cross-scoring).

이 보충 쿼리가 **DB 예산의 33%(호출당 295ms)** 를 쓰고 있었다. 본검색(336ms)에 육박한다.
**1차 검색도 아니고 빈칸을 메우는 보조 작업이** 그만큼 쓰는 건 균형이 안 맞는다.

그래서 보충도 2단계로 바꿨다:

```
Stage 1: 대상 아티클의 청크들에 대해 binary 해밍 → 아티클별 추정 점수 → 상위 N개만 통과
Stage 2: 통과한 것만 halfvec 정확 계산
```

**주의한 점 — 순서 보존**: 해밍 거리를 코사인 추정치로 바꿀 때
`avg(cos(h)) ≠ cos(avg(h))`이므로, 집계 위치를 옮길 때 **어느 쪽 순서를 보존해야 하는지**
명시적으로 확인했다. 성능을 위해 수식을 옮기는 리팩터링은 **랭킹을 조용히 바꿀 수 있다.**

**실측**: 퍼널은 수정 전에도 레거시 단일 쿼리보다 **3.8배 빨랐고**(24.0 vs 91.2ms),
블록도 9,374 → 3,941(**−58%**)로 줄었다.

### 17.4 필터와 ANN의 상성 문제 (중요)

```sql
SELECT ... FROM chunk c JOIN article a ON ...
WHERE a.deleted_at IS NULL
ORDER BY c.embedding_binary <~> :q LIMIT 200
```

HNSW가 200개를 뽑아온 뒤 필터가 일부를 버리면 **최종 결과가 200개가 안 된다.**
실제로 **LIMIT 200 요청에 40행 반환**이 관측됐다 (9장). 이게 ANN + 필터의 고전적 함정이다.

대응:
1. **필터를 없앤다** (다른 곳에서 검증한다면) — 우리가 택한 방법
2. **over-fetch** — LIMIT을 크게 잡고 필터 후 자른다
3. **인덱스에 필터 반영** — 부분 인덱스(`WHERE deleted_at IS NULL`)나 필터링 지원 인덱스

### 17.5 품질 지표를 같이 봐야 한다

성능 최적화가 **recall을 조용히 떨어뜨리는 것**이 벡터 검색의 최대 리스크다.

- `ef_search`를 내리면 빨라지지만 놓치는 결과가 생긴다.
- 퍼널 Stage 2 컷을 좁히면 빨라지지만 랭킹이 바뀐다.
- 우리는 "퍼널 컷 20의 랭킹 손실은 미검증 부채"라고 **문서에 명시**했다.

> **면접 포인트**: "성능을 X% 개선했습니다"에 이어 **"품질 지표는 어떻게 지켰나요?"** 를
> 스스로 언급하면 신뢰도가 크게 오른다. 검색·추천 도메인에서는 특히.

---

## 18장. 세그먼트 인덱스의 쓰기 버퍼 vs 읽기 지연 ★★

> **한 줄 요약** — 검색 엔진 계열 인덱스는 **쓰기를 버퍼에 모았다가 나중에 정리**한다.
> 그 버퍼는 **검색할 때 매번 선형 스캔**되므로, 버퍼 크기가 곧 읽기 지연의 고정비다.
> **내 경험** — ParadeDB의 `global_mutable_segment_rows`를 1000 → 50으로. 이건 ParadeDB만의
> 이야기가 아니라 **Lucene / Elasticsearch / LSM 계열 전부에 공통된 구조**다.

### 18.1 일반 원리 — 왜 세그먼트인가

역색인(inverted index)은 **읽기에 최적화된 불변 구조**다. term → posting list 정렬 배열.
여기에 문서 한 건을 추가하려면 원칙적으로 인덱스 전체를 다시 써야 한다(**쓰기 증폭**).

그래서 이 계열은 전부 같은 해법을 쓴다:

```
쓰기 → [작은 가변 버퍼] → 차면 → [불변 세그먼트로 봉인(seal)]
                              → 백그라운드 병합(merge)
```

| 시스템 | 버퍼 | 봉인 트리거 | 병합 |
|---|---|---|---|
| Lucene / Elasticsearch | in-memory buffer | `refresh_interval`(기본 1초) | segment merge |
| RocksDB / LSM 트리 | memtable | 크기 임계값 | compaction |
| **ParadeDB pg_search** | **mutable segment** | **`mutable_segment_rows`(기본 1000)** | background merge |

**공통 트레이드오프**:

```
버퍼를 크게  →  쓰기 처리량 ↑, 병합 부담 ↓, 그러나 읽기 지연 ↑ (버퍼를 매번 훑음)
버퍼를 작게  →  읽기 지연 ↓, 그러나 세그먼트가 많이 생겨 병합 부담 ↑
```

Elasticsearch에서 `refresh_interval`을 1초 → 30초로 올리면 색인 처리량이 오르는 대신
**새 문서가 검색에 보이기까지 최대 30초** 걸린다. 같은 축의 다른 지점이다.

> 참고: [ParadeDB — Write Throughput](https://docs.paradedb.com/documentation/performance-tuning/writes),
> [ParadeDB — The Write Performance Problem](https://www.paradedb.com/blog/increased-write-performance)

ParadeDB 문서의 표현: 값이 높을수록 **"쓰기 처리량은 개선되지만 읽기 성능은 나빠진다 —
가변 자료구조는 검색이 느리기 때문"** 이고, 버퍼가 메모리로 읽히므로 RAM도 더 쓴다.

### 18.2 우리가 관측한 것 — 3.5%의 문서가 쿼리 시간의 99%

프로덕션 인덱스의 세그먼트 구성과 쿼리 시간을 대조했다:

| | 문서 수 | 쿼리 기여 시간 | 문서당 |
|---|---:|---:|---:|
| immutable 세그먼트 4개 | 17,940 | 1.5ms | **0.00008 ms** |
| **mutable 세그먼트 1개** | **645** | **148.2ms** | **0.230 ms** |

**mutable 문서 1건이 sealed 문서 1건의 약 2,900배**다.
전체의 **3.5%(645/18,585)** 문서가 쿼리 시간의 **99.0%** 를 차지했다.

이유는 단순하다. **mutable 세그먼트에는 term dictionary가 없다.**
그래서 검색어가 무엇이든 **매 쿼리마다 전 문서를 선형 스캔**한다.
매칭 여부와 무관한 **순수 고정비**다.

**검증**: 시간이 버퍼 수에 비례하지 않았다(933 → 185, 5배 차이인데 시간은 100배).
→ I/O가 아니라 **CPU**다. 그리고 로컬 합성 코퍼스에서 잰 `0.077 ms/doc`의 정확히 3배가
프로덕션 `0.230 ms/doc`이었다 — 실제 문서 토큰 수가 약 3배(250 → ~750)라는 뜻으로,
**별개 환경·별개 데이터에서 같은 선형 모델이 재현**됐다.

### 18.3 축적이 아니라 톱니파

```
200184f7  f  14,940 docs   ← CREATE INDEX로 만들어진 원본
6921ffbf  f   1,000 docs   ┐
679f8db4  f   1,000 docs   ├ 1000 캡에 도달해 sealed 된 과거 mutable
b84ae3df  f   1,000 docs   ┘
6ebe4607  t     645 docs   ← 지금 채워지는 중
```

1,000개짜리 sealed 세그먼트 3개는 **이 문제를 이미 3번 겪고 지나간 흔적**이다.
mutable이 0 → 1000으로 차는 동안 BM25는 1.5ms에서 **최대 ~230ms**까지 연속적으로 나빠지다가,
1000에서 sealed되며 리셋되고 다시 차오른다.

> **핵심 통찰**: BM25 지연은 "느리다/빠르다"가 아니라
> **"측정 시점이 마지막 seal로부터 얼마나 지났는지"** 에 좌우된다.
> 이걸 모르면 같은 쿼리를 두 번 재고 "왜 다르지?" 하게 된다.

이 사실이 다른 미해결 관측 두 개도 설명했다:
- 서로 다른 쿼리(본검색 167ms / 보충 169ms)가 값이 붙어 있던 이유 → **둘 다 같은 고정비**를 한 번씩 냄
- "64건만 점수 매기는데 왜 본검색과 시간이 같은가" → **`id IN`으로 대상을 줄여도 mutable 스캔은 안 준다**

### 18.4 쓰기는 어디서 오는가 — 초판의 오진

처음엔 톱니파의 동력을 "매일 04:00 크롤링"으로 봤는데 **틀렸다.**
실제 크롤링 유입은 **하루 약 5건**이다. 5건/day면 3,645행이 쌓이는 데 2년이 걸린다.

진짜 출처는 **관리자 대량 작업**이었다:

| 경로 | 1회 쓰기량 |
|---|---|
| 아티클별 term 추출 | 1행 |
| 관리자 ArticleTerm 수정/삭제 | 1행 |
| **Term 삭제 / 불용어 등록 (`rebuildForAffectedArticles`)** | **수백~수천 행** |

→ **임계치는 하루 평균 유입량이 아니라 스파이크 크기가 결정한다.**
평균으로 용량을 잡으면 안 되는 전형적인 사례다.

### 18.5 값을 어떻게 골랐나

프로덕션 실측 `0.23 ms/doc`과 스파이크 2,000행 가정 기준:

| cap | 읽기 최악 | 5건/day seal 주기 | 2,000행 스파이크 시 생성 세그먼트 |
|---:|---:|---:|---:|
| 1 | 0.23ms | 매 write | **2,000** ❌ |
| 20 | 4.6ms | 4일 | 100 |
| **50** | **11.5ms** | **10일** | **40** ✅ |
| 100 | 23ms | 20일 | 20 |
| 1000 (기본) | **230ms** ❌ | 200일 | 2 |

**50을 골랐다.** 최악 11.5ms는 벡터 본검색 87ms의 1/7이라 무시 가능하고,
스파이크가 와도 세그먼트 40개면 1~2 vCPU가 병합을 감당할 범위다.
**`=1`은 스파이크 한 번에 세그먼트 수천 개가 되므로 금지.**

> **이 표가 이 장의 진짜 산출물이다.** "기본값이 커서 줄였다"가 아니라
> **양쪽 비용(읽기 최악 지연 / 세그먼트 생성량)을 같은 표에 놓고 고른 것**이다.

정상 상태에서는 mutable이 하루 5행씩만 자라므로 **평소 BM25는 캡과 무관하게 1~2ms**다.
**캡은 오직 스파이크 방어용이다.**

### 18.6 부수적으로 배운 것 — 설정 반영 확인의 함정

`ALTER SYSTEM SET ... ; SELECT pg_reload_conf(); SHOW ...;`를 **한 배치로 실행하면**
`SHOW`가 SIGHUP 처리 전 값을 읽어 `-1`을 반환한다.
**완전히 분리된 세션에서 다시 확인**하니 정상 값이 나왔다.

> 설정이 안 먹은 것처럼 보일 때 **"확인 방법이 틀린 것"** 을 먼저 의심해야 한다.

또 하나: `REINDEX CONCURRENTLY`로 mutable을 비우면 즉시 149.7ms → 1.5ms가 되지만,
**다음 쓰기가 다시 채운다.** 일회성 조치와 상시 대책을 구분해야 한다.
상시 대책은 (a) 캡을 낮추거나 (b) 대량 rebuild 직후 REINDEX를 붙이는 것이다.

---

## 19장. TOAST ★

> **한 줄 요약** — PostgreSQL은 한 행이 페이지(8KB)의 1/4을 넘으면 큰 컬럼을 **별도 테이블로 빼낸다**.
> **내 경험** — 블록 접근의 64%가 TOAST 인덱스 조회였다. 그래서 범인으로 지목했는데, **아니었다.**

### 19.1 구조

TOAST(The Oversized-Attribute Storage Technique)는 큰 값을 압축하거나
**out-of-line**(별도 TOAST 테이블에 청크로 쪼개 저장)으로 보관한다.

| 전략 | 압축 | out-of-line | 비고 |
|---|---|---|---|
| `PLAIN` | ✗ | ✗ | 고정 길이 타입 |
| `EXTENDED` | ✓ | ✓ | **기본값** |
| `EXTERNAL` | ✗ | ✓ | 부분 접근(substring)이 빠름 |
| `MAIN` | ✓ | 최후에만 | |

읽을 때는 TOAST 테이블에서 청크를 모아 재조립하고, 압축돼 있으면 푼다(**detoast**).
값 크기에 비례한 I/O + CPU가 든다.

> 출처: [PostgreSQL — TOAST](https://www.postgresql.org/docs/current/storage-toast.html)

우리 `clova_chunk_vectors`는 halfvec(1024) = 2KB짜리 컬럼이 여러 개라
heap 9MB / **TOAST 417MB** 구성이었고, `attstorage='e'`(EXTERNAL)였다.

### 19.2 왜 범인처럼 보였나 (3장의 반복이지만 중요하다)

- TOAST 읽기는 **블록 카운터에 또렷이 찍힌다** → "블록의 64%가 TOAST"라는 강한 신호.
- 실제로는 그 블록들이 **대부분 캐시 히트**라 마이크로초였다.
- 진짜 시간은 **블록을 하나도 안 건드리는 CPU 파싱**에서 샜다 (6~7장).

**"관측된 사실"과 "원인"은 다르다.** 이 구분이 성능 디버깅의 전부라고 해도 과언이 아니다.

### 19.3 실무 팁

- 큰 컬럼은 **SELECT 목록에서 빼면 detoast 자체가 안 일어난다.** `SELECT *` 금지의 진짜 이유 중 하나.
- 정렬/그룹핑 키에 큰 TOAST 컬럼을 넣지 않는다.
- 큰 컬럼을 자주 안 읽는다면 **별도 테이블로 분리**(수직 분할)하는 것도 방법이다.
  우리 스키마의 `ArticleChunk` / `ChunkContent` / `ChunkVector` 분리가 이 형태다.

---
---

# 6부. 애플리케이션 레벨

## 20장. 계산 재활용의 함정 ★★

> **한 줄 요약** — "이미 계산해둔 값을 다시 쓰자"는 공짜처럼 보이지만,
> **두 값이 같은 방식으로 계산된 게 아니면 공짜가 아니다.**
> **내 경험** — 1단계 후보 점수를 재활용하려다 **추정량 편향**을 발견하고 안전장치 2개를 넣었다.

### 20.1 아이디어

본검색은 이렇게 돈다:

```
1. HNSW로 청크 후보 200개 추출
2. 200개 전부에 halfvec 정확 거리 계산
3. 아티클별 상위 3청크 평균 집계
4. threshold 미만 제거 → LIMIT → 반환
```

**3번 시점에 후보에 등장한 모든 아티클의 점수가 이미 계산돼 있다.**
4번에서 잘려나간 점수는 그냥 버려진다. 그런데 잠시 뒤 cross-scoring이
**바로 그 아티클들 중 일부의 점수를 다시 계산하려고** 84MB를 읽는다.

→ 버리지 말고 캐시해서 쓰자. **DB 왕복 0회.** 이론상 완전 무료.

### 20.2 그런데 두 값은 같은 값이 아니다

| 출처 | 계산 방식 |
|---|---|
| 본검색 후보 점수 | **HNSW 후보 200개 안에서** 상위 3청크 평균 |
| cross-scoring 보충 점수 | **그 아티클의 전체 청크 중** 상위 3청크 평균 |

같은 이름의 "벡터 점수"인데 **모집단이 다르다.**
그리고 이 두 집단이 같은 min-max 정규화를 통과해 NSF 랭킹에 들어간다.
**추정량이 다른 두 집단을 한 척도로 정규화하는 것은 계통 편향(systematic bias)** 이다.

### 20.3 편향의 방향 — 직관과 반대였다

처음엔 "후보 안에서 낸 점수 ≤ 전체에서 낸 점수"라고 가정했다(후보가 부분집합이니까).
**틀렸다. 집계가 `AVG`라서 분모가 고정이 아니다.**

아티클이 후보 200청크에 걸치는 청크 수가 `topK`(3) 미만이면 상위 1~2개만 평균 내므로
**전체 청크 기준 상위 3평균보다 높게** 나온다.

| 아티클 | 후보에 걸린 청크 | 후보 점수 | cross-scoring 점수 |
|---|---|---|---|
| 청크 3개 중 1개만 후보 | 1 | **0.80** | 0.70 |
| 청크 5개 중 1개만 후보 | 1 | **0.55** | 0.51 |
| 청크 3개 전부 후보 | 3 | 0.43 | 0.43 |

그리고 **재활용 대상 집단에서 이 경우가 지배적**이다 — 재활용 대상은 정의상
threshold/limit로 잘린 약한 매칭이고, 그런 아티클이 후보 200청크 중 3개 이상을 차지하는 일은
드물다(아티클당 청크 중앙값 7).

→ 그대로 재활용하면 **약한 매칭이 부풀어 올라 "없던 점수"가 생긴다.**

### 20.4 안전장치 2개

**① 후보 청크가 topK개 이상인 아티클만 재활용한다.**

```sql
CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
```

이 조건이 있으면 후보는 전체 청크의 부분집합이고 **평균 깊이가 같으므로**
`candidate_similarity ≤ cross-scoring 값`이 항상 성립한다(과대평가 불가).

**② 임계값 미만인 후보는 탈락시키지 않고 DB로 넘긴다.**

후보 점수는 과소평가일 수 있어 DB에서는 임계값을 넘길 수 있다.
반대로 임계값 이상이면 DB 값은 그보다 크거나 같으므로 통과 여부가 안 바뀐다.
**점수를 잃는 아티클이 없다.**

두 장치를 합치면 재활용은 **"점수를 보수적으로, 방향은 본검색과 일관되게"** 채운다.

### 20.5 이 최적화의 다른 교훈들

**(a) 테스트가 실제 버그를 잡았다.**
실제 DB에서 쿼리 5종을 전부 실행하는 테스트를 짰더니, 결과 파싱의 삼항 연산자에서
**언박싱 NPE**가 나왔다:

```java
cond ? ((Number) row[2]).doubleValue() : similarity   // 한쪽이 primitive double
```

두 컬럼이 모두 NULL인 행(운영에서 흔한 조합)에서 `Double`이 언박싱되며 NPE가 나고,
상위 catch가 이를 삼켜 **벡터 검색이 통째로 빈 결과**가 된다 —
즉 **검색이 BM25 단독으로 조용히 퇴화**한다. 스텁 기반 테스트만 있었다면 배포 후에야 드러났을 것이다.

> **면접에서 강한 이야기**: "성능 최적화 코드에는 조용한 실패(silent degradation) 리스크가 있어서,
> 스텁이 아니라 실제 DB를 쓰는 테스트를 붙였고 거기서 NPE를 잡았습니다."

**(b) 부하테스트로는 이 최적화를 측정할 수 없다.**
mock Clova가 텍스트 시드 의사난수 벡터를 반환하므로 Stage 1 후보 집합이 BM25 결과와 **무상관**이다.
재활용은 교집합에서만 일어나는데 무상관이면 기대 교집합이 1건 미만.
→ **A/B가 "차이 없음"으로 나오는 건 예상된 결과이고, 그걸 운영 효과로 해석하면 안 된다.**
판정은 운영 로그의 커버리지 + `pg_stat_statements` 호출 수로만 한다.

> **이런 "측정 불가"를 미리 선언해두는 것**도 실력이다. 안 그러면 나중에
> "효과 없는 최적화"로 오판된다.

---

## 21장. 병렬화 ★★

> **한 줄 요약** — 서로 의존하지 않는 I/O 대기는 **겹치면 공짜**다. 단, **트랜잭션 경계와 만나면
> 공짜가 아니다.**
> **내 경험** — Clova 임베딩 HTTP 호출과 유의어 확장 DB 쿼리를 병렬로 돌렸다.

### 21.1 무엇이 직렬이었나

프로덕션 트레이스에서 유의어 확장 쿼리가 **150~165ms 동안 요청 맨 앞에 직렬로** 붙어 있었다.
두 트레이스에서 같은 값으로 재현되니 노이즈가 아니다.

그런데 데이터 의존성을 보면:

```
유의어 확장 결과  → BM25 쿼리 문자열을 만드는 데만 쓰임
Clova 임베딩 호출 → 원본 키워드만 있으면 됨
```

**서로 의존하지 않는다.** 직렬일 이유가 없었다.

### 21.2 고친 방법

`vectorFuture` 생성을 메서드 맨 앞(복잡도 분류·확장보다 위)으로 옮겼다.
람다가 캡처하는 값이 전부 메서드 파라미터에서 바로 나오므로 확장 결과에 의존하지 않는다.

```
before:  [확장 150ms] → [임베딩 HTTP 300ms] → [BM25 ∥ 벡터]
after:   [임베딩 HTTP 300ms          ]
         [확장 150ms]                  ← 겹침
                                     → [BM25 ∥ 벡터]
```

**컨트롤러도 같이 고쳐야 했다.** 확장이 서비스가 아니라 **컨트롤러에서** 돌고 있었기 때문에,
서비스 내부만 재정렬해서는 API 경로에 효과가 없었다.
→ **"어디서 호출되는가"를 확인하지 않고 내부만 고치면 효과가 0일 수 있다.**

### 21.3 ⚠️ 왜 트랜잭션 안으로 옮기면 안 되는가

벡터 검색은 의도적으로 별도 executor(virtual thread)에 남겼다.
요청 스레드나 `readOnlyTx` 안으로 옮기면 **임베딩 SELECT와 2단계 쿼리가 앰비언트 트랜잭션에
합류**해서, **Clova 호출 내내 커넥션을 무는** 상태가 된다 (12장에서 고친 문제의 재발).

```
잘못된 "정리": 관련 있는 DB 작업이니 같은 트랜잭션에 묶자
실제 결과   : HTTP 대기 300~3000ms 동안 커넥션 점유
```

**병렬화와 트랜잭션 경계는 같이 설계해야 한다.** 이게 이 장의 핵심이다.
겹치는 구간이 **DB작업 ∥ HTTP대기**여야 자원 경합이 아니다.
DB작업 ∥ DB작업이면 커넥션을 2개 쓰는 것이고, 풀 압력이 2배가 된다.

### 21.4 부수 효과들 (전부 의도적으로 문서화)

- **single-flight 패자는 확장을 아예 건너뛴다.** 같은 검색어가 동시에 오면 하나만 실행하고
  나머지는 결과를 공유하는데, 기존엔 컨트롤러에서 확장을 치른 뒤 join했다.
  확장은 keyword로부터 결정적이므로 안전하다.
- **빈 BM25 쿼리 경로가 500 대신 200+빈결과가 된다** — 확장 예외가 try/catch에 잡히는 위치가 바뀌어서.
- **이미 뜬 future는 취소하지 않는다.** `cancel(true)`는 실행 중인 `supplyAsync` 태스크를
  중단시키지 못하고, 그냥 두면 캐시를 채워 다음 요청이 이득을 본다.

> **면접 포인트**: 비동기 리팩터링에서 **취소가 실제로는 안 된다**는 사실을 아는 사람은 드물다.
> `Future.cancel(true)`는 인터럽트를 걸 뿐이고, 인터럽트를 확인하지 않는 코드는 계속 돈다.

### 21.5 회귀 가드

병렬화는 **순서 의존이 숨어 있으면 조용히 깨진다.** 그래서 테스트를 붙였다:
확장 스텁이 래치로 **"벡터가 이미 떠 있는가"** 를 확인한다.
그리고 **hoist를 되돌려 실제로 실패하는 것까지 확인**했다.

> 테스트를 짤 때 **"이 테스트가 실패하는 것을 본 적이 있는가"** 가 중요하다.
> 항상 통과하는 테스트는 아무것도 지키지 않는다.

---

## 22장. 캐시 계층 ★

> **한 줄 요약** — 캐시는 성능 문제를 **가리는** 데도 아주 잘 듣는다. 그래서 위험하다.

### 22.1 우리 캐시 지도

| 캐시 | TTL | 저장 위치 | 목적 |
|---|---|---|---|
| `SearchQueryEmbedding` | 영구 | **DB 테이블** (halfvec) | Clova API 호출 절약 (비용+지연) |
| `searchTermExpansion` | 30분 / 2000건 | Caffeine (로컬) | 유의어 확장 결과 |
| `hybridTopArticles` | 10분 | Caffeine | 검색 결과 전체 |
| `aiSummary` / `ragAnswer` | 1시간 | Caffeine | LLM 응답 |
| `ragPreprocess` | 6시간 | Caffeine | 질의 분해 결과 |

### 22.2 캐시 도입 시 반드시 결정해야 할 4가지

1. **키** — 무엇이 같으면 같은 결과인가? (대소문자, 정렬, 필터 조합 포함 여부)
2. **무효화** — 원본이 바뀌는 **모든 경로**를 찾았는가? (우리는 유의어 변경 7개 메서드 + term 삭제)
3. **실패 결과를 캐시할 것인가** — degrade/에러는 캐시하면 안 된다 (10.5절)
4. **stale 허용 범위** — 10분 지난 검색 결과가 괜찮은가?

### 22.3 캐시가 측정을 오염시킨다

**이게 우리가 실제로 크게 데인 부분이다.**

`hybridTopArticles` 10분 캐시 때문에, 부하테스트가 같은 검색어를 반복하면
**측정하려던 하이브리드 코어가 아예 실행되지 않았다.** 몇 주간의 실험이
"캐시 조회 성능"을 재고 있었던 셈이다 (15.4절).

대응으로 만든 것:
- **캐시 미스 모드** — 고유 검색어 생성 + 전용 엔드포인트(Clova mock)
- 트레이스 재측정 시 **"안 쓰던 검색어로 요청할 것"** 을 체크리스트에 명시

> **일반 규칙**: 성능 측정 시나리오를 짤 때 **캐시 계층을 전부 나열하고, 각각을 통과하는지
> 명시적으로 확인**해야 한다. 애플리케이션 캐시, DB 버퍼 캐시, OS 페이지 캐시, CDN까지.

### 22.4 캐시가 답이 아닌 경우

우리 유의어 확장은 캐시를 **넣었지만**, 그 전에 **쿼리 자체를 고쳤다**(N+1 제거 + UNION ALL).
순서가 중요하다:

```
1. 구조를 고친다 (근본)
2. 그래도 반복 비용이 남으면 캐시한다 (완화)
```

캐시를 먼저 넣으면 **캐시 미스 경로의 성능이 그대로 남고**, 그게 롱테일 요청에서 터진다.
"평균은 좋은데 p99가 나쁘다"의 흔한 원인이다.

---
---

# 7부. 방법론

## 23장. A/B 판정 프로토콜 ★★

> **한 줄 요약** — 성능 개선의 절반은 **개선을 개선이라고 증명하는 일**이다.

### 23.1 우리가 정착시킨 절차

```
1. 가설을 쓴다            — 무엇이 왜 느린가, 메커니즘 수준으로
2. 예측을 못 박는다        — 어떤 지표가 어느 방향으로 얼마나 (14.3절)
3. 변인을 하나만 바꾼다     — 코드 or 설정, 둘 다 바꾸면 판정 불가
4. 같은 조건을 만든다      — 시작 전 DB 무부하 CPU, 워밍업, 시나리오, 시각 정렬
5. 반복한다              — before는 최소 2회로 노이즈 밴드를 만든다
6. 과도구간을 뺀다        — 레벨 전환 직후 60초 제외한 정상상태로 비교
7. 판정 기준을 미리 정한다  — "20% 이상 차이날 때만 유의미"
8. 한계를 쓴다            — 1회 실행, warm cache, 합성 데이터 등
```

### 23.2 개별 항목이 왜 필요한가

**② 예측을 못 박기** — 결과를 보고 해석을 만드는 것(HARKing)을 막는다.
8개 예측이 방향과 크기까지 맞으면 1회 실행의 약점을 상당 부분 상쇄한다.

**③ 변인 하나** — 우리는 코드 병합과 병렬 워커를 **동시에** 바꾸는 바람에
어느 쪽 효과인지 몰라서 2×2 실험을 따로 해야 했다. 그리고 그 2×2도 캐시 오염 때문에
다시 해야 했다. **비용이 큰 실수다.**

**⑤ 반복** — before를 2회 돌리면 **노이즈 밴드**가 생긴다.
우리 환경은 ±11%였고, 개선폭 +114%는 그 밴드의 10배가 넘어 판정이 명확했다.

**⑥ 과도구간** — VU 레벨이 올라가는 순간 요청이 몰려 일시적으로 무너진다.
그 구간을 포함하면 "노이즈"로 보이는데, 정체를 보면 **무작위가 아니라 전환 과도구간**이다.
앞 60초를 빼고 정상상태 RPS로 비교하면 **결론이 뒤집히는 경우가 실제로 있었다.**

**⑦ 판정 기준 사전 설정** — 우리는 "최소 차이 20%"를 baseline 문서에 먼저 적었다.
안 그러면 5% 차이를 개선이라고 주장하게 된다.

**⑧ 한계 명시** — 예:
"warm cache 측정이라 프로덕션의 물리 I/O는 여기에 더해진다",
"합성 데이터의 halfvec 값이 모든 행에서 동일해 타이밍 결론에는 영향 없지만
Stage 1 선별의 현실성은 담보되지 않는다".

### 23.3 마이크로벤치의 함정 — 워밍업과 순서 효과

`MATERIALIZED` 개선을 잴 때 쓴 방법:

- HEAD의 SQL과 작업 트리의 SQL을 **각각 `PREPARE`** 한다
- **워밍업 16회**로 버퍼 캐시와 플랜 전환(6장의 계단)을 끝낸다
- **라운드로빈 15회**의 **중앙값**을 쓴다 (A,B,A,B,… 순서 효과 제거)

워밍업을 안 하면 6회차의 계단을 우연히 밟아 4배 차이가 나온다.
**"연속 실행 시 몇 회차부터 값이 안정되는가"를 먼저 보는 습관**이 필요하다.

### 23.4 결과 동등성 검증

성능 변경이 **결과를 바꾸지 않았다**는 것도 증명해야 한다.
우리 방법: **값이 전부 다른 데이터**로 기존 구현과 새 구현을 둘 다 돌려
**차집합 0행 + 값까지 일치**를 확인했다.

> 값이 같은 데이터(예: 모든 행의 벡터가 동일)로 하면 **아무것도 검증되지 않는다.**
> 실제로 우리 벤치 데이터가 그 함정에 빠져 있었고(비상관 서브쿼리가 InitPlan으로 1회 평가),
> 동등성 검증만 별도 데이터로 다시 했다.

---

## 24장. 오진 3건 복기 ★★

> **한 줄 요약** — 이 문서에서 가장 값어치 있는 부분. **틀렸던 이유에 패턴이 있다.**

### 24.1 오진 ① "TOAST가 범인이다"

**주장했던 것**: cross-scoring 비용의 대부분이 TOAST 간접 참조다. 블록 접근의 64%가 TOAST 인덱스 조회.

**실제**: 그 블록들은 대부분 캐시 히트라 마이크로초였다. 진짜 비용은
**블록을 하나도 안 건드리는 CPU 파싱**이었고, 그건 어떤 카운터에도 안 잡혔다.

**왜 틀렸나 — 관측 가능성 편향(availability/streetlight effect)**
계기판에 숫자가 큰 항목이 있으면 그게 원인처럼 보인다.
**"세면 보이는 쪽으로 추론이 쏠린다."**

**어떻게 피하나**
- 가설이 예측하는 **다른** 관측치를 정해놓고 확인한다.
  (TOAST가 범인이면 → 캐시를 비웠을 때 시간이 크게 늘어야 한다. 안 늘었다.)
- 시간의 총합과 항목별 합이 안 맞으면 **"안 잡히는 항목"이 있다고 가정**한다.

### 24.2 오진 ② "퍼널이 처리량 회귀의 원인이다"

**주장했던 것**: cross-scoring을 2단계 퍼널로 바꾼 뒤 처리량이 −28%. `stage1 AS MATERIALIZED`가
배리어라서 파이프라인이 끊겼다. 옛 단일 쿼리는 같은 840행을 한 번의 스캔으로 흘려보내며
I/O와 CPU를 중첩시켰다.

**실제**:
- 옛 단일 쿼리도 흘려보내지 않는다. 플랜에 `WindowAgg`, `GroupAggregate`, `Sort`가 있고,
  `ROW_NUMBER() OVER (PARTITION BY ...)`는 **입력 전체를 정렬해야 하는 완전 블로킹 연산**이다.
  **배리어 구조는 양쪽 동일하다.**
- 실측하면 퍼널이 수정 전에도 레거시보다 **3.8배 빨랐다**(24.0 vs 91.2ms).
  블록도 −58%로 줄어 문서가 잰 iowait −65%와 일치한다.

**왜 틀렸나 — 그럴듯한 메커니즘을 실측 없이 채택**
"MATERIALIZED = 배리어 = 파이프라인 차단"은 **문법적으로 맞는 말**이라 설득력이 있다.
하지만 상대 쪽 플랜을 안 봤다. **비교 대상의 플랜을 안 보고 한쪽만 설명하면 반드시 틀린다.**

**어떻게 피하나**
- "A가 B보다 느린 이유"를 설명하기 전에 **A와 B의 플랜을 나란히 놓는다.**
- 메커니즘 설명은 **숫자 예측을 동반**해야 한다. 예측이 없으면 반증 불가능한 이야기다.

### 24.3 오진 ③ "workers=0이 승리의 원인이다" → "시너지에서만 이득이다" → 둘 다 틀림

3단계로 굴러갔다:

1. 코드 개선과 `workers=0`을 **동시에** 적용하고 큰 개선을 봤다 → "workers=0 덕분"
2. 2×2 변인 분리를 했더니 workers=0 **단독은 손해**로 나왔다 → "시너지에서만 이득"
3. **측정 자체가 잘못돼 있었다**(캐시 히트로 하이브리드 코어 미실행). 캐시 미스 모드로 재측정하니
   → **"workers=0의 이득은 독립적으로 존재하고, 체크아웃 횟수는 측정 가능한 효과가 없다"**

**왜 틀렸나 — ① 변인 동시 변경 ② 측정 대상 미실행**

**어떻게 피하나**
- **변인은 하나씩.** 두 개를 동시에 바꿔야 한다면 2×2를 처음부터 계획한다.
- **측정 전에 "측정 대상이 실행되는가"를 확인한다.** 로그/트레이스에서 그 코드 경로가
  실제로 도는지 1회 확인하는 데 5분이면 된다. 안 하면 몇 주를 날린다.

### 24.4 세 오진의 공통 구조

```
관측 → (검증 없이) 메커니즘 채택 → 그 메커니즘에 맞는 조치 → 효과 애매 → 해석 추가
```

건강한 구조는 이것이다:

```
관측 → 가설 → 가설이 예측하는 "다른" 관측 → 그걸 확인 → 조치 → 사전 선언한 지표로 판정
```

> **면접에서 실패담을 말할 때의 요령**: "이런 실수를 했습니다"로 끝내면 마이너스다.
> **"그래서 프로세스를 이렇게 바꿨습니다"** 까지 가야 한다.
> 위 세 건은 각각 (a) 안 잡히는 비용 가정, (b) 양쪽 플랜 대조, (c) 측정 대상 실행 확인
> 이라는 **재발 방지 규칙**으로 이어졌다.

---
---

# 8부. 면접 대비

## 25장. 예상 질문 25개와 답변 골격

각 답변은 **원리 → 내 사례(숫자) → 트레이드오프 → 검증법** 4단으로 짰다.
외우지 말고 **4단 구조만 기억**하면 어떤 변형 질문에도 대응된다.

---

### A. 반드시 나오는 것 (1~8)

**Q1. N+1 문제가 뭔가요? 겪어보셨나요?**

> **원리** — 부모 N건을 조회한 뒤 각 부모의 연관을 얻으려고 쿼리를 N번 더 날리는 패턴입니다.
> JPA에서는 지연 로딩 컬렉션을 순회할 때 자동으로 생깁니다.
> **사례** — 저는 JPA 지연 로딩이 아니라 코드의 3중 루프로 만들어진 형태를 만났습니다.
> 검색어 유의어 확장에서 검색당 평균 5.7쿼리가 나갔습니다.
> **여기서 흥미로웠던 건 DB 시간은 3ms로 전체 예산의 0.3%밖에 안 됐다는 점입니다.**
> 진짜 비용은 두 가지였습니다. 첫째, `@Transactional`이 없어서 리포지토리 호출마다
> Spring Data JPA가 read-only 트랜잭션을 열어 **커넥션 체크아웃이 5.7회**였습니다.
> 둘째, 이게 BM25/벡터 병렬 실행 **전에 직렬로** 붙어 TTFB에 그대로 얹혔습니다.
> **조치** — 양방향 조인 1쿼리로 합치고, id 반복 조회를 `IN` 배치로 바꾸고,
> 확장 결과 자체를 Caffeine에 30분 캐시했습니다.
> **트레이드오프** — 캐시를 넣었으니 무효화 지점을 전수 조사해야 했습니다.
> 유의어 변경 7개 메서드와 term 삭제 경로에 `@CacheEvict`를 붙였고,
> **degrade된 결과는 캐시하면 안 되므로** `@Cacheable` 대신 `CacheManager` 수동 put을 썼습니다.
> **검증** — 쿼리 수는 로그로, 커넥션 체크아웃은 Hikari 메트릭으로, 지연은 트레이스로 확인했습니다.

**꼬리질문 대비**: fetch join vs `@EntityGraph` vs `@BatchSize` 차이,
fetch join으로 컬렉션 2개를 조인하면 왜 안 되는지(카테시안 곱), `distinct`의 의미.

---

**Q2. OSIV가 뭔가요? 왜 끄나요?**

13.8절의 답변 골격을 그대로 쓴다. 핵심 3문장:
1. 요청 끝날 때까지 영속성 컨텍스트가 열려 있어 **커넥션 리스 시간이 길어진다.**
2. 우리는 **경합이 없는 VU1에서도 쿼리중 비율이 34~38%뿐**인 걸 보고 구조적 상수라고 판단했다.
3. 전부 끄지 않고 **`/api/**`만** 제외해서, Thymeleaf 뷰 56개의 지연 로딩은 유지했다.

**꼬리질문 대비**:
- "끄면 뭐가 깨지나요?" → `LazyInitializationException`. 실제로 회귀 1건 발견(`/api/liked-items`).
  **그리고 그게 드러나는 게 장점이다** — OSIV는 N+1을 숨긴다.
- "왜 전부 안 껐나요?" → 템플릿 렌더링이 지연 로딩에 의존. 리스크 대비 이득으로 판단.
- "성능 효과는요?" → 쿼리 안 고치고 포화 처리량 +114%, `blks/req`는 불변.

---

**Q3. 커넥션 풀 크기는 어떻게 정하나요?**

> **원리** — HikariCP 공식은 `(코어 수 × 2) + 디스크 수`이고, 여기서 코어는 **DB 서버**의 것입니다.
> PostgreSQL은 커넥션당 프로세스라서 코어보다 많은 커넥션은 컨텍스트 스위칭만 늘립니다.
> Little's Law로 보면 처리량 = 풀 크기 ÷ 평균 점유 시간인데, 풀을 키워도 경합으로 점유 시간이
> 더 늘면 처리량은 오히려 줍니다.
> **사례** — DB가 2 vCPU라 공식상 5였고, 실제로 5/8/10/15를 전부 측정했습니다.
> 8이 국소 최적이었고 **10부터 오히려 나빠졌으며, 15에서는 50 VU 구간에서 120초 정체 후
> 폭주하는 패턴**이 나왔습니다. 그 구간에 DB 두 코어가 98~99%로 75초 이상 포화였습니다.
> **결론이 반전** — 그 뒤 커넥션 점유 패턴을 고치니 풀 5가 이전의 풀 8을 능가했고,
> 최종적으로 OSIV를 걷어내니 풀 5로 처리량이 2배가 됐습니다.
> **그래서 풀 크기는 원래 병목이 아니었습니다.**
> **검증** — 정점에서의 커넥션 획득 대기 시간으로 판정합니다. 우리 경우 0.001초라
> "풀은 더 이상 병목이 아니다"라고 말할 수 있습니다.

---

**Q4. 트랜잭션 범위를 어떻게 잡나요?**

> **원리** — 트랜잭션 경계는 **원자성이 필요한 범위**이지 비즈니스 로직의 범위가 아닙니다.
> Spring에서는 트랜잭션이 열려 있는 동안 커넥션이 그 스레드에 묶입니다.
> **사례** — 검색어 임베딩 조회 메서드에 `@Transactional`이 붙어 있어서,
> 캐시 미스 시 **Clova HTTP 호출이 끝날 때까지 커넥션을 붙잡고** 있었습니다.
> 롱테일 검색어가 풀 고갈을 증폭시키는 구조였습니다.
> 또 하이브리드 검색은 클래스 레벨 `@Transactional`이라 벡터 future를 기다리는 최대 5초 동안
> 커넥션을 물고 있었고, 이를 `TransactionTemplate`으로 Phase A/B/C로 쪼갰습니다.
> **트레이드오프** — 트랜잭션을 쪼개면 그 사이의 원자성이 사라집니다.
> 우리는 조회 경로라 문제없었지만, 쓰기 경로였다면 보상 로직이 필요했을 겁니다.
> **함정** — 비동기 작업을 "정리 차원에서" 트랜잭션 안으로 옮기면 앰비언트 트랜잭션에 합류해
> 원래 문제로 되돌아갑니다. 실제로 그 위험이 있어서 벡터 검색은 별도 executor에 남겼습니다.

---

**Q5. 인덱스를 만들었는데 안 타요. 왜죠?**

> **원리** — 대부분 인덱스가 없어서가 아니라 **조건의 형태** 때문입니다.
> 컬럼에 함수 적용, 암묵적 형변환, 선두 와일드카드 LIKE, 복합 인덱스의 선두 컬럼 누락,
> 그리고 **서로 다른 테이블에 걸친 OR**입니다. 통계가 낡아서 플래너가 Seq Scan을 고르는 경우도 있습니다.
> **사례** — 유의어 조회가 `WHERE t1.term IN (...) OR t2.term IN (...)` 형태였는데,
> OR가 조인된 **서로 다른 별칭**에 걸려 있어서 어느 쪽으로도 인덱스 구동이 안 됐습니다.
> 조인을 전부 만든 뒤 Join Filter로 거르니 비용이 검색어가 아니라 **term 테이블 크기에 비례**했습니다.
> `UNION ALL` 두 분기로 쪼개서 각각 인덱스 스캔을 타게 했고,
> 역방향 조회에 인덱스가 아예 없어서(FK 인덱스 누락) 그것도 추가했습니다.
> 로컬 재현으로 **term 600k 기준 72.4ms → 0.078ms**입니다.
> **가장 중요한 건** — 인덱스만 추가했을 때는 17.2 → 14.0ms로 거의 안 변했습니다.
> **쿼리 재작성이 핵심이고 인덱스는 보조**였습니다.
> **검증** — `EXPLAIN (ANALYZE, BUFFERS)`로 노드가 Index Scan으로 바뀌었는지,
> buffers가 1,567 → 66으로 줄었는지 확인했습니다.

---

**Q6. 실행계획을 어떻게 읽나요?**

2장 전체. 짧게 답한다면:
> `actual time`은 소요가 아니라 **누적 시각**이고 자식을 포함합니다. `loops`가 있으면 곱해야
> 총비용입니다. 그리고 제가 가장 많이 쓰는 건 **부모 시간에서 자식 시간 합을 뺀 값**입니다.
> 그게 그 노드 자체가 쓴 시간이거든요. 실제로 조인이 1.2ms인데 부모가 86.5ms인 계획을 보고
> **행별 표현식 평가**가 범인이라는 걸 특정했습니다. `VERBOSE`로 target list를 보면
> `l2_normalize(($1)::halfvec)`가 들어 있어서 확정할 수 있었습니다.

---

**Q7. 프리페어드 스테이트먼트가 오히려 느려질 수 있나요?** ← 여기서 차별화된다

> **원리** — 네. PostgreSQL은 파라미터 쿼리를 처음 5회는 custom plan으로 실행하고,
> 그 평균 비용과 generic plan 비용을 비교해서 generic이 크게 비싸지 않으면 이후 generic을 씁니다.
> generic plan은 `$1`이 미지수로 남아 **상수 접기가 불가능**합니다.
> pgjdbc도 `prepareThreshold=5`라 커넥션마다 5회 후 서버 측 statement로 승격됩니다.
> 두 임계값이 겹쳐서 **운영은 사실상 항상 generic 상태**입니다.
> **사례** — 저희 벡터 쿼리는 11.8KB짜리 임베딩 텍스트를 파라미터로 받는데,
> PG 12부터 한 번만 참조되는 CTE가 인라인되면서 그 파싱 표현식이 조인의 행별 계산식으로
> 들어갔습니다. generic plan이 되는 순간 **행마다 11.8KB를 파싱**하게 됐습니다.
> 행당 파싱 97µs, 정작 목적인 거리 연산은 2.5µs로 **38배**였습니다.
> `plan_cache_mode=auto`에서 같은 쿼리를 10회 실행하면 **6회차에서 4.3ms → 21ms로 뛰고
> 다시 안 돌아옵니다.**
> **조치** — `AS MATERIALIZED`로 CTE를 다시 최적화 장벽으로 만들어 파싱을 쿼리당 1회로 고정했습니다.
> 배포 SQL 실측으로 **91.2 → 5.3ms(17.4배)**, 퍼널 쿼리 **24.0 → 3.6ms(6.7배)** 입니다.
> **주의** — HNSW `ORDER BY … LIMIT`의 캐스트에는 적용하면 안 됩니다. 그건 인덱스 탐색키라
> CTE로 빼면 Index Scan이 Seq Scan으로 떨어집니다(0.62ms → 3.71ms).
> **검증** — `EXPLAIN (VERBOSE)`에 `$1`이 남아 있는지로 generic 여부를 확인하고,
> `DB_URL`에 `prepareThreshold=0`을 붙이면 재배포 없이 가설 전체를 판정할 수 있습니다.

---

**Q8. 성능 개선을 했다는 걸 어떻게 증명했나요?**

23장. 핵심은:
> 개선 **전에** 어떤 지표가 어느 방향으로 얼마나 움직여야 하는지 8개를 문서에 적어두고
> 그다음에 측정했습니다. 결과에 맞춰 해석을 만드는 걸 막기 위해서입니다. 8개 전부
> 방향과 크기까지 맞았고, 그중 하나는 **"DB 일의 양은 변하지 않아야 한다"** 는
> **불변 예측**이었습니다. `blks/req`가 17.6k → 16.9k로 그대로여서,
> "쿼리를 빠르게 한 게 아니라 커넥션을 빨리 돌려준 것"이라는 주장을 뒷받침했습니다.

---

### B. 나오면 가산점 (9~18)

**Q9. 커넥션 풀이 고갈되면 어떻게 진단하나요?**
> Hikari 메트릭 3개(`active`, `idle`, `pending`)와 획득 대기 시간을 봅니다. 그다음
> **"쿼리중 비율" = DB 실행 시간 ÷ 커넥션 점유 시간**을 계산합니다.
> 이 값이 낮으면 커넥션을 잡고 아무것도 안 하는 겁니다. **경합이 없는 최저 부하에서 재는 게
> 포인트**입니다. VU1에서도 38%면 혼잡 탓이 아니라 구조 문제입니다.

**Q10. 병렬 쿼리는 언제 켜고 언제 끄나요?**
15장. "OLTP + 코어 적음 + 동시성 높음 → 끈다. 검색 1건이 leader+worker 2개 = 프로세스 3개인데
코어가 2개면 동시 1건만 넘어도 초과다. 요청당 DB CPU가 2.1배 차이 났다."

**Q11. `IN`과 `= ANY`의 차이는?**
> 의미는 같지만 **SQL 텍스트가 다릅니다.** Hibernate가 `IN (:ids)`를 `IN ($1..$n)`으로 펼치면
> n마다 텍스트가 달라져서 `pg_stat_statements`의 queryid가 흩어지고 프리페어 캐시도 파편화됩니다.
> `= ANY(배열)`은 텍스트가 하나로 고정됩니다.
> **다만 저희는 그 전환에 부작용이 있었습니다** — 텍스트가 고정되니 generic plan에 빨리 진입해
> 재파싱 문제가 오히려 빨리 터졌습니다. 관측성 개선이 성능에는 손해였을 수 있어서,
> 되돌리는 대신 근본(재파싱)을 고쳤습니다.

**Q12. 캐시는 어디에 두나요?**
22장. "구조를 먼저 고치고, 남은 반복 비용에 캐시를 씁니다. 캐시를 먼저 넣으면 미스 경로가
그대로 남아 p99에서 터집니다." + 무효화 전수 조사 + degrade 캐시 금지.

**Q13. `work_mem`을 올려도 되나요?**
> 단독으로는 판단할 수 없습니다. **정렬/해시 노드마다, 병렬 워커마다, 커넥션마다 곱해집니다.**
> 16MB × 노드 2 × (1+워커 2) × 커넥션 5면 이론상 480MB인데 저희 DB RAM이 1GB였습니다.
> 다만 EXPLAIN에서 **디스크 스필 증거가 안 나와서 낮추지 않았습니다.**
> 위험해 보인다고 바꾸는 게 아니라 스필이 실제로 나는지 보고 정합니다.

**Q14. 벡터 검색은 어떻게 최적화하나요?**
17장. binary HNSW → halfvec 재랭킹 2단계, `ef_search`와 recall의 관계,
필터가 `ORDER BY … LIMIT` 위에 있으면 LIMIT을 못 채우는 문제.

**Q15. 통계가 왜 중요한가요?**
5장. `n_live_tup` 204 vs 실제 18,585 사례. 테이블별 autovacuum 오버라이드.

**Q16. 슬로우 쿼리를 어떻게 찾나요?**
> `pg_stat_statements`를 `total_exec_time` 기준으로 정렬합니다.
> **평균이 느린 쿼리보다 "빠른데 너무 자주 도는 쿼리"가 예산을 더 먹는 경우가 많습니다.**
> N+1이 딱 그 형태고요. 그리고 `mean_exec_time`과 `calls`를 같이 봐야 합니다.

**Q17. 읽기 전용 트랜잭션의 이점은?**
> Hibernate flush 모드가 `MANUAL`이 되어 더티 체킹 비용이 사라지고, 스냅샷 일관성이 보장되며,
> 일부 환경에서는 레플리카 라우팅 힌트로도 쓰입니다.

**Q18. 인덱스를 추가하는 비용은?**
> 쓰기마다 인덱스도 갱신되고(쓰기 증폭), 디스크와 캐시 메모리를 먹고, VACUUM 대상이 늘어납니다.
> 저희 DB에서 가장 큰 덩치가 `article_term`의 인덱스였습니다.
> 그리고 **인덱스를 추가해도 조건 형태가 막고 있으면 플래너가 못 씁니다.**

---

### C. 심화 / 꼬리질문 (19~25)

**Q19. CTE는 성능에 좋나요 나쁜가요?**
> "좋다/나쁘다"가 아니라 **PG 12에서 의미가 바뀐 기능**입니다. 11까지는 항상 물질화되는
> 최적화 장벽이었고, 12부터는 한 번만 참조되면 인라인됩니다. 그래서 **12 이전 코드가
> 12 이후에서 의미가 달라질 수 있습니다.** 저희가 그 케이스였습니다.

**Q20. 왜 `SELECT *`를 피하나요?**
> 네트워크·메모리도 있지만 PostgreSQL에서는 **TOAST detoast**가 큽니다.
> 큰 컬럼을 목록에서 빼면 out-of-line 청크를 읽는 일 자체가 안 일어납니다.
> 그리고 커버링 인덱스로 Index Only Scan을 노릴 여지도 사라집니다.

**Q21. 근사 검색(ANN)에서 필터를 어떻게 다루나요?**
9.5 / 17.4절. over-fetch, 부분 인덱스, 필터 제거 세 가지와 각각의 대가.

**Q22. 검색 엔진의 세그먼트 구조를 설명해보세요.**
18장. Lucene/ES `refresh_interval`, LSM memtable, ParadeDB mutable segment를 같은 축으로.
"버퍼 크기 = 읽기 지연의 고정비"라는 한 문장 + 3.5% 문서가 시간의 99%를 차지한 실측.

**Q23. 부하테스트 시나리오를 어떻게 설계하나요?**
> VU를 계단식으로 올려 **RPS 곡선의 정점 위치**를 찾습니다. 정점이 어디에 있고
> 거기서 무엇이 포화인지가 병목의 정체입니다. 그리고 **캐시 계층을 전부 나열해서
> 측정 대상이 실제로 실행되는지 먼저 확인**합니다. 저희는 이걸 안 해서
> 몇 주간의 실험이 캐시 앞단만 재고 있었던 적이 있습니다.

**Q24. 성능 개선이 결과를 바꾸지 않았다는 걸 어떻게 보장하나요?**
23.4절. 값이 전부 다른 데이터로 구/신 구현을 둘 다 돌려 **차집합 0행 + 값 일치**.
그리고 실제 DB를 쓰는 테스트로 고정.

**Q25. 가장 크게 실패했던 경험은?**
24장에서 하나를 고른다. 추천은 ③(측정 대상 미실행):
> 부하테스트로 몇 주간 A/B를 돌렸는데, 검색어가 반복돼 애플리케이션 캐시에 걸리는 바람에
> **정작 측정하려던 하이브리드 검색 코어가 실행되지 않고 있었습니다.**
> 그 상태에서 낸 "두 변경의 시너지에서만 이득"이라는 결론은 캐시 미스 모드로 재측정하니
> 재현되지 않았습니다. 이후로는 **측정 전에 대상 코드 경로가 실제로 도는지 로그로 1회 확인**하는
> 절차를 넣었습니다. 5분이면 되는 확인이었습니다.

---

## 26장. 숫자 암기 카드

면접에서 바로 꺼낼 수 있는 실측치. **출처와 조건을 함께 외운다.**

| # | 숫자 | 무엇 | 조건 |
|---|---|---|---|
| 1 | **17.4배 / 6.7배** | `AS MATERIALIZED` 적용 전후 (91.2→5.3ms, 24.0→3.6ms) | 합성 데이터 벤치, 워밍업 후 중앙값 |
| 2 | **97µs vs 2.5µs** | 행당 파라미터 파싱 준비 vs 실제 거리 연산 | 2000행, generic plan 고정 |
| 3 | **6회차** | custom → generic plan 전환 후 4.3→21ms 계단 | `plan_cache_mode=auto`, pgjdbc 기본 |
| 4 | **11,823 바이트** | 쿼리 임베딩 텍스트 크기 (float 1024개) | 실측 |
| 5 | **+114%** | OSIV 해제 후 포화 레벨 RPS (5.30 → 11.35) | 부하테스트 1회, before 2회 |
| 6 | **430 → 195ms** | 요청당 커넥션 점유 시간 | VU2 |
| 7 | **40% → 82%** | 쿼리중 비율 (점유 중 실제 쿼리 비중) | VU2 |
| 8 | **17.6k → 16.9k** | `blks/req` — **거의 불변** (일의 양은 그대로) | VU5 |
| 9 | **5.7쿼리 → 1쿼리** | 유의어 확장 N+1 제거 | 검색당 평균 |
| 10 | **72.4ms → 0.078ms** | OR → UNION ALL + 인덱스 (term 600k) | 로컬 합성, 전부 캐시 히트 |
| 11 | **2.1배** | `workers=2` vs `0`의 요청당 DB CPU (1.22~1.48 vs 0.62~0.79 core-s) | 캐시 미스 모드 |
| 12 | **0.230 ms/doc** | ParadeDB mutable 세그먼트 문서당 고정비 | 프로덕션 실측 |
| 13 | **3.5% → 99%** | mutable 문서 비율 → 쿼리 시간 비중 | 645/18,585 |
| 14 | **21%(3,457블록)** | 중복 `article` 조인이 쓰던 요청당 블록 | 요청당 총 16,903블록 |
| 15 | **2.94 rps** | 이론 천장 (2코어 ÷ 0.68 core-s/req), 실측 피크 2.48 = 84% | 개선 전 |

> ⚠️ **말할 때 조건을 빼먹지 말 것.** "17.4배 빨라졌습니다"는 과장으로 들리지만,
> "합성 데이터에서 배포 SQL로 워밍업 후 잰 중앙값 기준 17.4배이고, 프로덕션 재현은
> 아직 못 했습니다"는 신뢰를 준다.

---

## 27장. 용어집

| 용어 | 뜻 |
|---|---|
| **custom plan / generic plan** | 파라미터 값을 알고 매번 세우는 계획 / 값을 모른 채 한 번 세워 재사용하는 계획 |
| **상수 접기 (constant folding)** | 계획 단계에서 상수 표현식을 미리 계산해 결과를 계획에 박는 것 |
| **최적화 장벽 (optimization fence)** | 플래너가 넘어서 최적화하지 못하는 경계. PG 11까지 CTE가 그랬다 |
| **인라인 (CTE inlining)** | CTE를 본문에 펼쳐 넣어 플래너가 함께 최적화하게 하는 것. PG 12부터 기본 |
| **`prepareThreshold`** | pgjdbc가 서버 측 프리페어드 스테이트먼트로 승격시키는 실행 횟수. 기본 5 |
| **BitmapOr** | 여러 인덱스 스캔 결과(TID 비트맵)를 OR로 합치는 노드 |
| **선택도 (selectivity)** | 조건이 걸러내는 비율. 낮을수록(적게 남길수록) 인덱스가 유리 |
| **커버링 인덱스 / Index Only Scan** | 필요한 컬럼이 인덱스에 다 있어 힙을 안 읽는 것. visibility map이 최신이어야 함 |
| **TOAST** | 큰 컬럼을 압축·별도 저장하는 구조. 읽을 때 detoast 비용 발생 |
| **HNSW** | 근사 최근접 이웃(ANN) 그래프 인덱스. `ef_search`가 탐색 폭 |
| **recall (재현율)** | 정답 상위 k개 중 실제로 찾아낸 비율. ANN의 품질 지표 |
| **양자화 (quantization)** | 벡터를 더 작은 표현으로 줄이는 것. halfvec(2B), binary(1bit) |
| **halfvec / bit(n)** | pgvector의 float16 벡터 / 이진 벡터 타입 |
| **해밍 거리** | 두 이진 벡터의 다른 비트 수. XOR + popcount로 매우 싸다 |
| **NSF (Normalized Score Fusion)** | 서로 다른 척도의 점수(BM25/벡터)를 각각 정규화해 가중합하는 병합 방식 |
| **cross-scoring** | 한쪽 검색에만 등장한 결과의 반대쪽 점수를 따로 계산해 채우는 것 |
| **세그먼트 / seal / merge** | 검색 인덱스의 불변 단위 / 가변 버퍼를 봉인 / 여러 세그먼트를 합치는 백그라운드 작업 |
| **mutable segment** | ParadeDB의 쓰기 버퍼. term dictionary가 없어 검색 시 선형 스캔 |
| **OSIV** | Open Session In View. 요청 전체에 영속성 컨텍스트를 열어두는 패턴 |
| **체크아웃 / 점유(lease)** | 풀에서 커넥션을 꺼내는 것 / 반납까지 붙잡고 있는 시간 |
| **오버서브스크립션** | 실행 가능한 프로세스 수가 코어 수를 초과한 상태. 컨텍스트 스위칭 비용 발생 |
| **Little's Law** | L = λ × W. 동시 처리 건수 = 도착률 × 체류 시간 |
| **`work_mem`** | 정렬/해시 노드 **하나당** 할당되는 메모리. 노드·워커·커넥션 수만큼 곱해진다 |
| **autovacuum / `n_live_tup`** | 죽은 튜플 회수·통계 갱신 데몬 / 통계상 추정 살아있는 행 수 |
| **`blks/req`, `core-s/req`** | 요청당 만진 8KB 블록 수 / 요청당 소비한 DB CPU 초 |
| **degrade** | 일부 기능이 실패해도 축소된 결과로 응답하는 것. **캐시하면 안 된다** |
| **single-flight** | 동일 키의 동시 요청을 하나만 실행하고 결과를 공유하는 기법 |
| **stall-burst (정체-폭주)** | 요청이 한동안 멈춰 있다가 한꺼번에 처리되는 패턴. 평균 지표에 안 잡힌다 |

---

## 28장. 참고자료

### PostgreSQL 공식 문서

- [PREPARE — custom/generic plan과 5회 규칙](https://www.postgresql.org/docs/current/sql-prepare.html)
- [Query Planning 설정 (`plan_cache_mode` 등)](https://www.postgresql.org/docs/current/runtime-config-query.html)
- [WITH Queries (CTE, `MATERIALIZED`)](https://www.postgresql.org/docs/current/queries-with.html)
- [Using EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html)
- [How Parallel Query Works](https://www.postgresql.org/docs/current/how-parallel-query-works.html)
- [Parallel Query 개요](https://www.postgresql.org/docs/current/parallel-query.html)
- [TOAST](https://www.postgresql.org/docs/current/storage-toast.html)
- [Index-Only Scans and Covering Indexes](https://www.postgresql.org/docs/current/indexes-index-only-scans.html)
- [Routine Vacuuming](https://www.postgresql.org/docs/current/routine-vacuuming.html)
- [Planner Statistics](https://www.postgresql.org/docs/current/planner-stats.html)
- [pg_stat_statements](https://www.postgresql.org/docs/current/pgstatstatements.html)
- [Monitoring Statistics](https://www.postgresql.org/docs/current/monitoring-stats.html)

### 드라이버 / 풀 / 프레임워크

- [pgJDBC — Server Prepared Statements (`prepareThreshold`)](https://jdbc.postgresql.org/documentation/server-prepare/)
- [HikariCP — About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [Vlad Mihalcea — The Open Session In View Anti-Pattern](https://vladmihalcea.com/the-open-session-in-view-anti-pattern/)
- [Vlad Mihalcea — Spring Transaction and Connection Management](https://vladmihalcea.com/spring-transaction-connection-management/)
- [Vlad Mihalcea — PostgreSQL plan_cache_mode](https://vladmihalcea.com/postgresql-plan-cache-mode/)
- [Spring Data JPA N+1: Fetch Join and EntityGraph](https://sharpskill.dev/en/blog/spring-boot/spring-data-jpa-n-plus-1-fetch-join-entitygraph)

### 쿼리 튜닝

- [CYBERTEC — Avoid OR for better PostgreSQL query performance](https://www.cybertec-postgresql.com/en/avoid-or-for-better-performance/)
- [CYBERTEC — Foreign key indexing and performance](https://www.cybertec-postgresql.com/en/index-your-foreign-key/)
- [depesz — PG12 CTE materialization 변경](https://www.depesz.com/2019/02/19/waiting-for-postgresql-12-allow-user-control-of-cte-materialization-and-change-the-default-behavior/)
- [Michael Paquier — Postgres 12: WITH clause and materialization](https://paquier.xyz/postgresql-2/postgres-12-with-materialize/)
- [pgMustard — Increasing max_parallel_workers_per_gather](https://www.pgmustard.com/blog/max-parallel-workers-per-gather)
- [pganalyze — UNION과 subquery pull-up](https://pganalyze.com/blog/5mins-postgres-UNION-subquery-pull-up-performance)

### 벡터 / 검색 엔진

- [pgvector README](https://github.com/pgvector/pgvector)
- [Jonathan Katz — Scalar and binary quantization for pgvector](https://jkatz05.com/post/postgres/pgvector-scalar-binary-quantization/)
- [ParadeDB — Write Throughput (`mutable_segment_rows`)](https://docs.paradedb.com/documentation/performance-tuning/writes)
- [ParadeDB — Postgres as a Search Engine: The Write Performance Problem](https://www.paradedb.com/blog/increased-write-performance)
- [Elasticsearch refresh_interval의 트레이드오프](https://pulse.support/kb/what-is-elasticsearch-refresh-interval)

### 방법론

- [Brendan Gregg — The USE Method](https://www.brendangregg.com/usemethod.html)
- [Little's Law (Wikipedia)](https://en.wikipedia.org/wiki/Little%27s_law)

### 이 저장소의 1차 자료 (숫자의 출처)

| 문서 | 내용 |
|---|---|
| [`docs/operations/QUERY_PARAM_REPARSE.md`](../operations/QUERY_PARAM_REPARSE.md) | 6·7장. 행별 재파싱 전체 분석 + 재현 절차 |
| [`docs/operations/PGSS_SEARCH_COST.md`](../operations/PGSS_SEARCH_COST.md) | 1·3·4·17·20장. DB 예산 배분, stage-1 재활용, 퍼널 |
| [`docs/operations/BM25_MUTABLE_SEGMENT_RUNBOOK.md`](../operations/BM25_MUTABLE_SEGMENT_RUNBOOK.md) | 18장. 세그먼트 실측과 임계치 산정 |
| [`docs/operations/DB_LOAD_REDUCTION.md`](../operations/DB_LOAD_REDUCTION.md) | 5·15·16장. 서버 파라미터 실측 |
| [`docs/operations/CROSS_SCORING_NEXT.md`](../operations/CROSS_SCORING_NEXT.md) | 17장. 보충 최적화 후보 |
| [`docs/search/SEARCH_TRACE_ANALYSIS.md`](../search/SEARCH_TRACE_ANALYSIS.md) | 8·21장. 트레이스 기반 구간 분해 |
| [`load-test/results/2026-08-06-search-ramp-limit-finder.md`](../../load-test/results/2026-08-06-search-ramp-limit-finder.md) | 11·15장. 풀 크기 실험, 2×2 변인 분리 |
| [`load-test/results/2026-08-08-search-checkout-workers-2x2-cachemiss.md`](../../load-test/results/2026-08-08-search-checkout-workers-2x2-cachemiss.md) | 15장. 캐시 미스 모드 재측정 |
| [`load-test/results/2026-08-13-query-param-reparse-ab.md`](../../load-test/results/2026-08-13-query-param-reparse-ab.md) | 23장. 과도구간 판정 프로토콜 |
| [`load-test/results/2026-08-16-search-db-cost-3commits-ab.md`](../../load-test/results/2026-08-16-search-db-cost-3commits-ab.md) | 14장. 유휴 점유 가설의 출발점 |
| [`load-test/results/2026-08-17-osiv-connection-hold-ab.md`](../../load-test/results/2026-08-17-osiv-connection-hold-ab.md) | 13·14장. OSIV A/B 정산 |

### 관련 커밋

| 커밋 | 내용 | 장 |
|---|---|---|
| `fe087b6` | 벡터 쿼리 파라미터 행별 재파싱 제거 (`MATERIALIZED`) | 6, 7 |
| `cf469ee` | 유의어 확장 N+1 제거 — 조인 1쿼리 + 캐시 | 10 |
| `1012da0` | 유의어 확장 쿼리를 UNION ALL로 재작성 + FK 인덱스(V1_36) | 8 |
| `838ae89` | cross-scoring·본검색 벡터 쿼리의 중복 article 조인 제거 | 9 |
| `19b0a64` | `/api/**` OSIV 해제 | 13 |
| `ad64c7a` | 하이브리드 검색 트랜잭션 스코프 축소 (Phase A/B/C) | 12 |
| `83211a5` | 임베딩 캐시 미스 시 Clova 호출 중 커넥션 점유 제거 | 12 |
| `eec4532` | 검색어 확장 쿼리를 Clova 임베딩 호출과 병렬화 | 21 |
| `05bb4d3` | cross-scoring 보충을 binary → halfvec 2단계 퍼널로 | 17 |
| `20fd2c3` | cross-scoring 보충에 Stage 1 후보 점수 재활용 | 20 |
| `0346ede` | cross-scoring 쿼리를 `= ANY` 배열 파라미터로 | 6 |

---

## 마무리 — 이 경험을 한 문장으로 정리한다면

> **"성능 최적화는 빠르게 만드는 일이 아니라, 무엇이 한계를 정하고 있는지 반복해서
> 다시 판정하는 일이었다."**

그리고 그 판정을 가능하게 한 건 화려한 도구가 아니라
**요청당 블록 수, 요청당 CPU 초, 커넥션 점유 시간 중 쿼리 비율** 같은
지극히 단순한 나눗셈 몇 개였다.
