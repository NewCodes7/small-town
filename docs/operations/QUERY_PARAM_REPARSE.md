# 벡터 쿼리 파라미터 행별 재파싱 — 검색 비용의 실제 지배 항목

**2026-08-12 작성.** `2026-08-12-search-unpushed-commits-ab.md`의 퍼널 회귀를 조사하다 발견.
측정 환경: PG 17.10 + pgvector 0.8.2, 프로덕션과 동형인 합성 데이터(153,000청크 / 18,000아티클,
아티클당 8.5청크, `clova_chunk_vectors` heap 9MB + TOAST 417MB, `attstorage='e'`).
재현 절차는 부록.

**재현 타당성**: 레거시 cross-scoring 쿼리가 이 환경에서 **86.0ms / 9,374블록**으로 나온다.
프로덕션 실측은 **93.3ms / 10,272블록**(`PGSS_SEARCH_COST.md` 3장)이다. 형태가 일치한다.

---

## 요약

- **검색 쿼리 시간의 대부분은 TOAST I/O도 벡터 연산도 아니고, 쿼리 파라미터로 받은
  11.8KB 텍스트를 halfvec으로 파싱하는 비용이다.** 그리고 그게 **행마다** 반복된다.
- 행당 비용: **실제 거리 연산 2.5µs vs 파싱 준비 97µs.** 38배다.
- pgjdbc가 커넥션마다 5회 실행 후 generic plan으로 전환하는 순간 계단식으로 뛰고
  **영구 고착**된다. 운영은 사실상 항상 이 상태에 있다.
- **직격탄은 cross-scoring 두 쿼리다** (배포된 SQL로 측정):
  레거시 단일쿼리 **91.2 → 5.3ms (17.4배)**, 퍼널 **24.0 → 3.6ms (6.7배)**.
- **본검색 `findArticlesByTwoStageSearch`는 해당하지 않는다.** 이 쿼리는 generic plan으로
  전환되지 않아 파라미터가 계속 상수로 접힌다 (5.6 → 5.4ms, **중립**).
  전환 여부는 쿼리마다 다르고 통계에 따라 뒤집힐 수 있어 수정은 **보험으로** 함께 넣었다.
- 퍼널은 수정 전에도 레거시보다 **3.8배 빨랐다**(91.2 vs 24.0). 부하테스트 문서가 지목한
  "MATERIALIZED 배리어" 진단은 근거가 없다.
- `PGSS_SEARCH_COST.md`가 "효과가 제한적"이라고 기각했던 **CTE 호이스팅이 사실은
  가장 큰 레버**였다.

---

## 1. 무엇이 일어나는가

### 앱이 보내는 것

`VectorSearchService.formatVectorForPostgres()`가 `float[1024]`를 텍스트로 만든다:

```
[-0.99233731,-0.64909404,0.73770413,-0.49603310, ... ]
```

실측 **11,823바이트**. DB는 이걸 그대로 쓸 수 없다. 벡터 연산을 하려면 ASCII 숫자 1024개를
파싱해 2048바이트 이진 배열로 바꿔야 한다 — 그게 `CAST(:queryEmbedding AS halfvec)`이고,
`l2_normalize()`가 정규화를 더한다.

### 왜 그게 행마다 도는가 — 조건 두 개가 겹쳐야 한다

코드는 파싱을 1회만 하도록 썼다:

```sql
WITH query_vec AS (SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec)
```

**조건 A — CTE가 더 이상 최적화 장벽이 아니다.** PG 11까지 CTE는 항상 별도 1회 실행이었다.
**PG 12부터 한 번만 참조되는 CTE는 본문에 인라인된다.** `query_vec`는 한 곳에서만
참조되므로 인라인되고, 표현식 덩어리가 조인의 행별 계산식 안으로 들어간다.

**조건 B — generic plan에서 파라미터가 상수로 접히지 않는다.** 처음 5회는 custom plan이라
`$1`이 진짜 상수로 취급돼 **계획 단계에서 한 번 계산되고 결과가 계획에 박힌다.**
6회차부터 generic plan으로 바뀌면 `$1`은 실행 시점까지 값을 모르는 자리가 되어
상수 접기가 불가능해지고, **행마다 실행**된다.

pgjdbc `prepareThreshold=5`(기본값) + `plan_cache_mode=auto`(기본값)라서
**커넥션마다 5번 지나면 이 상태에 들어가 다시 나오지 않는다.**

### EXPLAIN 증거

```
Nested Loop (actual time=0.234..86.542 rows=850)
  Output: cac.article_id, (ccv.embedding_normalized <#> l2_normalize(($1)::halfvec)), ...
     -> Nested Loop     (actual time=0.029..1.200 rows=850)    <- 자식 합계 1.2ms
     -> Index Scan ccv  (actual time=0.001..0.001 loops=850)   <- 조인도 공짜
```

자식이 1.2ms인데 부모가 86.5ms다. 차이 전부가 행별 표현식 평가다.
`l2_normalize(($1)::halfvec)`가 타깃리스트 안에 들어와 있는 것이 인라인의 직접 증거다.

custom plan에서는 같은 자리가 파싱된 리터럴로 대체된다(HNSW 경로 EXPLAIN에서 관찰):

```
Order By: (embedding_binary <~> '0111110110100110...'::bit(1024))
```

---

## 2. 비용 분해

2000행 테이블에서 같은 연산을 방식만 바꿔 측정 (generic plan 고정):

| 행마다 하는 일 | 행당 비용 |
|---|---|
| 파라미터 문자열(11.8KB)을 꺼내 복사 | ~23µs |
| 텍스트 → halfvec 파싱 (숫자 1024개) | ~63µs |
| `l2_normalize` (1024회 곱셈 + sqrt) | ~11µs |
| **소계 — 준비 작업** | **~97µs** |
| `<#>` 실제 거리 계산 | **~2.5µs** |

쿼리 안에 쿼리 벡터는 하나뿐인데, 850개 청크와 비교하면 **같은 문자열을 850번 파싱해
850번 같은 벡터를 만들고 850번 버린다.**

### 산수가 맞아떨어진다

| 쿼리 | 파싱 대상 행 수 | 예측 (행수 × 97µs) | 실측 |
|---|---|---|---|
| 레거시 cross-scoring | 850 | 82.9ms | **86.0ms** |
| 퍼널 Stage 2 | 170 | 16.6ms | **19.2ms** |

**영향받는 쿼리에 한해** 실행시간이 사실상 전부 이 파싱이다.

---

## 3. 실측 — 계단이 보인다

`plan_cache_mode=auto`(운영 기본값)에서 퍼널 쿼리를 연속 10회:

```
1: 4.61  2: 4.27  3: 4.28  4: 4.27  5: 4.27   ms   <- custom plan
6: 20.97 7: 19.23 8: 19.27 9: 19.55 10: 19.54 ms   <- generic plan 전환, 이후 고착
```

### 적용 전/후 — 저장소의 실제 SQL로 측정

`HEAD`의 SQL과 작업 트리의 SQL을 각각 `PREPARE`해서, **워밍업 16회로 캐시와 플랜 전환을
끝낸 뒤** 라운드로빈 15회 중앙값 (순서 효과 제거):

| 쿼리 | BEFORE | AFTER | 판정 |
|---|---|---|---|
| `computeSimilarityForArticleIds` (레거시 보충) | **91.18ms** | **5.25ms** | **17.4배** |
| `computeSimilarityForArticleIdsTwoStage` (퍼널) | **24.04ms** | **3.57ms** | **6.7배** |
| `findArticlesByTwoStageSearch` (본검색) | 5.64ms | 5.42ms | 중립 |

퍼널은 수정 전에도 레거시보다 3.8배 빨랐다(24.04 vs 91.18) — 부하테스트 문서의
"퍼널이 회귀 원인" 진단을 뒤집는 근거다.

### 왜 본검색은 해당하지 않나

`plan_cache_mode=auto`는 **generic plan의 추정 비용이 custom plan 평균보다 높지 않을 때만**
전환한다. 본검색은 `LIMIT :candidateLimit`이 HNSW 탐색 비용을 좌우하는데 그 값을 모르면
플래너가 비용을 크게 잡으므로 **계속 custom plan에 머문다** — 파라미터가 상수로 접히니
재파싱이 없다. EXPLAIN에 파싱된 리터럴이 그대로 박혀 있다:

```
Output: ... avg(((- ((c.embedding_normalized <#> '[-0.0340271,-0.053375244,...]')))))
                                                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                                  $1이 아니라 상수 = custom plan
```

반면 cross-scoring 쪽은 파라미터가 비용 추정에 거의 영향을 주지 않아 generic으로 넘어간다.

> ⚠️ **이 경계는 비용 추정에 달려 있어 데이터가 바뀌면 뒤집힐 수 있다.** 그래서 본검색에도
> 같은 수정을 넣었다 — 측정상 중립(5.64 → 5.42ms)이고 HNSW 인덱스 스캔도 그대로 유지된다.
> 공짜 보험이다.

## 4. 영향 범위

`ArticleChunkRepository`의 **12개 쿼리가 형태상 위험**하다 (`query_vec AS (` 정의 10곳,
수정 전 **MATERIALIZED 지정 0곳**). 다만 **실제로 비용을 내는지는 그 쿼리가 generic plan으로
전환되는지에 달려 있다** — 3장의 경계 참고. 아래 "파싱 대상 행 수"는 전환됐을 때의 노출량이다.

| 메서드 | 형태 | 행당 파싱 횟수 | 파싱 대상 행 수 |
|---|---|---|---|
| `findArticlesByTwoStageSearch` (+필터 변형 4개) | CTE 1회 참조 → 인라인 | 1 | `candidateLimit` (500) — **단 현 데이터에선 generic 미전환 = 실제 비용 0** |
| `computeSimilarityForArticleIds` | 〃 | 1 | 대상 아티클 전 청크 (~850) |
| `computeSimilarityForArticleIdsTwoStage` | 〃 + binary 캐스트 인라인 | halfvec 1 / binary 2 | Stage2 ~170 / Stage1 ~850 |
| `findTopChunksForAiSummary` | 〃, `q.vec`를 WHERE·ORDER BY 양쪽에서 사용 | 최대 2 | `candidateLimit` |
| `findFirstAndBestChunksByArticleIds` | 〃 | 1 | 지정 아티클 전 청크 |
| `findFirstAndTopChunksByArticleIds` | 〃 (RAG) | 1 | 지정 아티클 전 청크 |
| `findRelatedArticlesByTwoStageSearch` | **CTE 없음, 인라인 2회** | 2 | 후보 아티클 전 청크 |
| `findRelatedArticlesByRepresentativeChunk` | **CTE 없음, 인라인 2회** | 2 | **전체 아티클 전 청크(154k)** |

`findRelatedArticlesByRepresentativeChunk`가 최악이다. binary 임베딩이 없을 때의 fallback
경로지만, 타면 154k행 × 2회 파싱이다. `PGSS_SEARCH_COST.md`가 "관련 글 추천이 예산 밖에서
11%를 쓴다"고 적어둔 항목의 정체가 이것일 가능성이 높다.

### 해당하지 않는 곳

- `ArticleChunkAccuracyRepository`의 2개 쿼리는 `(SELECT vec FROM query_vec)` **스칼라
  서브쿼리 형태**를 두 번 쓴다. CTE가 2회 참조되므로 인라인되지 않고, 비상관 서브쿼리라
  InitPlan으로 1회만 평가된다 — **우연히 올바른 형태다.**
- `SearchQueryEmbeddingRepository`는 단일 행 INSERT라 애초에 1회 평가다.

---

## 5. 고치는 법

### 5.1 `query_vec AS (` → `query_vec AS MATERIALIZED (`

10곳. PG 12에서 인라인을 막으려고 추가된 키워드다. CTE가 다시 장벽이 되어 딱 한 번
실행되고, 결과 1행을 이후 조인이 참조한다. generic plan이든 아니든 파싱은 쿼리당 1회다.

**우선순위**: **cross-scoring 2개**(실측으로 확인된 유일한 회수처, 17.4배 / 6.7배)
→ 본검색 5개·AI요약/RAG 3개(현재는 중립, generic 전환에 대비한 보험).

### 5.2 CTE가 없는 2개는 CTE를 만들어 준다

`findRelatedArticlesByTwoStageSearch`, `findRelatedArticlesByRepresentativeChunk`는
`CAST(:queryEmbedding AS halfvec)`를 행마다 2번 평가한다. MATERIALIZED CTE로 묶는다.

### 5.3 퍼널 쿼리 — binary 캐스트도 함께

`computeSimilarityForArticleIdsTwoStage`의 Stage 1은 `CAST(:queryBinary AS bit(1024))`를
타깃리스트와 윈도우 `ORDER BY`에 각각 적어 행당 2회 평가한다. 해밍도 2회 계산된다.

```sql
WITH q AS MATERIALIZED (
    SELECT l2_normalize(CAST(:queryEmbedding AS halfvec)) AS vec,
           CAST(:queryBinary AS bit(1024))                AS bvec
),
stage1 AS MATERIALIZED (
    SELECT article_id FROM (
        SELECT article_id, hamming,
               ROW_NUMBER() OVER (PARTITION BY article_id ORDER BY hamming) AS rn
        FROM (
            SELECT cac.article_id, cac.embedding_binary <~> q.bvec AS hamming
            FROM clova_article_chunk cac
            JOIN article a ON cac.article_id = a.id
            CROSS JOIN q
            WHERE a.deleted_at IS NULL
              AND cac.embedding_binary IS NOT NULL
              AND cac.article_id = ANY(CAST(:articleIds AS bigint[]))
        ) h
    ) estimated
    WHERE rn <= :topK
    GROUP BY article_id
    HAVING AVG(cos(pi() * hamming / 1024.0)) >= :stage1Floor
    ORDER BY AVG(cos(pi() * hamming / 1024.0)) DESC
    LIMIT :stage2Limit
)
-- Stage 2는 q를 CROSS JOIN 하는 것 외 현행 그대로
```

`cos()` 환산을 집계 시점으로 옮겼지만 **`avg(cos(h))` 순서는 보존된다**
(`cos(avg(h)) != avg(cos(h))` 제약 유지 — `PGSS_SEARCH_COST.md` B' 참고).

**결과 동등성 검증 완료.** 값이 전부 다른 데이터(2,550청크 / 300아티클, binary 2,550개 모두
distinct, halfvec은 binary와 상관되게 생성)로 현재 퍼널 ≡ 개선안 ≡ Stage1행재사용안이
차집합 0행, 값까지 레거시 전수계산과 일치. **랭킹 리스크 없는 순수 성능 변경이다.**

### 5.4 ⚠️ HNSW `ORDER BY ... LIMIT`에는 적용 금지

`candidates` CTE의 `ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024)) LIMIT`는
**HNSW 탐색키**다. CTE로 끌어올리면 인덱스가 죽는다 (20k행 실측):

```
인라인 : Limit -> Index Scan using idx_..._hnsw          0.62ms
CTE화  : Limit -> Sort -> Nested Loop -> Seq Scan        3.71ms
```

154k행에서는 훨씬 큰 차이가 되고 `ef_search=250` 의미도 사라진다.

**규칙: halfvec 재랭킹 표현식만 끌어올린다. HNSW `ORDER BY ... LIMIT`에 붙은 binary
캐스트는 인라인으로 둔다.** 퍼널 Stage 1의 binary 캐스트는 인덱스 탐색키가 아니라
(`article_id = ANY(...)` 인덱스 스캔 위의 일반 표현식) 끌어올려도 안전하다 — 5.3의 3.2ms가 증거.

### 5.5 코드 배포 없이 가설 전체를 판정하는 법

`.env`의 `DB_URL`에 **`?prepareThreshold=0`**을 붙이면 pgjdbc가 unnamed statement를 쓰고
PG가 항상 실제 파라미터 값으로 계획한다 → **12개 쿼리 전부가 custom plan 쪽 숫자로 돌아간다.**
재배포 없이 `.env`만 고치면 되므로, 운영 서버 접근이 제한된 상황에서 1회 실행으로
이 문서 전체를 검증할 수 있다. 계획 시간은 0.008~0.5ms로 회수액 대비 무시 가능.

---

## 6. 기존 문서 정정

### `PGSS_SEARCH_COST.md` 3장 — "쿼리 정리는 효과가 제한적"

> 쿼리 정리(CTE 호이스팅, LATERAL)는 정렬·파싱만 줄이고 벡터 개수는 그대로라 효과가 제한적이다.

**틀렸다. CTE 호이스팅이 가장 큰 레버였다.** "파싱"이 부수적 비용이라는 전제가 깨진다 —
파싱이 비용의 거의 전부다. 벡터 개수를 줄이는 방향(A, B')이 잘못된 건 아니지만,
**투자 대비 회수는 호이스팅이 압도적으로 크고 랭킹 리스크가 0이다.**

### 왜 TOAST가 범인으로 보였나

"블록 접근의 64%가 TOAST 인덱스 조회"는 **틀린 관찰이 아니다.** 두 비용이 서로 다른
계기판에 잡힐 뿐이다:

- **TOAST 읽기**는 `shared_blks_read` / `toast_blks_read` **블록 카운터에 또렷이 찍힌다.**
- **파라미터 재파싱**은 블록을 하나도 건드리지 않는다. 순수 CPU이고
  `pg_stat_statements`에는 `exec_time` 총합에 섞일 뿐 **어떤 항목으로도 분리되지 않는다.**

세면 보이는 쪽으로 추론이 쏠린 것이다. 실제로 그 블록들은 대부분 캐시 히트라
마이크로초였고, 시간은 카운터에 안 잡히는 쪽에서 샜다.

### `2026-08-12-search-unpushed-commits-ab.md` — "MATERIALIZED가 배리어다"

> `stage1 AS MATERIALIZED`는 배리어다 (...) 옛 단일 쿼리는 같은 840행을 한 번의 스캔으로
> 흘려보내며 I/O와 CPU를 중첩시켰다.

**옛 단일 쿼리도 흘려보내지 않는다.** 플랜에 `Incremental Sort -> WindowAgg ->
GroupAggregate -> Sort`가 있고, `ROW_NUMBER() OVER (PARTITION BY ...)`는 입력 전체를
정렬해야 하는 완전 블로킹 연산이다. 배리어 구조는 양쪽 동일하다.

그리고 실측이 방향을 뒤집는다 — 정상 상태에서 퍼널은 **91.2 → 24.0ms로 3.8배 빠르고**,
블록도 9,374 → 3,941(**-58%**)로 문서가 잰 iowait -65%와 일치한다.
**퍼널의 설계 의도는 달성됐고, 처리량 -28%의 원인이 이 쿼리일 수는 없다.**

따라서 그 문서의 선택지 평가에서 `MATERIALIZED 제거`는 **효과 없음이 맞지만 이유가 다르고**,
`DB_POOL_SIZE 상향`보다 **이 문서의 5.1이 먼저**다. 점유시간을 우회하는 게 아니라
직접 줄이기 때문이다.

### `= ANY` 전환(`0346ede`)의 부수효과 — 미판정

A/B 문서는 #4를 "퍼널 on이면 레거시 쿼리 미호출"이라는 코드 근거로 원인에서 배제했다.
부하테스트 구성에 한해서는 맞다. 다만 **일반 트래픽에서는 검토되지 않은 부수효과가 있다.**

`IN (:articleIds)`는 Hibernate가 `IN ($1..$n)`으로 펼쳐 **n마다 SQL 텍스트가 달라진다.**
그래서 각 변형이 커넥션당 `prepareThreshold=5`에 좀처럼 도달하지 못하고 **custom plan에
머물렀다** — 즉 재파싱 폭탄을 우연히 피하고 있었다. `= ANY` 전환으로 텍스트가 하나로
고정되면서 **generic plan에 빠르게 진입해 고착된다.**

부하테스트는 보충 대상이 항상 100건이라 전후 모두 텍스트가 안정적이었으므로 이 차이가
드러나지 않는다. **실사용 트래픽에서는 n이 변하므로 `= ANY` 전환이 순손해였을 수 있다.**
관측 가능성(단일 queryid) 자체는 유지할 가치가 있으므로, 결론은 "되돌린다"가 아니라
**"5.1을 적용해 generic plan에서도 싸게 만든다"** 이다.

---

## 7. 검증 상태

| 항목 | 상태 |
|---|---|
| 행별 재파싱 메커니즘 | ✅ EXPLAIN 타깃리스트 + custom/generic 대조로 확정 |
| 행당 비용 분해 (97µs vs 2.5µs) | ✅ 실측 |
| 예측/실측 일치 (영향받는 2개) | ✅ 오차 4% 이내 |
| **적용 전/후 개선폭** | ✅ **배포 SQL로 측정** — 17.4배 / 6.7배 / 중립 |
| 개선안 결과 동등성 | ✅ 값이 다른 데이터로 차집합 0행 + `ArticleChunkCrossScoringTest` 실 DB 통과 |
| MATERIALIZED의 HNSW 파괴 | ✅ 플랜 + 타이밍으로 확정. 수정 후 본검색이 HNSW Index Scan 유지함도 확인 |
| **어떤 쿼리가 generic으로 전환되는가** | ⚠️ **벤치 통계 기준**. 프로덕션에선 다를 수 있다 |
| **프로덕션 재현** | ❌ **미실행** — 아래 참고 |
| 퍼널 컷 20의 랭킹 손실 | ❌ 기존 부채 그대로, 이 문서 범위 밖 |

### 한계

- **warm cache 측정이다.** 프로덕션의 물리 TOAST I/O는 여기에 더해지며, 그 부분은
  퍼널이 실제로 줄여준다. 두 이득은 배타적이지 않다.
- **대용량 벤치의 halfvec 값이 모든 행에서 동일하다** (비상관 서브쿼리가 InitPlan으로 1회만
  평가된 결과). 텍스트 파싱·고정폭 거리연산 비용은 값과 무관해 **타이밍 결론에는 영향이
  없지만**, Stage 1 선별의 현실성은 그 데이터로 담보되지 않는다. 동등성 검증만 값이
  다른 데이터로 별도 수행했다.
- **generic plan 전환 여부는 비용 추정에 달려 있어 환경 의존적이다.** 이 문서의
  "본검색은 해당 없음"은 벤치 통계 기준이며, 프로덕션에서는 반대일 수 있다.
  아래 EXPLAIN 한 줄로 쿼리별로 확인할 수 있다.
- **커넥션 점유 +38%의 진짜 출처는 여전히 미확정이다.** 이 문서는 "퍼널 쿼리가 원인이
  아니다"까지만 증명한다. `pg_stat_statements`의 쿼리별 `mean_exec_time`을 W/Z로 대조하면
  DB 안인지 밖인지 갈린다.

### 다음

- [x] 5.1~5.3 코드 적용 완료 (2026-08-12) — 12개 쿼리 전부, 실 DB 테스트 통과
- [ ] 부하테스트 재측정 (cross-scoring이 검색당 몇 번 도는지에 따라 체감이 갈린다)
- [ ] 프로덕션에서 쿼리별 generic 전환 여부 확인 (아래 EXPLAIN)
- [ ] 프로덕션 `pg_stat_statements`에서 `mean_exec_time` 전후 대조
- [ ] `findRelatedArticlesByRepresentativeChunk` 실호출 빈도 확인 (fallback 경로)

---

## 부록. 재현

### 합성 데이터 생성

```sql
CREATE SCHEMA bench; SET search_path = bench, public;

CREATE TABLE article (id bigint PRIMARY KEY, deleted_at timestamp);
INSERT INTO article SELECT g, NULL FROM generate_series(1, 18000) g;

CREATE TABLE clova_article_chunk (
    id bigint PRIMARY KEY, article_id bigint NOT NULL, chunk_index int,
    is_representative boolean DEFAULT false, embedding_generated_at timestamp,
    embedding_binary bit(1024)
);
INSERT INTO clova_article_chunk (id, article_id, chunk_index, embedding_binary)
SELECT row_number() OVER (), a.id, c.i,
       (SELECT string_agg((CASE WHEN random() < 0.5 THEN '0' ELSE '1' END), '')
        FROM generate_series(1,1024))::bit(1024)
FROM article a CROSS JOIN LATERAL generate_series(1, (4 + (a.id % 10))) AS c(i);
CREATE INDEX idx_clova_chunk_article_id ON clova_article_chunk (article_id);

CREATE TABLE clova_chunk_vectors (id bigint PRIMARY KEY, embedding_normalized halfvec(1024));
INSERT INTO clova_chunk_vectors (id, embedding_normalized)
SELECT c.id, l2_normalize((SELECT ('[' || string_agg((random()*2-1)::text, ',') || ']')
                           FROM generate_series(1,1024))::halfvec)
FROM clova_article_chunk c;

ANALYZE article; ANALYZE clova_article_chunk; ANALYZE clova_chunk_vectors;
```

> 위 INSERT의 벡터 생성 서브쿼리는 **비상관**이라 InitPlan으로 1회만 평가된다 —
> 모든 행이 같은 값을 갖는다. 타이밍에는 영향 없으나(7장 한계 참고) 값에 의존하는
> 실험을 하려면 상관 서브쿼리로 바꿔야 한다.

### 계단 관측

```sql
PREPARE p(varchar, varchar, varchar, int, float8, int) AS <퍼널 쿼리>;
SET plan_cache_mode = auto;   -- 운영 기본값
\timing on
-- 같은 EXECUTE를 10회. 6회차에서 뛴다.
```

`force_generic_plan` / `force_custom_plan`으로 고정하면 양쪽을 직접 대조할 수 있다.

### 행당 비용 분해

```sql
-- A: 행마다 파싱 (현재 코드 형태)
PREPARE a(varchar) AS SELECT sum(t.v <#> l2_normalize(CAST($1 AS halfvec))) FROM t;
-- B: 파싱 1회
PREPARE b(varchar) AS WITH q AS MATERIALIZED (SELECT l2_normalize(CAST($1 AS halfvec)) AS vec)
                      SELECT sum(t.v <#> q.vec) FROM t, q;
SET plan_cache_mode = force_generic_plan;
-- (A - B) / 행수 = 행당 파싱 비용
```

`l2_normalize`를 뺀 변형, `length(CAST($1 AS varchar))`만 하는 변형을 추가하면
파싱 / 정규화 / 문자열 복사로 더 쪼갤 수 있다.

### 운영에서 확인하는 법

```sql
-- 이 쿼리가 generic plan에 있는지: 실행계획에 $n이 남아 있으면 generic
EXPLAIN (VERBOSE) EXECUTE <stmt>(...);
-- 타깃리스트에 l2_normalize(($1)::halfvec)가 보이면 행별 재파싱 중이다.
```
