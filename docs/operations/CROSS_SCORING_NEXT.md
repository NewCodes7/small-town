# cross-scoring 보충 최적화 — 다음 후보

**2026-08-13 작성.** `PGSS_SEARCH_COST.md`의 A / B' 적용 이후, 운영 트레이스와 로그로 다시 잰
"벡터 보충 · BM25 보충"의 현재 상태와 남은 선택지.

측정 근거:
- 트레이스 `850054decdf63c36a3cc480f991fe502` (blue, 2026-08-13 21:21:15, `virtual thread 성능 테스트 결과`)
- Loki `{job="small-town"} |= "후보재활용"` 최근 24h, 실사용 요청 5건 + 부하테스트 25건

---

## 0. 지금 시점의 사실

### 스팬 타임라인 (요청 시작 기준 ms)

| 구간 | 시작 | 끝 | 소요 |
|---|---|---|---|
| 유의어 확장 (`term_synonym` 조인) | 22.9 | 187.6 | **164.7** |
| BM25 본검색 (`@@@ LIMIT 100`) | 190.5 | 359.9 | **168.0** |
| 벡터 검색 전체 | 191.2 | 542.5 | 351.3 |
| ├ Clova HTTP (cache miss) | 195.3 | 442.2 | 247.0 |
| └ 2단계 본검색 쿼리 | 449.9 | 537.7 | **87.8** |
| 청크 캐시 워밍 (async, DB 경합) | 544.3 | 638.6 | **94.3** |
| **벡터 보충 (cross-scoring 2단계)** | 543.9 | 649.4 | **105.5** |
| **BM25 보충** | 649.7 | 820.9 | **171.2** |
| 유효성 검증 (`article` id 164건) | 823.8 | 826.4 | 2.6 |
| NSF | 828.7 | 829.2 | 0.4 |
| 페이지 로딩 + 좋아요 | 832.0 | 851.5 | 14.8 |
| **총** | | | **856.7** |

같은 요청의 `[검색]` 로그: `BM25 171ms (100개), Vector 351ms (85개), 후보재활용 0/79건
(DB보충 79건, 보충통과 8건) | 총 640ms`.

### 이미 고친 것을 빼고 보면 보충이 DB 시간의 절반이다

이 트레이스에는 **main에 이미 들어갔지만 배포 전인** 두 커밋의 대상이 그대로 찍혀 있다.

- `eec4532` 유의어 확장을 Clova 호출과 병렬화 → 164.7ms가 임베딩 대기에 겹쳐 숨는다
- `b020104` 미사용 청크 캐시 워밍 제거 → 94.3ms 쿼리 자체가 사라진다

둘을 빼면 이 요청의 DB 시간은 약 547ms이고, 그중 **벡터 보충 105.5 + BM25 보충 171.2 = 276.7ms
= 50.6%**다. 두 보충은 다른 모든 작업이 끝난 뒤 **직렬로** 붙어 있어 지연에도 그대로 얹힌다.

---

## 1. 새로 확인된 것

### 1-1. 이제 더 비싼 쪽은 BM25 보충이다 (171ms > 105ms)

B'로 벡터 보충이 295 → 105ms로 내려가면서 **순위가 뒤집혔다.** BM25 보충은 손댄 적이 없다.

비싼 이유가 명확하다 — 쿼리가 본검색과 **사실상 같은 일**을 한다:

```sql
SELECT asi.id, paradedb.score(asi.id), asi.published_at
FROM article_analyzed_content asi
WHERE asi @@@ paradedb.parse(:searchQuery)
  AND asi.id IN (:articleIds)      -- 대상 64건
ORDER BY bm25_score DESC           -- LIMIT 없음
```

대상은 64건인데 **소요시간이 본검색(LIMIT 100, 168ms)과 같은 171ms**다. `id IN`이 작업량을
줄이지 못하고 있다는 뜻 — `@@@` 매치 집합 전체를 스코어링한 뒤 post-filter로 걸러내는
플랜으로 보인다. LIMIT도 없어 정렬까지 전건에 걸린다.

> **확인 필요**: 위는 소요시간에서 추론한 가설이다. `EXPLAIN (ANALYZE, BUFFERS)` 한 번이면
> pushdown 여부가 갈린다. 2장의 방안 선택이 여기에 달려 있다.

### 1-2. 항목 A(stage-1 후보 재활용)는 운영에서 한 번도 발동한 적이 없다

로그 표본 전체에서 재활용이 **0건**이다:

| 키워드 | 후보재활용 | DB보충 | 보충통과 |
|---|---|---|---|
| `virtual thread 성능 테스트 결과` | **0** / 79 | 79 | 8 |
| `쿠버네티스 도입한 사례 알려줘` | **0** / 58 | 58 | 3 |
| `java virtual thread` | **0** / 82 | 82 | 2 |
| `kafka storm 연동 아키텍처 구성` | **0** / 90 | 90 | 0 |
| 부하테스트 25건 | **0** / 99~100 | 99~100 | 0~20 |

노이즈가 아니라 **구조적으로 발동할 수 없다.** 재활용 조건과 본검색 통과 조건이 서로를 배제한다:

1. 재활용 조건은 `candidate_similarity >= 0.52` (상위 topK 후보 청크 평균)
2. `candidate_similarity`가 0.52 이상이면 그 평균에 든 청크 중 최소 하나가 0.52 이상이다
3. `avg_similarity`는 **청크별로** 0.52 필터를 건 뒤의 평균이므로 non-NULL이 된다
4. `is_main = (avg_similarity IS NOT NULL AND row_number <= 100)`이므로, 벡터 결과가 상한 100에
   걸리지 않는 한 **is_main = true → 본검색 결과에 이미 포함**된다
5. 본검색 결과에 있으면 `needVectorIds`(= BM25-only)에서 이미 빠져 있다

즉 재활용 대상 집합은 "벡터 결과가 100 상한에 걸려 잘린 아티클" ∩ "BM25 상위 100" 뿐이고,
실측상 그 교집합이 비어 있다. **지금의 `reuseStage1Candidates` 블록은 죽은 코드다.**

부수적으로, `candidateScores`를 만들기 위해 5개 2단계 검색 쿼리에 추가한 `agg` CTE
(`candidate_similarity` 컬럼, `CASE WHEN COUNT(*) >= topK`)도 아무도 쓰지 않는 값을 계산 중이다.

### 1-3. 보충통과율이 0~10%다 — Stage 2 컷 20은 2배 이상 여유다

`보충통과 P`(DB 보충 대상 중 실제로 임계값 0.52를 넘긴 수)는 실사용 요청에서 **0, 2, 3, 8건**이다.
대상은 58~90건. 즉 **보충 작업의 90% 이상이 아무 점수도 만들지 못한다.**

B' 문서가 "컷은 이 분포로 정한다"며 남겨둔 값이 지금 관측됐다. `stage2-limit=20`은 실측 최댓값
8건의 2.5배이므로, **컷을 10으로 내려도 손실이 없다**(부하테스트의 20건은 임계값 0.0으로 필터를
푼 값이라 판단 근거가 아니다).

### 1-4. 본검색과 보충의 임계값 적용 방식이 다르다 (기록용)

- 본검색: 청크별로 `similarity >= 0.52` 필터 → 통과한 것만 평균 (아티클 하나만 강해도 통과)
- 보충: 상위 topK 청크 **전부**의 평균에 0.52 (한 청크만 강하면 평균이 눌려 탈락)

두 값이 같은 min-max 정규화를 통과해 NSF에 들어간다. `PGSS_SEARCH_COST.md` 항목 A가 지적한
"추정량이 다른 두 집단을 한 척도로" 문제가 임계값 쪽에도 남아 있다. 보충통과율이 낮은 데에도
이 비대칭이 기여한다.

---

## 2. BM25 보충 — 방안

### B-1. id 필터를 ParadeDB 쿼리 안으로 넣는다 (효과 최대, 의미 불변)

`WHERE ... AND asi.id IN (...)`(post-filter)를 BM25 쿼리 자체의 조건으로 바꾼다. BM25 인덱스는
`key_field='id'`(V1_27)로 만들어져 있어 id가 인덱스 안에 있다.

```sql
-- 질의 문자열에 붙이는 형태 (앱에서 buildBM25Query 결과에 append)
(원본쿼리) AND id:IN [123 456 789]
-- 또는 paradedb.boolean(must => ARRAY[paradedb.parse(:q), paradedb.term_set(...)])
```

- **점수가 바뀌지 않는다.** BM25의 idf/평균 문서길이는 코퍼스 전역 통계라 후보 제한과 무관하다.
  랭킹 리스크 0.
- 64건만 스코어링하면 171ms → 한 자릿수 ms가 기대된다.

**선행 확인**: 설치된 pg_search 버전의 질의 문법 (`SELECT extversion FROM pg_extension WHERE
extname='pg_search'`)과, 1-1의 EXPLAIN으로 현재 정말 post-filter인지.

### B-2. 본검색을 over-fetch해서 꼬리를 재사용한다 (B-1이 막히면 이쪽)

`searchByBM25`의 `LIMIT 100` → 300~500으로 올리고,

- 상위 100은 지금처럼 `bm25Results` (본검색 의미·정규화 불변)
- 101위 이하는 `bm25CandidateScores`로 보관 → `needBm25Ids`를 여기서 채우고 **보충 쿼리를 아예 안 돈다**

**벡터 쪽 항목 A와 결정적으로 다른 점: 추정치가 아니다.** 같은 쿼리·같은 인덱스·같은 스코어이므로
보충 쿼리가 돌려줄 값과 **비트 단위로 동일**하다. 1-2 같은 편향/자기모순이 생길 여지가 없다.

꼬리에도 없는 id는? 두 경우뿐이다.
- `@@@`에 아예 매치되지 않음 → 지금도 보충 쿼리가 아무것도 안 돌려준다. 동일.
- 매치되지만 500위 밖 → BM25 점수가 최하위권이라 min-max 정규화 후 `normBM25 ≈ 0`.
  점수를 안 채우는 것과 NSF 기여가 사실상 같다.

**비용**: 쿼리 1회 증가분만 재면 된다. ParadeDB는 어차피 매치 전체를 스코어링하므로 LIMIT 500의
추가 비용은 `published_at` 힙 페치 400행 + 정렬 정도다. **168ms + 171ms → 180ms 내외**가 목표.

### B-3. 날짜 정렬 요청은 두 보충을 건너뛴다

`sort=latest|oldest`면 **보충이 응답에 아무 영향을 주지 않는다.** 증명:

- 최종 목록은 `nsfScores.keySet() ∩ validArticleIds`를 `publishedAtMap`으로 정렬한 것
- `nsfScores`의 키셋 = `bm25Results ∪ vectorResults`인데, 보충은 **반대쪽 맵에 이미 있는 id에만**
  점수를 채우므로(`needVectorIds ⊆ bm25Results`, `needBm25Ids ⊆ vectorResults`) 합집합을 넓히지 않는다
- `candidateIds`·`publishedAtMap`·`vectorOnlyIds`(= `foundByVector` 플래그)는 모두 보충 **이전에** 확정된다

즉 결과 집합도 순서도 완전히 같고, 달라지는 것은 DTO의 `bm25Score`/`vectorScore`/`finalScore`
표시값뿐이다.

**걸림돌 2개**:
- `hybridCoreCache` 키가 키워드뿐이라 날짜정렬로 계산한 코어가 적합도순 요청에 재사용되면 안 된다.
  → `HybridCoreResult`에 `crossScored` 플래그를 두고, 적합도순 진입 시 false면 재계산.
- **트래픽 중 날짜정렬 비중을 모른다.** Loki에는 nginx 액세스 로그가 없다(`job`은 `small-town`,
  `crawler` 뿐). 비중을 먼저 재지 않으면 이 방안의 기댓값을 계산할 수 없다.

### B-4. 보충 4종의 `IN (:ids)` → `= ANY(CAST(:ids AS bigint[]))`

`PGSS_SEARCH_COST.md` 그 외 3번에서 벡터 쪽만 하고 남긴 잔여분
(`computeBM25ScoreForArticleIds` 계열 4개 + `findIdAndPublishedAtByIdIn`).
id 개수마다 다른 쿼리가 되어 플랜 캐시가 안 먹고 `pg_stat_statements`가 수십 개로 흩어진다.
B-1/B-2를 적용하면 앞의 4개는 사라지므로 **유효성 검증 쿼리만 남는 정리 작업**이 된다.

### B-5. (기각) 두 보충의 병렬 실행

지연은 줄지만 요청당 커넥션 점유가 늘어난다(풀 5). 자원 절감이 목적이므로 현행 직렬 유지 —
기존 코드 주석의 판단 그대로다.

---

## 3. 벡터 보충 — 방안

### V-1. `stage2-limit` 20 → 10 (코드 변경 없음, 지금 가능)

근거는 1-3. 환경변수 `SEARCH_HYBRID_CROSS_SCORING_STAGE2_LIMIT=10`.
Stage 2 청크 수가 절반이 되므로 TOAST 접근이 그대로 절반. `보충통과 P`를 계속 보면서
붕괴 여부만 감시하면 된다(이미 로그에 있다).

### V-2. Stage 1을 index-only scan으로 (covering index)

```sql
CREATE INDEX CONCURRENTLY idx_clova_chunk_article_binary
  ON clova_article_chunk (article_id) INCLUDE (embedding_binary);
```

Stage 1은 대상 60~90 아티클의 전 청크(수백 개) heap 행을 만진다. `bit(1024)` = 128B라
INCLUDE로 넣으면 인덱스 약 22MB, **힙 접근이 통째로 사라진다**(청크 테이블은 append-mostly라
visibility map이 잘 서 있다). B'가 남겨둔 "Stage 1 자신의 300~600블록"을 겨냥한 항목.

### V-3. Stage 2가 읽는 청크 수를 아티클당 상한으로 자른다

지금 Stage 2는 생존 아티클의 **전 청크**를 다시 읽는다(그래서 값이 단일 쿼리와 동일하다).
아티클당 청크는 p50 7, p95 20, max 137이라 **긴 아티클 하나가 Stage 2 비용을 지배**할 수 있다.
Stage 1이 이미 청크를 해밍 순으로 줄 세웠으므로, 아티클당 상위 K'(예: 6)개 **청크 id**만
Stage 2로 넘기면 상한이 고정된다. 최종값은 그중 상위 3의 평균 — 해밍 상위 6 안에 코사인 상위 3이
들어갈 확률이 높지만 **동치성 보장은 깨진다**(현재 테스트가 잡아낼 것이다).

### V-4. Stage 2를 아예 없앤다 — binary 추정치를 보충값으로 쓴다

가장 큰 절감(TOAST 접근 0). Stage 1이 이미 `cos(π·h/1024)`로 아티클 점수를 계산하고 있으므로
그 값을 그대로 `vectorResults`에 넣는다.

- 오차: B' 문서의 시뮬레이션 기준 σ ≈ 0.01~0.023, 편향은 **청크 수에 따라 0 ~ +0.09**로
  체계적이라 `f(n_chunks)` 보정이 가능하다
- 보충 점수는 정의상 하위 집단이라 `minMaxNormalize`에서 기여가 작다
- 다만 **랭킹을 바꾸고, 그 변화량을 잴 수단이 여전히 없다** → 4장의 하네스가 선행 조건

### V-5. 항목 A(재활용) 정리 — 제거 또는 재정의

1-2대로 지금은 죽은 코드다. 두 갈래:
- **제거**: `reuseStage1Candidates` 블록 + `candidateScores` + 5개 쿼리의 `candidate_similarity`
  컬럼을 걷어낸다. 본검색 쿼리가 단순해지고 계산도 준다.
- **재정의**: 임계값 조건(안전장치 2)을 없애고, 후보 점수가 **하한**임을 이용해
  "후보 점수 + 최대 보정폭 < 0.52면 DB에 물어보지 않고 탈락"이라는 **반대 방향 가지치기**로 쓴다.
  1-3에서 90%가 탈락하는 것을 감안하면 이쪽이 잠재 이득이 훨씬 크다. 다만 상한 보정폭 근거가 필요하다.

### V-6. 날짜 정렬 스킵 — B-3과 같은 레버 (벡터 보충도 같이 빠진다)

---

## 4. 권장 순서

| 순 | 작업 | 성격 | 리스크 |
|---|---|---|---|
| 1 | **V-1** `stage2-limit` 20 → 10 | env only, 즉시 | 낮음 (P로 감시) |
| 2 | **1-1 EXPLAIN** — BM25 보충이 post-filter인지 확정 | 측정 | — |
| 3 | **B-1**(pushdown 가능 시) 또는 **B-2**(over-fetch) | 최대 효과 | B-1 = 0, B-2 = 사실상 0 |
| 4 | **V-5** 재활용 코드 제거(또는 재정의) | 죽은 코드 정리 | 없음 |
| 5 | **V-2** covering index | 인덱스 22MB 추가 | 낮음 |
| 6 | **B-4** `= ANY` 잔여분 | 관측성 | 없음 |
| 7 | 하이브리드 랭킹 하네스 → **V-3 / V-4** | 품질 부채 상환 후 | 높음 |
| — | **B-3 / V-6** 날짜정렬 스킵 | 트래픽 비중 측정 후 판단 | 중간(캐시) |

1~3만으로 이 요청의 보충 276.7ms 중 **150~250ms**가 빠질 것으로 본다.

---

## 5. 측정 방법

### BM25 보충의 pushdown 여부 (2번 항목)

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT asi.id, paradedb.score(asi.id), asi.published_at
FROM article_analyzed_content asi
WHERE asi @@@ paradedb.parse('<실제 검색 쿼리 문자열>')
  AND asi.id IN (<vector-only id 60여 개>)
ORDER BY bm25_score DESC;
```

`rows removed by filter`가 크면 post-filter 확정 → B-1이 성립한다.
비교 대상으로 같은 조건을 `id:IN [...]`로 질의 문자열에 넣은 버전을 나란히 실행.

### 보충 비중 (Loki, 코드 변경 없이)

```logql
{job="small-town"} |= "[검색]"
  | regexp "보충통과 (?P<passed>\\d+)건"
  | regexp "DB보충 (?P<supplemented>\\d+)건"
```

`보충통과 / DB보충` 비율이 컷 판정의 1차 신호다. 재활용 재정의(V-5)의 기대 이득도 이 비율이다.

### 트레이스에서 두 보충만 뽑기

TraceQL:

```
{ name = "bm25-supplement" } | avg(duration)
```

벡터 보충에는 전용 스팬 이름이 없다 — `Observation`으로 감싸면(`search.vector-supplement`)
BM25 보충처럼 대시보드에서 바로 잡힌다. **작은 선행 작업으로 넣어둘 것.**
