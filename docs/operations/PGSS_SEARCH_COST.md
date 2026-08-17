# 검색 DB 비용 — 발견과 실행 계획

**2026-08-09 작성.** 검색 RPS 개선 조사 결과와 후속 작업 정리.
측정 환경: DB 2 vCPU / 1GB (PG 17.8 + pg_search + pgvector), 앱 2 vCPU (blue/green + nginx).

> ## 📌 현재 상태 (2026-08-17) — 이 문서의 수치 중 낡은 것
>
> 아래 본문은 **2026-08-09 시점 측정**이다. 그 뒤 실험으로 갱신된 항목:
>
> | 본문 서술 | 현재 |
> |---|---|
> | 검색 천장 **2.5 rps** | **11.35 rps** (2026-08-17, level_5) |
> | 예산의 10% = **워밍 쿼리**(`warmChunkCacheAsync`) | **제거됨** (`b020104`) — 아무도 읽지 않는 캐시였고, 요청당 블록의 대부분이 이것이었다(22만 → 1.7만 blks/req) |
> | `embedding_normalized`에 **HNSW 인덱스 없음** | **틀렸다 — 존재한다** (3장 정정 블록 참고) |
> | 병목 = DB 유저스페이스 CPU | 한동안 **커넥션 유휴 점유(OSIV)** 였고, 그걸 걷어낸 뒤 다시 DB로 돌아왔다 |
> | A + B'로 이론 RPS 2.94 → 4.0~4.3 | 그 예측의 전제(요청당 비용)가 바뀌었으므로 **재계산 필요** |
>
> **여전히 유효한 것**: cross-scoring의 TOAST 간접 참조가 비싸다는 진단, 청크당 12.2블록,
> "꺼내는 벡터 개수를 줄여야 한다"는 결론. 2026-08-17 측정에서 요청당 논리 블록이 여전히
> **16,903블록(132MB)** 이고 이것이 현재 1순위 병목으로 지목됐다.
>
> 최신 정산: [`load-test/results/2026-08-17-osiv-connection-hold-ab.md`](../../load-test/results/2026-08-17-osiv-connection-hold-ab.md) (특히 5장)

---

## 요약

- **병목은 DB의 유저스페이스 CPU다.** 앱 호스트도, 디스크도, 커넥션 풀도 아니다.
- **검색 2.5 rps는 이상 현상이 아니라 효율 천장이다.** 요청당 DB CPU를 줄이지 않으면 안 움직인다.
- **예산의 80%가 벡터 계열이고, 그중 43%가 "보조" 작업**(cross-scoring 33% + 워밍 10%)이다.
- **cross-scoring 한 곳에서 예산의 33%가 나가는데, 그 비용의 대부분이 TOAST 간접 참조다.**
  블록 접근의 64%가 TOAST 인덱스 조회다.
- **A + B'로 예산의 약 25~30%를 뺄 수 있다.** 이론 RPS 2.94 → **약 4.0~4.3**.

> ⚠️ **2026-08-12 추가 — 이 문서보다 큰 항목이 따로 있다.**
> 벡터 쿼리들이 파라미터 텍스트를 **행마다 재파싱**하고 있다. 블록 카운터에 안 잡혀
> 이 조사에서 통째로 누락됐다. cross-scoring 레거시 **91.2 → 5.3ms**, 퍼널 **24.0 → 3.6ms**
> (본검색은 generic plan 미전환이라 중립).
> 랭킹 리스크 0, 수정은 키워드 한 개. **A·B'보다 먼저다.**
> → **`QUERY_PARAM_REPARSE.md`**

---

## 1. 병목 판정

`2026-08-08-search-checkout-workers-2x2-cachemiss.md` 부하테스트 구간(UTC 12:43~14:19)의
서버측 지표 재분석.

| 자원 | 측정 |
|---|---|
| **DB 호스트 CPU** | **68~96%** |
| 앱 호스트 CPU | 5~25% |
| DB CPU 모드 분해 | user **1.1~1.7코어** / system 0.05~0.16 / **iowait 0.05~0.31** |

user 모드가 압도한다 → **연산 병목**이지 디스크·메모리 압박이 아니다.
(1GB RAM인데도 shared_buffers 256MB가 워킹셋을 잡고 있다는 뜻)

### 요청당 DB CPU

`sum(rate(node_cpu_seconds_total{job="db-node",mode!="idle"}[3m])) / rate(검색 RPS)`

| 조합 | core-s/req |
|---|---|
| `workers=0` | **0.62~0.79** |
| `workers=2` | 1.22~1.48 (**2.1배**) |

**천장 검증**: 2코어 ÷ 0.68 = **2.94 rps**. 실측 피크 2.48 = 상한의 84%.

> **부수 효과 — 이 지표가 RPS보다 훨씬 예민하다.** checkout 2회 vs 3회가 0.68 vs 0.68,
> 1.42 vs 1.40으로 동일하게 나온다. 기존 실험이 ±20% RPS 노이즈로 못 가르던 것을 갈라준다.
> A/B 판정 지표를 RPS에서 core-s/req로 바꿀 것.

---

## 2. 검색 1건당 DB 예산

벡터 검색이 실제로 실행되는 요청 기준 (`pg_stat_statements` 실측).

| 단계 | ms | 비중 |
|---|---|---|
| 벡터 2단계 본검색 | 336 | **37%** |
| **cross-scoring 보충** | **295** | **33%** |
| BM25 | 180 | 20% |
| 워밍 HNSW (`warmChunkCacheAsync`) | 93 | 10% |
| term N+1 (검색당 5.7 쿼리) | 3 | 0.3% |
| **합계** | **~904** | |

`node_cpu ÷ RPS = 680 core-s`와 같은 자릿수 (경과시간 vs CPU, 캐시 상태 차이).

**두 가지 함의:**

1. **벡터 계열이 80%** (336+295+93=724). BM25는 20%뿐이다.
   `max_parallel_workers_per_gather` 2×2 실험은 주로 BM25에 작용하는 변인이었으므로
   **예산의 20% 조각을 두고 벌인 실험**이었다. (workers=0 결론 자체는 유효)
2. **"보조" 작업이 43%.** 1차 검색이 아니라 그 뒤를 메우는 작업이 예산의 절반 가까이를 쓴다.

---

## 3. 확정된 사실

### 스키마 실측

| 항목 | 값 |
|---|---|
| 규모 | 18,401 아티클 / 154,698 청크 |
| 아티클당 청크 | avg **8.4**, p50 7, p95 20, max 137 |
| `clova_chunk_vectors` | heap **9MB** / TOAST **410MB** / 인덱스 4.6MB (⚠️ 아래 정정) |
| `embedding_normalized` 저장 | `attstorage = e` (EXTERNAL, **압축 없음**) |
| 대표 청크 | 18,401개 = 아티클당 정확히 1개, **커버리지 100%** |

- ~~**`embedding_normalized`에 HNSW 인덱스가 없다.** 인덱스 총합 4.6MB는 PK뿐이다
  (154k개 halfvec에 HNSW면 수백 MB).~~ stage-2 재랭킹과 cross-scoring은 인덱스 없는 경로다.

> ⚠️ **2026-08-17 정정 — "인덱스가 없다"는 틀렸다. 결론은 그대로 유효하다.**
>
> 두 HNSW 인덱스가 **실제로 존재한다** (2026-08-17 `pg_indexes` 확인):
>
> - `idx_clova_chunk_vectors_halfvec_hnsw` — `clova_chunk_vectors(embedding_normalized halfvec_ip_ops)`, `m=24, ef_construction=1000`
> - `idx_clova_chunk_embedding_binary_hnsw` — `clova_article_chunk(embedding_binary bit_hamming_ops)`, 동일 옵션
>
> 둘 다 **Flyway 마이그레이션이 아니라 `VectorIndexInitializer`가** `ApplicationReadyEvent`에서
> `CREATE INDEX CONCURRENTLY IF NOT EXISTS`로 보장한다 — 코드 관리이며 재구축 시 유실 위험은 없다.
> `db/migration/`을 grep해도 HNSW가 안 나오는 이유가 이것이다. 위 "인덱스 4.6MB"는 인덱스 크기에서
> 존재 여부를 추론한 것인데, 그 추론이 틀렸다(측정 시점에 아직 안 만들어져 있었는지는 소급 확인 불가).
>
> **다만 "stage-2 재랭킹과 cross-scoring은 인덱스 없는 경로"라는 판단은 그대로 맞다.** 이유가 다르다 —
> 인덱스가 없어서가 아니라 **쿼리 형태가 인덱스를 쓸 수 없어서**다. HNSW는
> `ORDER BY embedding <#> $1 LIMIT k`(전체 대상 top-k)를 서빙하는데, 이 경로들은
> `cac.article_id = ANY(CAST(:articleIds AS bigint[]))`로 후보를 고정한 **필터 스캔**이라
> 후보 전 청크의 halfvec을 TOAST에서 꺼내야 한다(`ArticleChunkRepository`).
> 따라서 아래 "꺼내는 벡터 개수를 줄여야 한다"는 결론과 청크당 12.2블록이라는 실측은 **손상되지 않는다.**
> → [`load-test/results/2026-08-17-osiv-connection-hold-ab.md`](../../load-test/results/2026-08-17-osiv-connection-hold-ab.md) 5.5~5.6
- **압축 해제 비용은 없다.** `attstorage='e'`이므로 "TOAST 압축을 끄자"는 카드는 존재하지 않는다.

### TOAST 간접 참조가 진짜 비용 (`clova_chunk_vectors` 누적)

| | hit | read | 비중 |
|---|---|---|---|
| heap | 21.1M | 25K | 19.5% |
| toast | 18.1M | **5.0M** | 16.7% |
| **tidx (TOAST 인덱스)** | **69.2M** | 11.9K | **63.8%** |

**블록 접근의 64%가 TOAST 인덱스 조회다.** 벡터 하나 꺼낼 때마다 인덱스를 타고 내려가는 비용이
실제 데이터 읽기보다 크다. `toast_blks_read` 500만은 물리 I/O — 410MB TOAST가
shared_buffers 256MB에 안 들어간다.

실측: cross-scoring 1회 = 840청크(1.7MB)를 위해 **10,272블록(84MB)** 접근. **청크당 12.2블록.**

→ **최적화는 FLOPs가 아니라 "몇 개의 벡터를 꺼내는가"를 줄여야 한다.**
~~쿼리 정리(CTE 호이스팅, LATERAL)는 정렬·파싱만 줄이고 벡터 개수는 그대로라 효과가 제한적이다.~~

> ⚠️ **2026-08-12 정정 — 이 문장이 틀렸다. CTE 호이스팅이 가장 큰 레버였다.**
> 여기서 "파싱"을 부수적 비용으로 본 전제가 깨진다. 쿼리 파라미터로 받은 11.8KB 텍스트를
> halfvec으로 파싱하는 비용이 **행마다** 반복되고 있으며(행당 97µs vs 실제 거리 연산 2.5µs),
> 영향받는 쿼리에선 그게 실행시간의 거의 전부다. cross-scoring 레거시 단일쿼리가 이 한 가지로
> **91.2ms**를 쓰고, `query_vec AS MATERIALIZED` 한 단어로 **5.3ms**가 된다.
> 블록 카운터에는 전혀 안 잡히는 비용이라 이 절의 TOAST 관찰(그 자체는 정확하다)에
> 가려져 있었다. → **`QUERY_PARAM_REPARSE.md`**

### 그 밖

- **`warmChunkCacheAsync`는 벡터 검색 실행 시 거의 1:1로 함께 돈다** (본검색 12회 : 워밍 11회).
  본검색 336ms 대비 93ms이므로 "2배"가 아니라 **+28%**.
- **N+1은 확정이나 CPU 문제는 아니다.** `expandSearchTerms` 3중 루프로 검색당 5.7개 추가 쿼리가
  나가지만 총 3ms(예산의 0.3%). **커넥션 점유·지연 문제**로 다뤄야 한다.
- **`IN (:ids)`가 통계 정규화를 깨뜨린다.** cross-scoring 13회 호출이 **12개 queryid로 분산**됐다.
  Hibernate가 `IN ($1..$n)`으로 펼치는데 n마다 다른 쿼리가 되기 때문. PG16+ IN 병합은 상수
  리스트에만 적용된다. → 상위 N 뷰에서 과소집계 + Grafana 카디널리티 폭발 + 플랜 캐시 비효율.
- **관련 글 추천이 예산 밖에서 11%를 쓴다** (`findRelatedArticlesByTwoStageSearch`, 3,514ms).
  검색 최적화 논의에 한 번도 등장한 적 없는 경로다.
  → **2026-08-12**: 이 쿼리는 CTE조차 없이 `CAST(:queryEmbedding AS halfvec)`를 **행마다 2회**
  평가한다. 11%의 상당 부분이 재파싱일 가능성이 높다 (`QUERY_PARAM_REPARSE.md` 4장).
- **postgres_exporter 자신이 유휴 DB 시간의 26%를 쓴다.** `pg_stat_user_tables` 스크랩이
  호출당 ~5,600블록(45MB)을 만진다. 15초마다 이러면 검색 워킹셋을 버퍼에서 밀어낼 수 있다.
- **검색 프리웜이 사실상 상시 실패 중이다.** 시간당 12회 중 9~11회가
  `Could not initialize proxy [Category#14] - no session`. cold start 방지가 작동하지 않는다.
  **유일하게 지금 실사용자에게 영향을 주는 항목.**

---

## 4. 해야 할 일

### A. stage-1 후보 재활용  ✅ 적용 (2026-08-09) — "무료·품질 리스크 없음"은 틀렸다

#### 왜 해야 하는가

**이미 계산해놓은 값을 버리고, 같은 값을 다시 계산하려고 84MB를 읽고 있다.**

본검색 `findArticlesByTwoStageSearch`는 이렇게 동작한다:

```
1. HNSW로 청크 후보 200개 추출        (embedding_binary, 인라인 128B — 쌈)
2. 200개 전부에 halfvec 정확 거리 계산
3. 아티클별 상위 3청크 평균 집계
4. threshold 미만 제거 → LIMIT 적용 → 반환
```

**3번 시점에 후보에 등장한 모든 아티클의 점수가 이미 계산돼 있다.** 4번에서 threshold와 LIMIT으로
잘려나간 아티클들의 점수는 그냥 버려진다. 그리고 잠시 뒤 cross-scoring이 **바로 그 아티클들 중
일부의 점수를 다시 계산하려고** `computeSimilarityForArticleIds`로 84MB를 읽는다.

#### 품질 측면에서도 현재가 잘못돼 있다

지금은 같은 `vectorResults` 맵 안에 **서로 다른 방식으로 계산된 두 집단**이 섞인다:

| 출처 | 계산 방식 |
|---|---|
| 본검색 결과 | **HNSW 후보 200개 안에서** 상위 3청크 평균 |
| cross-scoring 보충 | **그 아티클의 전체 청크 중** 상위 3청크 평균 |

보충된 쪽이 더 "정확"하다 — 후보 200개에 안 들어간 청크까지 본다. 그런데 이 둘이 같은
min-max 정규화를 통과해 NSF에 들어간다. **추정량이 다른 두 집단을 한 척도로 정규화하는 것은
계통 편향**이다.

> ### ⚠️ 정정 (2026-08-09, 구현 중 발견) — 편향의 방향이 위와 반대일 수 있다
>
> 위 표는 "후보 안에서 낸 점수 ≤ 전체에서 낸 점수"를 암묵적으로 가정하지만, **집계가 `AVG`라
> 분모가 고정이 아니다.** 아티클이 후보 200청크에 걸치는 청크 수가 `topK`(3) 미만이면
> 상위 1~2개만 평균 내므로 **전체 청크 기준 상위 3평균보다 높게** 나온다.
>
> | 아티클 | 후보에 걸린 청크 | 후보 점수 | cross-scoring 점수 |
> |---|---|---|---|
> | 청크 3개 중 1개만 후보 | 1 | **0.80** | 0.70 |
> | 청크 5개 중 1개만 후보 | 1 | **0.55** | 0.51 |
> | 청크 3개 전부 후보 | 3 | 0.43 | 0.43 |
>
> 그리고 **재활용 대상 집단에서는 이 경우가 지배적이다** — 재활용 대상은 정의상 threshold/limit로
> 잘린 약한 매칭이고, 그런 아티클이 후보 200청크 중 3개 이상을 차지하는 일은 드물다
> (아티클당 청크 p50 7 기준).
>
> 즉 그대로 재활용하면 **약한 매칭이 부풀어 올라 없던 벡터 점수가 생긴다.** 반대로 진짜 최고
> 청크가 binary 후보 컷에서 탈락한 아티클은 낮은 후보 점수로 평가돼, 원래는 threshold를 넘겼을
> 점수를 잃는다. 편향이 사라지는 게 아니라 **방향이 바뀌고 적용 대상이 넓어진다.**
>
> → 아래 "적용 내용"의 두 가지 안전장치(후보 청크 topK개 이상 조건 + 임계값 미만은 DB로)는
> 이 정정에서 나왔다.

#### 무엇을 바꾸나

- `findArticlesByTwoStageSearch`가 threshold/LIMIT 적용 **전**의 아티클별 점수 전체를 함께 반환
- `VectorSearchResult`에 `candidateScores` 필드 추가 (기존 `scores`는 그대로 — 본검색 의미 불변)
- `computeHybridCore`의 cross-scoring 블록에서, `needVectorIds` 중 `candidateScores`에 있는 것은
  **DB 왕복 없이** 채운다. 나머지만 기존 경로로 보충
- 보충 시 threshold는 기존과 동일하게 적용 (`vectorThresholdFor`)

#### 기대 효과

커버되는 만큼 **완전 무료**. 커버리지는 미측정이다 — 후보 200청크가 몇 개 아티클에 걸치는지,
그중 `needVectorIds`와 얼마나 겹치는지 먼저 재야 한다.

#### 검증

- 커버리지 로그 추가 (`needVectorIds` 중 후보에서 충족된 비율)
- `pg_stat_statements`에서 `computeSimilarityForArticleIds` 호출 수 감소 확인
- 랭킹 변화는 **의도된 개선**이므로 회귀가 아니다. 다만 변화량은 기록할 것

#### 적용 내용 (2026-08-09)

**쿼리** — 5개 2단계 검색 쿼리(무필터/국내/카테고리/복합/기업)가 후보 집계를 `agg` CTE로 빼고
threshold·limit를 컬럼으로 표현한다. 행 수만 늘 뿐 HNSW 순회·halfvec 계산량은 그대로다.

```sql
AVG(similarity) FILTER (WHERE similarity >= :threshold) AS avg_similarity  -- 기존 값 (미통과 시 NULL)
AVG(similarity)                                        AS candidate_similarity
(avg_similarity IS NOT NULL
 AND ROW_NUMBER() OVER (ORDER BY avg_similarity DESC NULLS LAST) <= :limit) AS is_main
```

`is_main`으로 기존 `scores`의 의미(threshold 통과 + limit 이내 + 정렬)를 **DB에서 그대로** 유지했다
— limit를 앱으로 올리지 않았으므로 본검색 결과 집합·값은 불변이다.

**안전장치 1 — 후보 청크가 topK개 이상인 아티클만 재활용한다.**

```sql
CASE WHEN COUNT(*) >= :topK THEN AVG(similarity) END AS candidate_similarity
```

이 조건이 있으면 후보는 전체 청크의 부분집합이고 평균 깊이가 같으므로
**`candidate_similarity` ≤ cross-scoring 값**이 항상 성립한다(과대평가 불가).
조건이 없으면 위 정정의 분모 축소가 그대로 들어온다.

**안전장치 2 — 임계값 미만인 후보는 탈락시키지 않고 DB로 넘긴다.**
후보 점수는 과소평가일 수 있어 DB에서는 임계값을 넘길 수 있다. 반대로 임계값 이상이면 DB 값은
그보다 크거나 같으므로 통과 여부가 바뀌지 않는다 — 점수만 보수적으로 잡히고, 그 방향은
본검색 결과와 같아 NSF 정규화에서 일관적이다. **점수를 잃는 아티클이 없다.**

**앱** — `VectorSearchResult.getCandidateScores()` 추가(기존 생성자는 빈 맵으로 위임).
`computeHybridCore`는 cross-scoring 직전에 `needVectorIds`에서 위 두 조건을 만족하는 id만 빼고
`vectorResults`에 직접 채운다. 나머지는 종전대로 DB 보충.

**스위치** — `search.hybrid.reuse-stage1-candidates`(기본 true, 환경변수
`SEARCH_HYBRID_REUSE_STAGE1_CANDIDATES`). 같은 빌드에서 core-s/req A/B를 재기 위한 것.
compose는 **나열된 변수만** 컨테이너에 주입하므로 `docker-compose.yml`의 `&backend-env`에도
배선했다 — 빠뜨리면 호스트 `.env`에 넣어도 앱에 도달하지 않는다.

**커버리지 로그** — `[검색]` 로그 끝에 `후보재활용: N/M건 (DB보충 K건)` 추가.
안전장치 2 덕분에 N은 "DB 왕복을 줄인 수"이자 "점수를 채운 수"로 일치한다.
운영 로그에서 이 비율을 먼저 읽고, 그 다음 `pg_stat_statements`의
`computeSimilarityForArticleIds` 호출 수 감소로 교차 확인한다. **커버리지는 여전히 미측정** —
B'의 Stage 1 대상 크기 추정도 이 값에 달려 있다.

**적용 범위** — RAG 경로(`getTopArticleIdsForRag`)는 **그대로 뒀다.** `searchForRag`도 같은 쿼리를
쓰므로 `candidateScores`는 채워지지만 소비하지 않는다. RAG는 threshold가 0.6으로 다르고 답변
품질 판정 기준이 따로 없어, 검색 경로의 커버리지·랭킹 변화를 먼저 본 뒤 별건으로 처리한다.

**테스트** — `ArticleChunkTwoStageSearchTest`가 **실제 DB에서 쿼리 5종을 전부 실행**한다.
질의 벡터를 e1으로 두어 유사도를 청크 벡터의 첫 성분으로, 질의 binary를 전부 1로 두어 해밍 거리를
0비트 수로 직접 지정하는 방식이라 후보 진입 순서까지 통제된다. 검증 항목: 후보 청크 topK 미만 →
`candidate_similarity` NULL, `avg_similarity`의 청크 단위 threshold, limit → `is_main` 표시.
`CASE WHEN COUNT(*) >= :topK`를 제거하면 이 테스트가 실패하는 것까지 확인했다.

> 이 테스트가 **실제 버그를 하나 잡았다.** 결과 파싱의 삼항 연산자
> `cond ? ((Number) row[2]).doubleValue() : similarity`에서 한쪽이 primitive `double`이라
> 반대쪽 `Double`이 언박싱된다 — 두 컬럼이 모두 NULL인 행(threshold 미달 + 후보 청크 topK 미만,
> **운영에서 흔한 조합**)에서 NPE가 나고, 상위 catch가 이를 삼켜 **벡터 검색이 통째로 빈 결과**가
> 된다(검색이 BM25 단독으로 조용히 퇴화). 스텁 기반 테스트만 있었다면 배포 후에야 드러났을 것이다.

**미검증** — 운영 계측(커버리지, core-s/req, 랭킹 변화량)은 배포 후에 해야 한다.

> **부하테스트로는 이 최적화를 측정할 수 없다.** mock Clova(`load-test/mock`)는 텍스트 시드
> 의사난수 벡터를 반환하므로 Stage 1 후보 아티클 집합이 BM25 결과와 **무상관**이다. 재활용은
> `needVectorIds ∩ candidateScores`에서만 일어나는데 무상관이면 기대 교집합이 1건 미만이다.
> 운영은 BM25/Vector가 강하게 상관되므로 커버리지가 훨씬 크다 — **부하테스트 A/B가 "차이 없음"으로
> 나오는 것은 예상된 결과이고, 그것을 운영 효과로 해석하면 안 된다.** 판정은 운영 로그의
> 커버리지 + `pg_stat_statements` 호출 수로만 한다.

> `IN (:ids)` 파편화(그 외 3번 항목)가 이 검증을 방해한다 — 재활용이 IN 리스트 길이를 요청마다
> 다르게 줄이므로 queryid가 더 흩어진다. `= ANY(:ids)` 전환을 먼저 하면 판정이 쉬워진다.

---

### B'. cross-scoring 2단계화 — 예산 33% → 약 3%

#### 왜 해야 하는가

**단일 쿼리로 시스템 최대 비용을 쓴다.** 호출당 295ms로 BM25(180ms)의 1.6배,
본검색(336ms)에 육박한다. 그런데 이건 1차 검색이 아니라 **점수 빈칸을 메우는 보조 작업**이다.

비싼 이유는 알고리즘이 나빠서다 — `ArticleChunkRepository:96`:

```sql
FROM clova_article_chunk cac JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
WHERE cac.article_id IN (:articleIds)      -- 100개 아티클
```

**인덱스 없이 100개 아티클의 모든 청크(평균 840개)를 꺼내** 정확 거리를 계산하고 윈도우 정렬한다.
그리고 위에서 봤듯 청크 하나를 꺼내는 데 TOAST 간접 참조로 12블록이 든다.

#### 삭제는 불가

NSF는 이렇게 계산된다:

```java
nsf = (w_b × normBM25 + w_v × normVec) / (w_b + w_v)   // 분모는 항상 전체 가중치
```

한쪽에만 있는 아티클은 **0.5가 상한**이다. BM25∩Vector 교집합 중앙값이 8건인데 합집합은
127~173건 — **결과 대부분이 단일 소스**다. 즉 보충 점수가 사실상 순위를 만든다. 지우면 품질이 붕괴한다.

#### 벡터 개수를 줄이면 듣는다 — 실측

대표 청크만 쓰도록 바꿔 재봤다 (`is_representative` 한 줄 차이, 코드 변경 없이 EXPLAIN 비교):

| | 블록 | 시간 |
|---|---|---|
| 현재 (840청크) | 10,272 | **93.3ms** |
| 대표 청크만 (100청크) | 1,751 | **3.05ms** |
| | 5.9× | **30.6×** |

시간 절감이 블록 절감보다 큰 이유는 바이트뿐 아니라 **윈도우 함수와 840행 정렬이 통째로 사라지고**
Nested Loop 인덱스 조회로 바뀌기 때문이다. **"꺼내는 벡터 개수"가 지배 변수라는 증거.**

#### 그러나 대표 청크를 Stage 1으로 쓰면 안 된다

`RepresentativeChunkService:51`의 선정 로직:

```
1. 아티클의 상위 10개 term 조회 (article_term, score 순)
2. term마다 그 아티클의 ParadeDB BM25 점수 조회
3. 청크 점수 = Σ (청크 내 term 출현 횟수 × 그 term의 BM25 점수)
4. 최고점 청크를 대표로 지정
   fallback: BM25 불가 → term 빈도수 / term 없음 → 첫 번째 청크
```

즉 **"이 아티클 자신의 대표 키워드를 가장 잘 담은 청크"** 다. Stage 1에는 세 가지로 어긋난다:

1. **선정은 어휘 기준(BM25 term 중첩), 사용은 의미 기준(벡터).** 두 공간이 정렬돼 있다는 보장이 없다.
2. **질의 독립적이다.** Stage 1이 답해야 할 질문은 "이 아티클에 **이 질의와** 맞는 청크가 있는가"인데
   대표 청크는 질의와 무관하게 고정돼 있다.
3. **퍼널의 Stage 1은 recall을 보존해야 하는데 과소평가 편향이 있다.** 최고 매칭 청크가 대표 청크가
   아니면 Stage 2에 가보지도 못하고 탈락한다. p95 20청크·최대 137청크에서 흔한 경우다.

#### Stage 1은 `embedding_binary`로 — TOAST를 아예 우회한다

| | 크기 | 저장 | 청크당 블록 |
|---|---|---|---|
| `embedding_normalized` halfvec(1024) | 2,048B | **TOAST** | ~12 |
| `embedding_binary` bit(1024) | **128B** | **인라인** (임계값 2KB 아래) | ≪1 |

**`embedding_binary`는 `clova_article_chunk`에 인라인 저장되므로 TOAST 간접 참조가 통째로
사라진다.** 840청크 × 128B = 107KB. 대표 청크 100개를 TOAST에서 꺼내는 것보다도 싸다.

그리고 **840청크를 전부 보므로 질의별 max-over-chunks 의미가 보존된다.** 대표 청크처럼 미리
하나로 줄이지 않는다 — 위 3가지 문제가 모두 사라진다.

> **앞선 "선택지 E(binary 벡터) = 높은 리스크" 판단을 정정한다.** 그 근거는 "binary 점수와
> halfvec 점수가 같은 min-max 정규화에 섞이면 계통 편향"이었는데, **퍼널에서는 Stage 1 점수를
> 버린다.** `vectorResults`에 들어가는 건 Stage 2의 정확한 halfvec 점수뿐이라 섞일 일이 없다.
> 단일 단계를 전제한 판단이었고 2단계에서는 성립하지 않는다.

#### 설계

```
Stage 1: 대상 아티클의 전체 청크를 Hamming 거리로 스코어링   (인라인 128B)
         → 아티클별 상위3 평균 → 컷 통과분만 선별
Stage 2: 선별된 N개 아티클만 기존 halfvec 정확 계산          (N × 8.4 청크)
```

**본검색의 binary → halfvec 퍼널과 정확히 같은 구조**다. 코드베이스 일관성도 맞는다.

A를 먼저 적용하면 `needVectorIds`가 줄어 Stage 1 대상도 작아진다. **A → B' 순서가 맞다.**

#### 보정 — Stage 1은 랭커가 아니라 recall 필터다

지금도 보충 점수엔 `similarity >= 0.52` 컷이 있다. 따라서 Stage 1의 역할은
**"Stage 2에서 0.52를 넘길 아티클을 하나도 떨어뜨리지 않는 것"** 이다.

단위 벡터의 이진 양자화에서 해밍 거리 `h`(d=1024)와 각도는 `cos θ ≈ cos(π·h/d)` 관계다.
0.52에 대응하는 해밍 컷을 구한 뒤 **여유를 둬 느슨하게** 잡는다.

**판정 기준: exact 결과 대비 Stage 1 recall ≥ 99%가 되는 가장 타이트한 컷.**
`VectorSearchAccuracyService`의 exact search 정답셋 방식을 그대로 재사용할 수 있다.

#### 기대 효과 — Stage 2 컷이 결정한다

Stage 1은 인라인이라 사실상 공짜지만 **Stage 2는 여전히 실제 halfvec을 TOAST에서 꺼낸다.**
따라서 이득은 컷 크기에 비례한다. (현재 10,272블록 / 93.3ms 기준 추정)

| Stage 2 컷 | Stage 2 청크 | 총 블록(추정) | 현재 대비 | 운영 295ms → | 예산 33% → |
|---|---|---|---|---|---|
| 20개 | 168 | ~2,200 | 21% | ~64ms | ~7% |
| 10개 | 84 | ~1,200 | 12% | ~35ms | ~4% |

컷 20 기준으로 검색 1건당 904ms → 약 673ms → **이론 RPS 2.94 → 약 4.0 (+35%)**.
A의 무료 커버리지가 얹히면 조금 더 내려간다.

> **앞서 적었던 "295ms → 약 10ms, RPS 4.3"은 과대 추정이었다.** 그 30.6×는 대표 청크만 쓰는
> **단일 단계** 측정치인데, 2단계에서는 Stage 2가 실제 벡터를 계속 읽으므로 그대로 적용되지 않는다.

컷 크기는 임의로 정하지 않는다 — 아래 품질 측정에서 **NDCG@10 손실 대비 core-s/req 절감의
파레토 곡선을 그려 무릎점**을 택한다.

#### 품질 측정이 선행돼야 한다

A와 달리 B'는 랭킹을 바꾼다. `VectorSearchAccuracyService`(exact search 정답셋 →
Recall@K / NDCG@K)와 `AdminSearchTestController` 엔드포인트가 이미 있으나,
**이건 벡터 검색만 잰다.** cross-scoring 변경을 판정하려면 **하이브리드 NSF 최종 랭킹까지
확장**해야 한다. 이 확장 자체가 B' 착수의 전제 조건이다.

판정 기준: Stage 2 컷(20개)을 바꿔가며 NDCG@10 손실 대비 core-s/req 절감의 파레토 곡선을 그리고
무릎점을 택한다.

#### 기대치 정정 — "33% → 약 3%"는 과대 표기다

절 제목의 3%는 위 표의 **컷 10** 케이스이고, 그 표에는 **Stage 1 자신의 비용이 빠져 있다.**
Stage 1은 `idx_clova_chunk_article_id`로 100개 아티클의 청크를 훑어 인라인 128B를 읽는다 —
청크당 ≪1블록이지만 인덱스 + heap 페이지로 **300~600블록** 정도는 든다. 이를 포함한 정직한 기대치:

| Stage 2 컷 | Stage 2 청크 | Stage 1 + Stage 2 블록 | 현재 대비 | 예산 33% → |
|---|---|---|---|---|
| 20개 | 168 | ~2,400~2,700 | 23~26% | **약 8~9%** |
| 10개 | 84 | ~1,500~1,800 | 15~18% | **약 5%** |

컷 20 기준 검색 1건당 904ms → 약 685ms → **이론 RPS 2.94 → 약 3.9**.

#### 적용 내용 (2026-08-12)

**선행 커밋 — `= ANY` 전환 + 보충통과 로그.** 알고리즘을 바꾸기 전에 계측부터 세웠다.

- `computeSimilarityForArticleIds`의 `IN (:articleIds)` → `= ANY(CAST(:articleIds AS bigint[]))`.
  파라미터는 앱에서 만든 PostgreSQL 배열 리터럴(`{1,2,3}`, `VectorSearchService.formatIdArray`).
  이걸 먼저 해야 B' 전/후를 `pg_stat_statements` **한 행**으로 비교할 수 있다(그 외 3번).
  겸사겸사 `l2_normalize` 2회 호출을 `query_vec` CTE 1회로 정리했다.
- `[검색]` 로그에 **`보충통과 P건`** 추가 — DB 보충 대상 N건 중 실제로 임계값을 통과한 수.
  **컷을 감이 아니라 이 분포로 정하기 위한 값이다.** 로그는 트랜잭션 밖에서 찍히므로
  카운트만 `AtomicInteger`로 꺼냈다(커넥션 점유 구간 불변).
- 실 DB에서 이 쿼리를 실행하는 `ArticleChunkCrossScoringTest` 추가. 예산 33%의 주인공인데
  기존 검색 테스트는 Repository를 전부 스텁해 **한 번도 실행된 적이 없었다.**

**퍼널 쿼리** — `computeSimilarityForArticleIdsTwoStage`. 같은 파일의
`findRelatedArticlesByTwoStageSearch`가 이미 쓰던 `stage1`(아티클 선별) →
`= ANY(ARRAY(SELECT article_id FROM stage1))` 구조를 그대로 따랐다.

```sql
stage1 AS MATERIALIZED (
    SELECT article_id FROM (
        SELECT cac.article_id,
               cos(pi() * (cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))) / 1024.0)
                   AS estimated_similarity,
               ROW_NUMBER() OVER (PARTITION BY cac.article_id
                                  ORDER BY cac.embedding_binary <~> CAST(:queryBinary AS bit(1024))) AS rn
        FROM clova_article_chunk cac JOIN article a ON cac.article_id = a.id
        WHERE a.deleted_at IS NULL AND cac.embedding_binary IS NOT NULL
          AND cac.article_id = ANY(CAST(:articleIds AS bigint[]))
    ) estimated
    WHERE rn <= :topK
    GROUP BY article_id
    HAVING AVG(estimated_similarity) >= :stage1Floor
    ORDER BY AVG(estimated_similarity) DESC
    LIMIT :stage2Limit
)
```

- **Stage 1은 아티클을 고르고 청크를 고르지 않는다.** Stage 2가 생존 아티클의 **전 청크**를
  그대로 다시 읽으므로 **반환 값은 단일 쿼리와 완전히 같다** — 달라지는 건 "누가 살아남았나"뿐.
  대표 청크로 미리 하나를 남기는 방식과 갈리는 지점이 여기다.
- **집계 모양을 Stage 2와 맞췄다** (최고 청크 하나가 아니라 상위 topK 평균). 컷은 Stage 2 값을
  예측해야 하므로.
- **해밍 → 코사인 환산을 청크 단위로 먼저 한다.** `cos(π·h/d)`가 비선형이라
  `cos(avg(h)) ≠ avg(cos(h))`. 선별 순서는 해밍 오름차순 = 추정 유사도 내림차순이라 등가.
- `deleted_at` 필터를 Stage 1로 옮겼다 — Stage 2는 생존분만 보므로 등가이고 halfvec 쪽
  `article` 조인이 사라져 더 싸다.

**컷과 하한 — 무엇이 실제 레버인가.** 부호 양자화에서 `h ~ Binomial(d, θ/π)`, d=1024에서
**σ_h ≈ 15**다.

| 참 유사도 | 해밍 h | 3σ 하한 추정치 |
|---|---|---|
| 0.60 | 302 | 0.49 |
| **0.52** | **334** | **0.40** |
| 0.45 | 360 | 0.32 |

→ 하한은 `threshold − 0.25` (기본 `stage1-floor-margin=0.25`). **단 하한은 레버가 아니다** —
무관한 문서쌍의 코사인도 0.3~0.4대에 있어 실제로 걸러지는 양이 적다. **비용을 줄이는 것도
품질 리스크를 만드는 것도 전부 `stage2-limit`(top-N)이다.** 하한은 병리적 케이스와
Stage 2 폭주에 대한 값싼 안전망이다.

**top-N 컷의 지배 오차는 노이즈가 아니라 청크 수 편향이다** (시뮬레이션, 참 유사도 0.52 고정):

| 아티클 청크 수 | 추정치 편향 | 추정치 σ |
|---|---|---|
| 3 | 0.000 | 0.023 |
| 7 (p50) | +0.032 | 0.017 |
| 20 (p95) | +0.056 | 0.014 |
| 137 (max) | +0.088 | 0.010 |

노이즈로 상위 topK를 고르는 데서 오는 상향 편향이 청크 수에 따라 0 ~ +0.09까지 벌어진다.
방향은 **"긴 아티클이 부풀어 살아남고 짧은 아티클이 눌린다"** — 짧은 아티클이 컷에서 밀릴 수
있다는 뜻이고, 그래서 **컷을 이론으로 정할 수 없다.** 선행 커밋의 `보충통과 P` 분포로 정한다.

완화 요인: 컷에서 잘리는 쪽은 벡터 점수 하위 집단이고 `minMaxNormalize`에서 최솟값은
`normVec = 0`이라 NSF 기여가 0이다. 게다가 본검색·보충 양쪽 모두 임계값 0.52에서 잘려 들어와
**min이 0.52 근처에 고정**돼 있어, 하위를 떼어내도 다른 아티클의 정규화가 크게 흔들리지 않는다.

**스위치** — `search.hybrid.cross-scoring-two-stage`(**기본 true**),
`...-stage2-limit`(20), `...-stage1-floor-margin`(0.25). 셋 다 `docker-compose.yml`의
`&backend-env`에 배선했다.

> **켜고 배포하기로 했다** — 컷 20은 위 표(예산 33% → 8~9%)에서 온 값이고, `보충통과` 로그로
> 사후에 조정한다. **롤백은 `SEARCH_HYBRID_CROSS_SCORING_TWO_STAGE=false` + 재배포**로
> 코드 변경 없이 된다.
>
> 대가 하나: 로깅과 퍼널이 **같은 릴리스에 들어가므로 `보충통과 P`의 "퍼널 off" 기준선이
> 남지 않는다.** 켠 뒤의 P만 관측되므로 before/after 비교는 불가능하고, 판정은
> "P가 붕괴하지 않는가"(절대값)와 `pg_stat_statements`의 B' 이전 비용 기준선 비교로만 한다.
> 기준선이 꼭 필요하면 첫 배포만 `.env`에 `=false`를 두고 하루 뒤 지우면 된다.

**적용 범위** — 검색 경로(`computeHybridCore`)만. `VectorSearchService`에 검색 전용 진입점
`computeSimilarityForSearchCrossScoring`을 새로 파고 그쪽에서만 스위치를 본다.
RAG(`getTopArticleIdsForRag`)와 따옴표 검색(relevance 정렬용 벡터 점수)은 종전 단일 쿼리를
그대로 탄다 — 따옴표 검색은 결과가 20건을 넘을 수 있어 컷이 정렬 자체를 망가뜨린다.

**테스트** — `ArticleChunkCrossScoringTest`가 실 DB에서 두 쿼리를 나란히 실행한다.
`ArticleChunkTwoStageSearchTest`의 픽스처 트릭(질의 벡터 e1 → 유사도 = 청크 벡터 첫 성분,
질의 binary all-1 → 해밍 = 0비트 수)을 재사용해 **유사도와 해밍을 독립적으로 지정**했다.
핵심 검증은 **동치성** — 컷·하한이 걸리지 않으면 두 쿼리의 키·값이 정확히 일치해야 한다.
그 밖에 "해밍이 먼 최고 청크가 Stage 2 값에 반영되는가"(퍼널이 청크를 미리 자르지 않는다는
증거), 컷 순서, 하한 배제, `deleted_at` 배제.

**미검증** — 운영 계측(EXPLAIN 블록 수, core-s/req, `pg_stat_statements`)은 배포 후에 해야 한다.
**랭킹 정확도는 측정하지 않고 켰다** — 아래 "남은 검증 부채"에 따로 적었다.

> **부하테스트로 이 최적화는 측정된다 — 항목 A와 갈리는 지점이다.** A는 mock Clova의 의사난수
> 벡터 때문에 `needVectorIds ∩ candidateScores`가 비어 재활용이 일어나지 않았다. B'의
> `needVectorIds`는 벡터 품질과 무관하게 BM25-only ~100건으로 잡히고,
> `search.loadtest.vector-threshold=0.0`이라 하한이 -0.25로 해제돼 **Stage 2가 정확히 컷 크기를
> 받는다** — 즉 최악 비용을 그대로 잰다. 단 mock 벡터는 무작위라 **비용만** 재고 품질은 못 잰다.

#### ⚠️ 남은 검증 부채 — 하이브리드 랭킹 정확도 (미측정 상태로 켰다)

**이 절은 B'가 갚지 않고 남긴 빚을 기록해두는 곳이다.** 위 §"품질 측정이 선행돼야 한다"가
착수 전제로 걸어둔 조건을 **충족하지 않은 채** 퍼널을 켰다. 나중에 이 절만 보고 이어받을 수
있도록 무엇이 비어 있는지, 무엇을 만들어야 하는지, 이미 확인한 함정이 무엇인지 적는다.

**무엇이 검증됐고 무엇이 안 됐나**

| | 상태 |
|---|---|
| 퍼널이 반환하는 **값**이 단일 쿼리와 같은가 | ✅ `ArticleChunkCrossScoringTest` 동치성 테스트로 고정 |
| Stage 2가 전 청크를 보는가(청크를 미리 안 자르는가) | ✅ 같은 테스트 |
| 비용이 실제로 줄어드는가 | ⏳ 배포 후 EXPLAIN / pgss / 부하테스트 (측정 방법 확립돼 있음) |
| **컷 20이 NSF 최종 랭킹을 얼마나 바꾸는가** | ❌ **미측정. 측정 수단 자체가 없다** |
| 컷 20이 적정값인가 | ❌ 문서 표에서 고른 값이고 `보충통과` 분포로 확인한 적 없다 |

**왜 못 쟀나** — `VectorSearchAccuracyService`(exact search 정답셋 → Recall@K / NDCG@K)와
`AdminSearchTestController`가 이미 있지만 **벡터 검색만 잰다.** cross-scoring은 정의상
BM25-only 아티클에만 작용하므로, 벡터 단독 지표로는 이 변경이 **원리적으로 관측되지 않는다.**

**만들면 되는 것 (설계는 이미 서 있다)** — `HybridSearchScorer.calculateNSFScores(bm25, vector,
wB, wV)`는 두 맵의 **순수 함수**이므로 하이브리드 랭킹 비교에 파이프라인 개조가 필요 없다:

1. `search_logs` 상위 키워드 → `search_query_embedding`에 **이미 캐시된 임베딩** 재사용
   (Clova API 호출 0회)
2. 그 키워드의 BM25 상위 100 id = 실제 cross-scoring 대상 근사
3. 같은 id 집합에 대해 `computeSimilarityForArticleIds`(정답) vs
   `computeSimilarityForArticleIdsTwoStage`(컷 스윕) 실행
4. **Stage 1 recall**(정답 중 임계값 통과분이 컷에서 몇 % 살아남나) + `calculateNSFScores`를
   두 벡터 맵으로 각각 호출해 **top-10 NDCG/겹침**
5. 컷을 10/20/30/50으로 스윕해 파레토 곡선 → 무릎점

**착수 전에 반드시 고칠 것 (조사 중 발견한 함정 2개)**

- `VectorSearchAccuracyService.resolveTestQueries`가 `FROM search_log`를 조회하는데 실제
  테이블명은 **`search_logs`**(`SearchLog.java`의 `@Table`). 항상 실패하고 catch가 **debug**
  레벨로만 삼킨 뒤 하드코딩 20개 키워드로 조용히 폴백한다 — 즉 지금 이 서비스는
  **실트래픽 쿼리를 한 번도 쓴 적이 없다.**
- 같은 서비스의 `Recall@K`는 "정답 상위 K개가 **결과 집합 전체**에 들어있나"라서 순위 손실을
  못 잡는다. 컷 판정에는 맞지만(recall 필터 판정) **랭킹 판정에는 NDCG만 유효하다.**

**그때까지의 대체 신호** — `[검색]` 로그의 `보충통과 P`. 이 값이 붕괴하면 컷이 너무 타이트하다는
직접 증거다. 다만 위 "스위치" 항목대로 **퍼널 off 기준선이 없어** 절대값으로만 읽어야 한다.

---

### D. 유의어 확장 N+1 → 조인 1쿼리 + 캐시  ✅ 적용 (2026-08-09)

#### 무엇이 문제였나

`expandSearchTerms`가 `findByTerm` → `getSynonymTermIds` → 유의어 id별 `findById`의 3중 루프였다.
검색당 5.7쿼리, DB 시간은 3ms(0.3%)로 **CPU 문제가 아니라 커넥션·지연 문제**였다:

- `@Transactional`이 없어 리포지토리 호출마다 read-only 트랜잭션이 열리고 닫힌다 → **체크아웃 5.7회**
- BM25/벡터 병렬 실행 **전에** 컨트롤러에서 호출되므로 5.7 RTT가 TTFB에 직렬로 얹힌다
- `hybridCoreCache`(5분) 바깥이라 **코어 캐시가 히트해도 매번 나갔다**

#### 무엇을 했나

| | 전 | 후 |
|---|---|---|
| `expandSearchTerms` | 5.7쿼리 / 체크아웃 5.7회 | **1쿼리 / 체크아웃 1회**, 캐시 히트 시 **0** |
| `getArticleIdsByKeywordWithSynonyms` | 최대 3M+N 쿼리 | **2쿼리** |
| `VideoService.getVideoIdsByKeywordWithSynonyms` | 최대 3M+N 쿼리 | **2쿼리** |

- `TermSynonymRepository.findSynonymPairsByTerms` — term 문자열 목록 → 유의어 쌍을 양방향 조인 1회로.
  `TermSynonymService.getSynonymsByTerms`가 이걸 `원본 → 유의어 목록` 맵으로 접는다.
  서비스가 클래스 레벨 `@Transactional(readOnly = true)`라 호출 1회 = 트랜잭션 1개 = 체크아웃 1회.
- `findSynonymTermIdsByTermIds`(IN 배치) — `expandTermIdsWithSynonyms`의 id별 반복 조회를 대체.
  반환 계약(원본 포함, 중복 제거)은 유지.
- `TermRepository.findByTermIn`(이미 있던 메서드) 재사용 — term별 `findByTerm`을 IN 조회 1회로.
- **캐시**: `searchTermExpansion` (Caffeine, 30분/2000). `@Cacheable`이 아니라 `CacheManager` 수동
  get/put인 이유는 **유의어 조회 실패로 degrade된 결과를 캐시에 남기지 않기 위해서**다.
  무효화는 `TermSynonymService`의 변경 메서드 7개 + term 삭제 경로(`ArticleTermService`)에서
  `@CacheEvict(allEntries = true)`.

#### 동작 차이 (의도된 것)

1. **확장 순서**: `[term1, term1유의어, term2, …]` → `[term1, term2, …, 유의어…]`.
   `HybridSearchScorer.buildBM25Query`가 가중치(>=1.0 / <1.0)로 재분할하므로 **생성되는 BM25 쿼리
   문자열은 동일**하다.
2. **대소문자 fallback**: 기존은 "정확 매칭이 비면 lower → upper" 순차 채택, 지금은 IN으로 매칭되는
   변형을 모두 가져온다. term 테이블은 V1_16에서 전부 소문자화됐고 이후 삽입도 소문자라 실데이터에서는
   동일 결과 — 대소문자가 섞인 행이 있어야만 재현율이 넓어지는 방향으로 갈린다.

#### 확인할 것 (배포 후)

- `pg_stat_statements`에서 `term_synonym` 쿼리 `calls` / 검색 RPS 비율이 5.7 → 1 이하
- HikariCP 체크아웃 지표 — **이 작업의 실제 타겟**
- `pgss_budget.sql`에서 term 항목 3ms → ~0

---

### 그 외 (우선순위 순)

| | 작업 | 근거 | 성격 |
|---|---|---|---|
| 1 | **프리웜 `LazyInitializationException` 수정** | 시간당 9~11회 실패, cold start 방지 무력화 | **지금 실사용자 영향** |
| 2 | `warmChunkCacheAsync` 제거 또는 게이트 (`VectorSearchService.java:543`) | 예산 10%, AI 요약을 안 쓰는 사용자에게도 항상 도는 투기적 작업. virtual thread 무제한 | RPS |
| 3 | ~~cross-scoring `IN (:ids)` → `= ANY(:ids)`~~ **✅ 적용 (2026-08-12)** — BM25 보충 4종(`computeBM25ScoreForArticleIds` 계열)은 남음 | 통계 정규화 + 플랜 캐시 + Grafana 카디널리티 동시 해결 | 관측성 |
| 4 | ~~`expandSearchTerms` N+1 → 조인~~ **✅ 적용 (2026-08-09)** — 아래 D 참고 | 검색당 5.7 쿼리 = 커넥션 체크아웃. CPU는 3ms | 지연·안정성 |
| 4-1 | **하이브리드 랭킹 정확도 측정 하네스** | B'를 미측정으로 켰다 — 위 "남은 검증 부채" 참고. `search_log`→`search_logs` 오타 선수정 필요 | **품질 부채** |
| 5 | 관련 글 추천 경로 검토 | 11%, 검색과 무관한 별개 경로인데 아무도 안 봄 | RPS(간접) |
| 6 | `SET STORAGE PLAIN` 전환 검토 | tidx 64%가 근거. 단 **B' 적용 후엔 cross-scoring 기여분이 사라지므로**, 나머지 벡터 경로(본검색 stage-2, 워밍, 관련글 = 47%)에서 따로 측정해 다운타임을 정당화해야 함 | 큼, 리스크도 큼 |
| 7 | postgres_exporter 스크랩 부하 검토 | 유휴 DB 시간의 26%, 버퍼 압박 | 인프라 |

**PLAIN 전환 리스크**: 테이블 재작성 필요(`VACUUM FULL` = 배타 락, 410MB를 1GB 박스에서 →
중단 발생. `pg_repack`은 설치 필요) / heap이 9MB → 약 320MB로 커져 shared_buffers 256MB 초과 /
되돌리려면 또 재작성.

---

## 부록 A. 재현 — 측정 방법

### 요청당 DB CPU (Prometheus)

```promql
sum(rate(node_cpu_seconds_total{job="db-node", mode!="idle"}[3m]))
  / sum(rate(http_server_requests_seconds_count{uri="/api/search/articles/loadtest"}[3m]))
```

CPU 모드 분해로 연산/디스크 병목을 가른다:

```promql
sum by (mode) (rate(node_cpu_seconds_total{job="db-node", mode!="idle"}[2m]))
```

### 쿼리별 예산 (`pg_stat_statements`)

> `total_exec_time`은 CPU가 아니라 **경과 시간**이다. 포화 상태에선 대기가 섞여 부풀린다.
> **단가는 저부하(VU=1)에서 재고** 요청당 `calls`를 곱해 예산을 세운 뒤,
> `node_cpu ÷ RPS` 총량과 대조할 것.

```sql
SELECT pg_stat_statements_reset();   -- 부하 직전
-- 부하 실행
```

```sql
-- 분류별 비용 (워밍 쿼리도 embedding_binary HNSW를 쓴다.
--  청크 본문 조인 여부로만 본검색과 구분되므로 이 분기를 먼저 둘 것)
WITH s AS (
  SELECT calls, total_exec_time, mean_exec_time, max_exec_time,
         shared_blks_hit + shared_blks_read AS blks,
         lower(regexp_replace(query, '\s+', ' ', 'g')) AS q
  FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%'
)
SELECT CASE
    WHEN q LIKE '%embedding_binary%<~>%' AND q LIKE '%clova_chunk_contents%'
         THEN '워밍 HNSW'
    WHEN q LIKE '%embedding_binary%<~>%'      THEN '벡터 2단계'
    WHEN q LIKE '%<#>%' AND q NOT LIKE '%embedding_binary%'
                                              THEN 'cross-scoring'
    WHEN q LIKE '%@@@%'                       THEN 'BM25'
    WHEN q LIKE '%search_query_embedding%'    THEN '임베딩 캐시'
    WHEN q LIKE '%term_synonym%'              THEN '유의어'
    ELSE '기타' END AS 구분,
  calls, round(mean_exec_time::numeric,2) AS mean_ms,
  round(total_exec_time::numeric,1) AS total_ms,
  round(100*total_exec_time/NULLIF(SUM(total_exec_time) OVER (),0)) AS pct,
  blks, left(q, 70) AS query
FROM s ORDER BY total_exec_time DESC LIMIT 30;
```

```sql
-- cross-scoring은 IN 길이별로 쪼개지므로 반드시 합산해서 볼 것
SELECT count(*) AS 조각수, sum(calls) AS calls,
       round(sum(total_exec_time)::numeric,1) AS total_ms,
       round((sum(total_exec_time)/sum(calls))::numeric,1) AS mean_ms
FROM pg_stat_statements
WHERE query LIKE '%avg_similarity%' AND query NOT LIKE '%embedding_binary%';
```

### `embedding_binary` 인라인 확인 (B' Stage 1의 전제)

bit(1024)=128B면 당연히 인라인이지만, 같은 테이블의 다른 컬럼 때문에 행이 TOAST로 밀렸을 수 있다.
`clova_article_chunk`의 toast 크기가 작으면 인라인 확정 — Stage 1 비용은 heap 스캔 수십 블록이다.

```sql
SELECT a.attname, a.attstorage, a.attlen,
       pg_size_pretty(pg_relation_size(c.oid)) AS heap,
       pg_size_pretty(pg_total_relation_size(c.oid)
                      - pg_relation_size(c.oid) - pg_indexes_size(c.oid)) AS toast
FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
WHERE c.relname = 'clova_article_chunk' AND a.attnum > 0 AND NOT a.attisdropped;
```

### TOAST 증폭 확인

쿼리 실행 **전후**로 각각 조회해 증가분을 본다. (`toast_*`+`tidx_*` 증가분이 `heap_*`을 압도하면
TOAST 간접 참조가 원인)

```sql
SELECT heap_blks_hit, heap_blks_read, toast_blks_hit, toast_blks_read,
       tidx_blks_hit, tidx_blks_read
FROM pg_statio_all_tables WHERE relname = 'clova_chunk_vectors';
```

### cross-scoring 블록 소비 (자립 실행 — 값 붙여넣기 불필요)

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH q AS MATERIALIZED (
    SELECT embedding_normalized AS vec FROM clova_chunk_vectors ORDER BY id LIMIT 1
),
ids AS MATERIALIZED (
    SELECT article_id FROM clova_article_chunk GROUP BY article_id ORDER BY article_id LIMIT 100
)
SELECT article_id, AVG(similarity) AS avg_similarity
FROM (
    SELECT cac.article_id,
           -(ccv.embedding_normalized <#> q.vec) AS similarity,
           ROW_NUMBER() OVER (PARTITION BY cac.article_id
                              ORDER BY ccv.embedding_normalized <#> q.vec) AS rn
    FROM clova_article_chunk cac
    JOIN article a ON cac.article_id = a.id
    JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
    CROSS JOIN q
    WHERE a.deleted_at IS NULL
      AND cac.article_id = ANY (ARRAY(SELECT article_id FROM ids))
) ranked
WHERE rn <= 3 GROUP BY article_id ORDER BY avg_similarity DESC;
```

대표 청크 방식과 비교하려면 위 쿼리에서 윈도우/집계를 걷어내고
`AND cac.is_representative = true`를 추가한다.

> 주의: 위 쿼리는 `ORDER BY article_id LIMIT 100`이라 **ID가 연속된 아티클**을 쓴다.
> 실제 cross-scoring 대상은 BM25 상위 100개로 ID가 흩어져 지역성이 나쁘다.
> 절대값은 낙관적일 수 있으나 **비율은 유효**하다.

---

## 부록 B. `pg_stat_statements` 활성화 (2026-08-09 적용 완료)

모듈은 이미지에 포함돼 있었고(1.11) preload만 없었다.
`shared_preload_libraries`를 `pg_search,pg_cron` → `pg_search,pg_cron,pg_stat_statements`로 변경.

### ⚠️ `ALTER SYSTEM`으로 하지 말 것 — 운영 13분 중단 발생

```sql
ALTER SYSTEM SET shared_preload_libraries = 'pg_search,pg_cron,pg_stat_statements';  -- 금지
```

리스트 파라미터인데 `ALTER SYSTEM`은 따옴표로 감싼 문자열 **전체를 요소 하나**로
`postgresql.auto.conf`에 저장한다. 재시작 시:

```
FATAL: could not access file "pg_search,pg_cron,pg_stat_statements": No such file or directory
```

기동 실패 → 컨테이너 재시작 루프 → psql도 안 붙어 `ALTER SYSTEM RESET`조차 불가.
`postgresql.auto.conf`를 호스트에서 직접 편집해야 복구된다. (2026-08-09 03:55~04:08 UTC 중단)

### 올바른 방법 — `postgresql.conf` 직접 수정 + 오프라인 검증

`postgresql.conf`에서는 따옴표로 감싼 콤마 목록이 정상적으로 리스트로 파싱된다.

```bash
PGDATA_HOST=<docker inspect postgres 의 Source 경로>

sudo cp $PGDATA_HOST/postgresql.conf $PGDATA_HOST/postgresql.conf.bak
sudo sed -i "768s|.*|shared_preload_libraries = 'pg_search,pg_cron,pg_stat_statements'|" \
     $PGDATA_HOST/postgresql.conf

# 재시작 전 반드시 검증 — 서버를 안 내리고 설정만 파싱한다
docker exec postgres postgres -C shared_preload_libraries -D /var/lib/postgresql/data
# → pg_search,pg_cron,pg_stat_statements 가 그대로 나와야 진행. 아니면 재시작 금지.

docker restart postgres
psql -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
psql -c "SELECT count(*) FROM pg_stat_statements;"
```

롤백: `sudo cp $PGDATA_HOST/postgresql.conf.bak $PGDATA_HOST/postgresql.conf && docker restart postgres`

### 상시 모니터링 (미적용)

Grafana 노출은 `docker-compose.yml`의 `postgres_exporter`에 `command: ['--collector.stat_statements']`.
DSN 계정에 `pg_read_all_stats` 롤 필요.
**단, `IN (:ids)` 분산 문제를 먼저 고쳐야 한다** — 안 그러면 가장 중요한 쿼리가 queryid 수십 개로
흩어져 카디널리티가 폭발하고 집계가 불가능하다.
