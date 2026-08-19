# BM25 mutable 세그먼트 튜닝 — 명령어

대상: `article_analyzed_content_bm25_idx` (ParadeDB pg_search)
전부 런타임 reload. 재시작 불필요, 즉시 되돌리기 가능.

---

## 0. 권한 / 현재 상태

```sql
SELECT current_user, usesuper FROM pg_user WHERE usename = current_user;

 current_user | usesuper 
--------------+----------
 newcodes     | t
(1 row)
```

```sql
SELECT segno, mutable, num_docs, num_deleted, pg_size_pretty(byte_size::bigint) sz
FROM paradedb.index_info('article_analyzed_content_bm25_idx')
ORDER BY mutable DESC, num_docs DESC;

  segno   | mutable | num_docs | num_deleted |   sz    
----------+---------+----------+-------------+---------
 6ebe4607 | t       |      645 |           0 | 
 200184f7 | f       |    14940 |           0 | 13 MB
 6921ffbf | f       |     1000 |           0 | 1153 kB
 679f8db4 | f       |     1000 |           0 | 1181 kB
 b84ae3df | f       |     1000 |           0 | 1174 kB
(5 rows)
```

## 1. 기준선 측정 (3회 반복, 마지막 값)

```sql
SET max_parallel_workers_per_gather = 0;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, paradedb.score(id) AS bm25_score, published_at
FROM article_analyzed_content
WHERE article_analyzed_content @@@ paradedb.parse('title_terms:카프카^3.0 OR content_terms:카프카^2.0')
ORDER BY bm25_score DESC LIMIT 100;
```
```
 Limit  (cost=10.00..10.63 rows=84 width=20) (actual time=149.372..149.513 rows=72 loops=1)
   Buffers: shared hit=933
   ->  Custom Scan (ParadeDB Scan) on article_analyzed_content  (cost=10.00..10.63 rows=84 width=20) (actual time=149.370..149.501 rows=72 loops=1)
         Table: article_analyzed_content
         Index: article_analyzed_content_bm25_idx
         Segment Count: 5
         Heap Fetches: 72
         Exec Method: TopNScanExecState
         Scores: true
            TopN Order By: pdb.score() desc
            TopN Limit: 100
            Queries: 1
         Tantivy Query: {"with_index":{"query":{"parse":{"query_string":"title_terms:카프카^3.0 OR content_terms:카프카^2.0","lenient":null,"conjunction_mode":null}}}}
         Buffers: shared hit=933
 Planning:
   Buffers: shared hit=55
 Planning Time: 1.110 ms
 Execution Time: 149.743 ms
(18 rows)
```

## 2. 설정 적용

```sql
ALTER SYSTEM SET paradedb.global_mutable_segment_rows = 1;
SELECT pg_reload_conf();
SHOW paradedb.global_mutable_segment_rows;

ALTER SYSTEM
 pg_reload_conf 
----------------
 t
(1 row)

 paradedb.global_mutable_segment_rows 
--------------------------------------
 -1
(1 row)
```

`pg_reload_conf()` 권한 없을 때 (DB 서버 셸):

```bash
docker exec <pg컨테이너> pg_ctl reload -D /var/lib/postgresql/data
# 또는
systemctl reload postgresql
```

## 3. 기존 mutable 세그먼트 flush

```sql
UPDATE article_analyzed_content SET updated_at = updated_at
WHERE id = (SELECT min(id) FROM article_analyzed_content);
```

```sql
SELECT segno, mutable, num_docs
FROM paradedb.index_info('article_analyzed_content_bm25_idx')
WHERE mutable;

  segno   | mutable | num_docs 
----------+---------+----------
 6ebe4607 | t       |      646
(1 row)

```

안 비워지면:

```sql
REINDEX INDEX CONCURRENTLY article_analyzed_content_bm25_idx;
```

## 4. 재측정

1번 블록 그대로 3회.

```
 Limit  (cost=10.00..10.75 rows=100 width=20) (actual time=1.157..1.326 rows=72 loops=1)
   Buffers: shared hit=185
   ->  Custom Scan (ParadeDB Scan) on article_analyzed_content  (cost=10.00..10.75 rows=100 width=20) (actual time=1.156..1.314 rows=72 loops=1)
         Table: article_analyzed_content
         Index: article_analyzed_content_bm25_idx
         Segment Count: 4
         Heap Fetches: 72
         Exec Method: TopNScanExecState
         Scores: true
            TopN Order By: pdb.score() desc
            TopN Limit: 100
            Queries: 1
         Tantivy Query: {"with_index":{"query":{"parse":{"query_string":"title_terms:카프카^3.0 OR content_terms:카프카^2.0","lenient":null,"conjunction_mode":null}}}}
         Buffers: shared hit=185
 Planning:
   Buffers: shared hit=65
 Planning Time: 1.146 ms
 Execution Time: 1.495 ms
(18 rows)
```

## 5. 크롤링(04:00) 전 정리

```sql
ALTER SYSTEM SET paradedb.global_mutable_segment_rows = 50;
SELECT pg_reload_conf();
```

## 되돌리기

```sql
ALTER SYSTEM RESET paradedb.global_mutable_segment_rows;
SELECT pg_reload_conf();
```

---

## 모니터링

세그먼트 수 (수십 개로 늘면 값을 올릴 것):

```sql
SELECT count(*) FROM paradedb.index_info('article_analyzed_content_bm25_idx');
```

진행 중 머지:

```sql
SELECT * FROM paradedb.merge_info('article_analyzed_content_bm25_idx');
```

인덱스 크기:

```sql
SELECT pg_size_pretty(pg_relation_size('article_analyzed_content_bm25_idx'));
```

---

# 측정 결과 / 해석

**2026-08-15 프로덕션 실측.** 위 0~4번 실행 결과를 분석한 것.

## 핵심: BM25 지연은 코퍼스 크기가 아니라 "마지막 flush 이후 쓴 행 수"의 함수다

0번의 세그먼트 구성과 1번/4번의 실행시간을 대조하면 비용의 출처가 한 곳으로 좁혀진다.

| | 문서 수 | 쿼리 기여 시간 | 문서당 |
|---|---:|---:|---:|
| immutable 세그먼트 4개 | 17,940 | 1.5ms | **0.00008 ms** |
| mutable 세그먼트 1개 | 645 | 148.2ms | **0.230 ms** |

mutable 세그먼트는 term dictionary가 없어 **매 쿼리마다 전 문서를 선형 스캔**한다.
매칭 여부와 무관하므로 순수 고정비다.

- mutable 문서 1건 = sealed 문서 1건의 **약 2,900배**
- 전체의 **3.5%** 문서(645/18,585)가 쿼리 시간의 **99.0%** 를 차지
- 시간이 버퍼 수에 비례하지 않는다 (933 → 185 = 5배인데 시간은 100배). I/O 아니라 CPU다.

로컬 재현(합성 코퍼스 18,401행, 250토큰/문서)에서 잰 **0.077 ms/doc** 의 정확히 3배가
프로덕션 **0.230 ms/doc** 이다. 실제 `content_terms` 가 ~750토큰이라는 뜻으로,
별개 환경·별개 데이터에서 같은 선형 모델이 재현됐다.

## 세그먼트 구성이 말해주는 것 — 축적이 아니라 톱니파

```
200184f7  f  14,940 docs  13 MB      ← CREATE INDEX 로 만들어진 원본
6921ffbf  f   1,000 docs  1153 kB    ┐
679f8db4  f   1,000 docs  1181 kB    ├ 1000 캡에 도달해 sealed 된 과거 mutable
b84ae3df  f   1,000 docs  1174 kB    ┘
6ebe4607  t     645 docs  (크기 없음) ← 지금 채워지는 중
```

1,000개짜리 sealed 세그먼트 3개는 **이 문제를 이미 3번 겪고 지나간 흔적**이다.
mutable 이 0→1000 으로 차는 동안 BM25 는 1.5ms 에서 **최대 ~230ms** 까지 연속적으로 나빠지다가,
1000 에서 sealed 되며 리셋되고 다시 차오른다.
축적되는 블로트가 아니라 **주기적으로 재발하는 톱니파**다.

→ BM25 지연은 "느리다/빠르다"가 아니라 **측정 시점이 마지막 seal 로부터 얼마나 지났는지**에 좌우된다.

> **정정 (2026-08-15).** 초판은 이 톱니파의 동력을 "매일 04:00 크롤링"으로 봤는데 **틀렸다.**
> 실제 크롤링 유입은 **하루 약 5건**이다. 아래 "쓰기는 어디서 오는가" 참고.

## 쓰기는 어디서 오는가 — 크롤링이 아니라 관리자 대량 작업

`article_analyzed_content` 에 쓰는 곳은 전부 `ArticleTermService` 다.

| 경로 | 1회 쓰기량 |
|---|---|
| 아티클별 term 추출 (`ArticleTermService:328`) | 1행 |
| 관리자 ArticleTerm 수정/삭제 (`:613`, `:639`, `:660`) | 1행 |
| **Term 삭제 / 불용어 등록 (`:399` → `rebuildForAffectedArticles`)** | **해당 term 을 가진 모든 아티클 = 수백~수천 행** |

크롤링 유입이 하루 5건이면 baseline 쓰기는 **하루 5행**이다.
그런데 인덱스 생성 이후 누적 쓰기는 **3,645행** (sealed 1,000×3 + mutable 645) —
5/day 로는 2년이 걸린다.

**즉 관측된 645 는 크롤링이 아니라 과거 대량 rebuild 스파이크의 잔해다.**
→ 임계치는 하루 유입량이 아니라 **스파이크 크기**가 결정한다.

### 미확인

`CrawlingScheduler.scheduledContentCrawling()` (05:00, 본문 200자 이하 Article 재추출) 이
매일 몇 건을 처리하는지 모른다. 수백 건이면 daily 쓰기량이 5가 아니게 되어 계산이 달라진다.
`crawling_article_processing_log` 로 확인 가능.

## 임계치 산정

프로덕션 실측 `0.23 ms/doc` 과 스파이크 2,000행 가정 기준.

| cap | 읽기 최악 | 5건/day seal 주기 | 2,000행 스파이크 시 생성 세그먼트 |
|---:|---:|---:|---:|
| 1 | 0.23ms | 매 write | **2,000** ❌ |
| 20 | 4.6ms | 4일 | 100 |
| **50** | **11.5ms** | **10일** | **40** ✅ |
| 100 | 23ms | 20일 | 20 |
| 1000 (기본) | **230ms** ❌ | 200일 | 2 |

**결론: 50.**
11.5ms 는 벡터 본검색 87ms 의 1/7 로 무시 가능하고, 스파이크가 와도 세그먼트 40개면
1~2 vCPU 머지가 감당할 범위다. **`=1` 은 스파이크 한 번에 세그먼트 수천 개가 되므로 금지.**

정상 상태에서는 mutable 이 하루 5행씩만 자라므로 **평소 BM25 는 캡과 무관하게 1~2ms** 다.
**캡은 오직 스파이크 방어용이다.**

## 기존 문서들과의 연결

- `SEARCH_TRACE_ANALYSIS.md` #3 본검색 167/157ms 와 #11 보충 169/167ms 가
  **서로 다른 쿼리인데 값이 붙어 있던 이유** — 둘 다 쿼리 내용과 무관한 동일 고정비를
  각각 한 번씩 냈다.
- `CROSS_SCORING_NEXT.md` §1-1 "64건만 점수 매기는데 왜 본검색과 시간이 같은가" —
  같은 이유. `id IN` 으로 대상을 줄여도 mutable 스캔은 안 줄어든다.
  **B안(본검색 LIMIT 300 확대로 보충 제거)은 이 문제를 먼저 잡고 재평가할 것.**
  랭킹을 안 건드리고 해결될 가능성이 높다.
- `PGSS_SEARCH_COST.md` §2 BM25 180ms / DB 예산 20% — 거의 전부 이 고정비다.

절감 추정: **직렬 구간 171ms(보충)는 통째로**, DB CPU 예산은 **약 20%**.
본검색(168ms)은 벡터(351ms)와 병렬이라 크리티컬 패스에는 없지만 CPU 예산에는 그대로 잡힌다.

## 설정 레버는 살아 있다 (2026-08-15 후속 확인)

2번의 `SHOW` 가 `-1` 을 반환한 것은 **같은 배치 안에서 SIGHUP 처리 전에 읽힌 artifact** 였다.
완전히 분리된 세션에서 다시 찍으니 **`1`** 이 반환됐다. `ALTER SYSTEM` + `pg_reload_conf()` 는
정상 동작한다.

3번에서 mutable 이 645 → **646** 으로 seal 되지 않은 것도 모순이 아니다.
로컬 검증은 전부 `REINDEX` 로 mutable 을 비운 뒤 write 하는 순서였다. 즉 확인된 것은
**"새로 생기는 mutable 세그먼트가 새 상한을 따른다"** 이지,
**"이미 차 있는 세그먼트를 소급해서 seal 한다"** 가 아니다.

로컬 단조 감소 확인: 1000→886docs/70ms, 200→200/17ms, 50→50/5ms, 1→1/0.7ms.

다만 **149.7ms → 1.5ms 자체는 전적으로 `REINDEX CONCURRENTLY` 의 효과다**
(세그먼트 5→4, mutable 소멸이 근거). 설정 레버가 실제로 상한을 거는지는
REINDEX 로 비워진 현 상태에서 write 를 한 건 넣어봐야 확정된다.

## 현재 상태

REINDEX 로 1.5ms 가 됐지만 **다음 크롤링(04:00)이 mutable 을 다시 채우면 원위치**한다.
톱니파를 없애려면 설정 레버든 크롤링 직후 REINDEX 든 **하나가 상시로 걸려 있어야 한다.**

---

# 다음 액션

## P0 — 상시 대책 확정 (다음 크롤링 04:00 전)

- [x] **설정 반영 여부 판별.** 분리된 세션에서 `SHOW` → **`1`**. 레버 살아 있음.
      (같은 배치 안의 `SHOW` 는 SIGHUP 전 값을 읽으므로 신뢰하지 말 것)

- [ ] **상한이 실제로 걸리는지 확인.** REINDEX 로 비워진 현 상태에서 write 1건:

      UPDATE article_analyzed_content SET updated_at = updated_at
      WHERE id = (SELECT min(id) FROM article_analyzed_content);

      SELECT segno, mutable, num_docs FROM paradedb.index_info('article_analyzed_content_bm25_idx');

      → mutable 의 `num_docs <= 1` 이면 확정

- [ ] **크롤링 리허설 (권장).** `=1` 은 세그먼트 폭증 위험이 있으므로 하루 기다리지 말고
      300행 규모로 미리 확인. 크롤링과 동일한 무해한 write 지만 row version 은 생긴다.

      UPDATE article_analyzed_content SET updated_at = updated_at
      WHERE id IN (SELECT id FROM article_analyzed_content ORDER BY id LIMIT 300);

      SELECT count(*) FROM paradedb.index_info('article_analyzed_content_bm25_idx');
      SELECT * FROM paradedb.merge_info('article_analyzed_content_bm25_idx');

      → 세그먼트가 수십~수백으로 튀거나 `merge_info` 가 계속 차 있으면
        1~2 vCPU 가 머지를 못 따라가는 것. `=1` 폐기하고 50 으로.

- [x] **⚠️ `= 50` 으로 내리기 (필수).** ← **2026-08-19 완료.** `pg_settings` 별도 세션 확인: `setting=50`, `source=configuration file`, `sourcefile=postgresql.auto.conf` 최악 지연이 50 × 0.23 ≈ 12ms 로 묶이고
      세그먼트 생성 빈도는 `=1` 대비 1/50.

      ALTER SYSTEM SET paradedb.global_mutable_segment_rows = 50;
      SELECT pg_reload_conf();
      -- 반드시 별도 세션에서
      SHOW paradedb.global_mutable_segment_rows;

- [ ] **대량 rebuild 직후 REINDEX.** nightly cron 은 불필요하다 (하루 5행이면 REINDEX 할 게 없다).
      맞는 지점은 `ArticleTermService:399` `rebuildForAffectedArticles`
      (Term 삭제 / 불용어 등록) **직후 1회** — 코드에 붙이거나 관리자 작업 후 수동.
      (18.6k행 기준 로컬 1.8초, 프로덕션 5~15초 예상)
- [ ] `scheduledContentCrawling()` (05:00) 이 하루 몇 건을 재추출하는지 확인
      → 수백 건이면 위 임계치 산정이 달라진다 (`crawling_article_processing_log`)
- [ ] 무엇을 택하든 **실패해도 조용히 넘어가지 않게** 로깅/알림 붙일 것

## P1 — 효과 검증

- [ ] 크롤링 다음날 오전 `index_info()` 의 mutable `num_docs` 확인 → 톱니파가 잡혔는지
- [ ] 같은 시점에 1번 EXPLAIN 재측정 (목표: 한 자릿수 ms 유지)
- [ ] Tempo 트레이스에서 `bm25-search` / `bm25-supplement` 스팬 재측정
      — `hybridTopArticles` Caffeine 캐시(10분) 때문에 **안 쓰던 검색어**로 요청할 것
- [ ] 04:00~05:00 크롤링 창 DB CPU 와 `CrawlingSchedulerRun` 소요시간 비교
      — 설정 레버를 택한 경우 유일한 대가가 여기다

## P2 — 파생 정리

- [ ] `CROSS_SCORING_NEXT.md` B안(LIMIT 300 확대) 재평가 — 보충이 3ms 면 불필요
- [ ] `SEARCH_TRACE_ANALYSIS.md` §3-B / §4 표 갱신 (원인이 "ParadeDB 가 BM25 를 먼저 평가해서"가
      아니라 mutable 세그먼트 고정비였음)
- [ ] `PGSS_SEARCH_COST.md` §2 예산표에 주석 추가
- [ ] 세그먼트 수 모니터링 상시화 — 수십 개로 늘면 `mutable_segment_rows` 를 올릴 것

## P0.5 — 2026-08-19 후속 (부하테스트 중 발견)

- [x] **운영값이 `= 1`에 머물러 있던 것을 확인.** `postgresql.auto.conf`(mtime 08-15 13:47)에
      `paradedb.global_mutable_segment_rows = '1'`이 그대로 있었다. 위 P0의 `= 50` 항목이
      체크되지 않은 채 남은 결과다.
- [x] **§5의 `= 50` 복귀가 왜 안 먹었는지 판명.** 같은 파일에
      `paradedb.global_mutuable_segment_rows = '50'`(**`mutuable` — 오타**)이 함께 들어 있었다.
      Postgres는 확장 네임스페이스의 미정의 이름을 **placeholder로 받아 저장까지 하고**,
      `SHOW`로 조회하면 `50`을 정상 반환한다. `pg_settings`에는 안 나온다.
      **→ GUC 적용 판정은 `SHOW`가 아니라 `pg_settings`의 `source`/`sourcefile`로 할 것.**
- [x] **`= 50` 설정 완료 (2026-08-19).** 중간에 `ALTER SYSTEM RESET`으로 기본값(1000)까지 갔다가
      — 이는 임계치 산정 표에서 ❌인 값이다 — `50`으로 명시 설정했다. 별도 세션 검증 결과:

      ```
      paradedb.global_mutable_segment_rows | 50 | configuration file | .../postgresql.auto.conf
      ```

      오타 이름(`global_mutuable_segment_rows`)은 `pg_settings`에서 **0행**으로 확인 —
      실재하지 않는 GUC였고 `SHOW`에만 응답하던 placeholder였음이 확증됐다.
- [ ] **P2의 "세그먼트 수 모니터링 상시화" 구현됨(배포 대기).**
      `Bm25SegmentMetricsScheduler`가 5분 간격으로 `bm25_index_segments`,
      `bm25_index_segments_mutable`, `bm25_index_docs`, `bm25_index_bytes` 게이지를 올리고,
      `load-test/scripts/collect-results.py`가 사다리 레벨별 `segs`/`mut` 열로 찍는다.
      **`mut`(mutable 문서 수)가 이 문서의 핵심 지표**다 — 톱니파를 직접 관측할 수 있다.
- [ ] 부하 사다리에서 요청당 논리 읽기가 13,601 → 2,120으로 6.4배 떨어진 구간(08-17·18 → 08-19)이
      이 톱니파로 설명되는지 확인. 그 시점 세그먼트 기록이 없어 사후 검증은 불가하고,
      위 지표가 붙은 뒤 다음 톱니에서 확인한다.
      (`load-test/results/2026-08-17-search-ladder-5-10-15-20.md` 7.4)

## P3 — 별건으로 발견된 것

- [ ] `article_analyzed_content` 의 `n_live_tup` 이 **204** (실제 18,585).
      autovacuum/autoanalyze 가 이 테이블에 사실상 안 돌고 있다.
      `clova_chunk_vectors` 도 1,573 vs 실제 154,698 로 같은 양상.
      플래너 추정치에 영향 (1번 EXPLAIN 의 `rows=84`)
- [ ] 테이블별 autovacuum 오버라이드 검토 → `DB_LOAD_REDUCTION.md` §4 참고
