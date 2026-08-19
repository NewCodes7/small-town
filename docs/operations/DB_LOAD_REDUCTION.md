# DB 부하 저감 가이드 (검색/RAG API)

검색 API·RAG API 트래픽 증가 시 병목이 가장 먼저 발생할 지점은 DB(2vCPU/1GB 별도 서버). 스케일 업/아웃 전에 시도해볼 수 있는 DB 자체 부하 저감 방법을 정리한다.

## 현재 이미 적용된 조치

- HikariCP `maximum-pool-size=5` / `minimum-idle=5` — 1GB RAM DB에 맞춰 타이트하게 제한 (`application-prod.properties`)
- `spring.datasource.hikari.connection-init-sql=SET hnsw.ef_search=250; SET statement_timeout='5000';` — 커넥션당 쿼리 타임아웃 5초로 방어
- Caffeine 다계층 캐싱 (`CacheConfig.java`): `hybridTopArticles`/`ragTopArticles` 10분, `aiSummary`/`ragAnswer` 1시간, `ragPreprocess` 6시간
- 하이브리드 검색 트랜잭션 스코프 축소 (`ArticleSearchService`, 커밋 `ad64c7a`) — BM25 실행(Phase A) → Vector future 대기(트랜잭션 밖) → cross-scoring(Phase B) → 후처리(Phase C)로 분리해 Vector 대기 구간(최대 5초) 동안 커넥션 미점유
- Vector 검색 2단계 구조 (`embedding_binary` HNSW 후보 필터링 → `embedding_normalized` halfvec 리랭킹)로 스캔 비용 자체를 줄임
- `SearchPrewarmScheduler`로 인기 검색어 사전 워밍

## 확인이 필요한 영역 (다음 단계)

### 1. DB 서버 현재 파라미터 확인

```sql
SELECT name, setting, unit, source
FROM pg_settings
WHERE name IN (
  'shared_buffers', 'effective_cache_size', 'work_mem', 'maintenance_work_mem',
  'max_connections', 'max_parallel_workers_per_gather', 'max_worker_processes',
  'max_parallel_workers', 'wal_buffers', 'checkpoint_completion_target'
)
ORDER BY name;
```

`source`가 `default`면 아직 튜닝 안 된 상태. 1GB RAM 기준 목표치:

| 파라미터 | 권장값 (1GB RAM 기준) | 비고 |
|----------|----------------------|------|
| `shared_buffers` | 200~256MB (RAM의 ~25%) | 너무 크면 OS 캐시와 이중으로 메모리 잡아먹음 |
| `effective_cache_size` | 600~700MB | 실제 할당 아님, 플래너 힌트용 |
| `work_mem` | 4~8MB | **커넥션 풀(5) × 병렬 정렬/해시 개수**만큼 곱연산되므로 낮게 유지 — 여기 올리려면 풀 크기와 세트로 재검토 |
| `maintenance_work_mem` | 64MB | VACUUM/인덱스 빌드용, 평소엔 영향 적음 |
| `max_parallel_workers_per_gather` | 0~1 | 2vCPU에서 병렬 워커 여러 개는 컨텍스트 스위칭 비용이 더 클 수 있음 |

### 2. 버퍼 캐시 히트율

```sql
SELECT
  datname,
  round(100.0 * blks_hit / NULLIF(blks_hit + blks_read, 0), 2) AS cache_hit_ratio,
  blks_hit, blks_read
FROM pg_stat_database
WHERE datname = current_database();
```

99% 밑으로 자주 떨어지면 `shared_buffers` 부족 → 디스크 I/O 발생 중. 1GB RAM에서 흔한 증상.

### 3. 커넥션/대기 상태 (풀 5개가 병목인지)

```sql
SELECT state, count(*)
FROM pg_stat_activity
WHERE datname = current_database()
GROUP BY state;

-- 락 대기 등 오래 걸리는 쿼리
SELECT pid, state, wait_event_type, wait_event, query, now() - query_start AS duration
FROM pg_stat_activity
WHERE datname = current_database() AND state != 'idle'
ORDER BY duration DESC;
```

### 4. autovacuum 동작 이력 / 테이블별 오버라이드

크롤링으로 쓰기가 꾸준히 발생하는 테이블(`article_chunk`, `article_analyzed_content`, `chunk_vector`)은 인덱스 블로트가 검색 성능에 직접 영향을 줌.

```sql
SELECT relname, last_autovacuum, last_autoanalyze, n_dead_tup, n_live_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS dead_pct
FROM pg_stat_user_tables
WHERE relname IN ('article_chunk', 'article_analyzed_content', 'chunk_vector', 'chunk_content', 'article')
ORDER BY dead_pct DESC NULLS LAST;

-- 테이블별 개별 오버라이드 (없으면 전역 설정 그대로 적용)
SELECT c.relname, c.reloptions
FROM pg_class c
WHERE c.relname IN ('article_chunk', 'article_analyzed_content', 'chunk_vector')
  AND c.reloptions IS NOT NULL;
```

`dead_pct`가 높은 테이블은 autovacuum 임계치(`autovacuum_vacuum_scale_factor` 등)를 테이블별로 낮춰서 더 자주 돌게 하는 것을 검토.

### 5. 테이블/인덱스 크기 (bloat 감 잡기)

```sql
SELECT relname AS table_name,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
       pg_size_pretty(pg_relation_size(relid)) AS table_size,
       pg_size_pretty(pg_total_relation_size(relid) - pg_relation_size(relid)) AS index_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 15;
```

### 6. OS 레벨 메모리 (DB 서버에서 직접, psql 아님)

```bash
free -h
cat /proc/meminfo | grep -i huge   # Transparent Huge Pages 설정도 pgvector 성능에 영향 가능
```

### 7. HNSW/BM25 인덱스 실제 사용 여부

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM article
ORDER BY embedding_binary <~> '<쿼리벡터>'::bit(1024)
LIMIT 200;
```

BM25 쪽도 실제 검색 쿼리로 떠서 `Bitmap Heap Scan`/`Index Scan`이 잡히는지, `Seq Scan`이 섞여있는지 확인.

## 우선순위 판단 기준

1. **`source=default`** (파라미터 미튜닝) + **캐시 히트율 낮음** → `shared_buffers`/`effective_cache_size` 조정이 최우선. 스케일업보다 먼저 시도.
2. **`dead_pct` 높음** → autovacuum 튜닝이 검색 성능에 직결. 인덱스 재구성(`REINDEX CONCURRENTLY`) 필요 여부도 함께 확인.
3. **커넥션 풀 대기가 실제로 발생** (pg_stat_activity에 대기 쿼리 다수) → 풀 크기 확장은 `work_mem` 재계산 없이는 OOM 위험 — 반드시 세트로 조정.
4. 위 조치로도 부족하면 그때 스케일 업/아웃 검토 (read replica, 캐시 계층 확장 등).

## 참고: 캐시 확장 여지

- 롱테일 RAG 질의는 캐시 히트율이 낮아 매번 DB를 침 — 유사 질의 정규화(캐시 키 통합)나 `SearchPrewarmScheduler` 대상 확장이 풀 크기 확장보다 안전한 완화책.

## 결과 

small_town=# SELECT name, setting, unit, source
FROM pg_settings
WHERE name IN (
  'shared_buffers', 'effective_cache_size', 'work_mem', 'maintenance_work_mem',
  'max_connections', 'max_parallel_workers_per_gather', 'max_worker_processes',
  'max_parallel_workers', 'wal_buffers', 'checkpoint_completion_target'
)
ORDER BY name;
              name               | setting | unit |       source       
---------------------------------+---------+------+--------------------
 checkpoint_completion_target    | 0.9     |      | default
 effective_cache_size            | 65536   | 8kB  | command line
 maintenance_work_mem            | 65536   | kB   | command line
 max_connections                 | 100     |      | configuration file
 max_parallel_workers            | 8       |      | default
 max_parallel_workers_per_gather | 2       |      | default
 max_worker_processes            | 8       |      | default
 shared_buffers                  | 32768   | 8kB  | command line
 wal_buffers                     | 1024    | 8kB  | default
 work_mem                        | 16384   | kB   | command line
(10 rows)

small_town=# ^M^C
small_town=# SELECT
  datname,
  round(100.0 * blks_hit / NULLIF(blks_hit + blks_read, 0), 2) AS cache_hit_ratio,
  blks_hit, blks_read
FROM pg_stat_database
WHERE datname = current_database();
  datname   | cache_hit_ratio |  blks_hit   | blks_read 
------------+-----------------+-------------+-----------
 small_town |           98.65 | 13162878666 | 179586150
(1 row)

small_town=# SELECT state, count(*)
FROM pg_stat_activity
WHERE datname = current_database()
GROUP BY state;

-- 락 대기 등 오래 걸리는 쿼리
SELECT pid, state, wait_event_type, wait_event, query, now() - query_start AS duration
FROM pg_stat_activity
WHERE datname = current_database() AND state != 'idle'
ORDER BY duration DESC;
 state  | count 
--------+-------
 active |     1
 idle   |    13
(2 rows)

   pid   | state  | wait_event_type | wait_event |                                         query                                          | duration 
---------+--------+-----------------+------------+----------------------------------------------------------------------------------------+----------
 2357995 | active |                 |            | SELECT pid, state, wait_event_type, wait_event, query, now() - query_start AS duration+| 00:00:00
         |        |                 |            | FROM pg_stat_activity                                                                 +| 
         |        |                 |            | WHERE datname = current_database() AND state != 'idle'                                +| 
         |        |                 |            | ORDER BY duration DESC;                                                                | 
(1 row)

small_town=# SELECT relname, last_autovacuum, last_autoanalyze, n_dead_tup, n_live_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS dead_pct
FROM pg_stat_user_tables
WHERE relname IN ('article_chunk', 'article_analyzed_content', 'chunk_vector', 'chunk_content', 'article')
ORDER BY dead_pct DESC NULLS LAST;

-- 테이블별 개별 오버라이드 (없으면 전역 설정 그대로 적용)
SELECT c.relname, c.reloptions
FROM pg_class c
WHERE c.relname IN ('article_chunk', 'article_analyzed_content', 'chunk_vector')
  AND c.reloptions IS NOT NULL;
         relname          |        last_autovacuum        |       last_autoanalyze        | n_dead_tup | n_live_tup | dead_pct 
--------------------------+-------------------------------+-------------------------------+------------+------------+----------
 article                  | 2026-07-23 05:41:45.483013+00 | 2026-07-22 21:02:41.452987+00 |        208 |      18711 |     1.10
 article_analyzed_content |                               | 2026-05-18 14:13:47.842144+00 |          0 |      18194 |     0.00
 article_chunk            |                               |                               |          0 |          0 |         
(3 rows)

 relname | reloptions 
---------+------------
(0 rows)

small_town=# SELECT relname AS table_name,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
       pg_size_pretty(pg_relation_size(relid)) AS table_size,
       pg_size_pretty(pg_total_relation_size(relid) - pg_relation_size(relid)) AS index_size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC
LIMIT 15;
           table_name            | total_size | table_size | index_size 
---------------------------------+------------+------------+------------
 article_term                    | 836 MB     | 340 MB     | 496 MB
 clova_chunk_vectors             | 417 MB     | 8960 kB    | 408 MB
 clova_chunk_contents            | 262 MB     | 245 MB     | 17 MB
 article                         | 142 MB     | 13 MB      | 128 MB
 clova_article_chunk             | 107 MB     | 37 MB      | 70 MB
 term                            | 68 MB      | 42 MB      | 26 MB
 article_analyzed_content        | 51 MB      | 18 MB      | 33 MB
 hacker_news_comment             | 24 MB      | 20 MB      | 3392 kB
 hacker_news_item                | 9536 kB    | 4088 kB    | 5448 kB
 term_autocomplete_mv            | 7208 kB    | 7168 kB    | 40 kB
 view_log                        | 6704 kB    | 5088 kB    | 1616 kB
 article_search_index            | 5312 kB    | 1424 kB    | 3888 kB
 article_summary                 | 3888 kB    | 3240 kB    | 648 kB
 video                           | 3192 kB    | 2560 kB    | 632 kB
 crawling_article_processing_log | 2824 kB    | 2488 kB    | 336 kB
(15 rows)

small_town=# EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM article
ORDER BY embedding_binary <~> '<쿼리벡터>'::bit(1024)
LIMIT 200;
ERROR:  column "embedding_binary" does not exist
LINE 3: ORDER BY embedding_binary <~> '<쿼리벡터>'::bit(1024)
                 ^
small_town=# ^C
small_town=# ^C
small_town=# exit
free -h
cat /proc/meminfo | grep -i huge   # Transparent Huge Pages 설정도 pgvector 성능에 영향 가능
               total        used        free      shared  buff/cache   available
Mem:           918Mi       822Mi        80Mi       278Mi       449Mi        95Mi
Swap:             0B          0B          0B
AnonHugePages:         0 kB
ShmemHugePages:        0 kB
FileHugePages:         0 kB
HugePages_Total:       0
HugePages_Free:        0
HugePages_Rsvd:        0
HugePages_Surp:        0
Hugepagesize:       2048 kB
Hugetlb:               0 kB
```

## 결과 분석 (2026-07-23)

### 요약

메모리 관련 파라미터(`shared_buffers`/`effective_cache_size`/`maintenance_work_mem`)는 이미 1GB RAM 기준 적정선으로 튜닝되어 있음. 반면 **RAM 여유가 위험 수준(`available=95Mi`)인데 swap이 0B이고, `work_mem`이 이 상황 대비 과하게 높게(16MB) 잡혀 있는 조합이 가장 시급한 리스크** — 트래픽 스파이크 시 "느려짐"이 아니라 OOM killer에 의한 postgres 백엔드 강제 종료로 이어질 수 있음.

### 1. 파라미터 — 대부분 이미 적정선

| 파라미터 | 현재값 | source | 판정 |
|----------|--------|--------|------|
| `shared_buffers` | 256MB | command line | ✅ 1GB RAM 권장치(25%) 부합, 조정 불필요 |
| `effective_cache_size` | 512MB | command line | ✅ 적정 |
| `maintenance_work_mem` | 64MB | command line | ✅ 적정 |
| `work_mem` | 16MB | command line | ⚠️ 아래 참고 — 실측 전엔 낮추지 말 것 |
| `max_connections` | 100 | configuration file | ⚠️ 실사용(14개) 대비 과잉 |
| `max_parallel_workers_per_gather` | 2 | default | ⚠️ 2vCPU에 부적합, 손 안 댐 |
| `wal_buffers` | 8MB | default | ✅ shared_buffers 대비 자동 계산값, 적정 |

### 2. 가장 시급한 리스크 — swap 0B + available 95Mi

```
Mem: total 918Mi / free 80Mi / available 95Mi / buff/cache 449Mi
Swap: 0B / 0B
```

스왑이 전혀 없는 상태에서 available이 95Mi밖에 남지 않음. 현재는 트래픽이 낮아(`pg_stat_activity`상 active=1, idle=13) 문제가 드러나지 않지만, 동시 쿼리가 늘어나는 시나리오에서는 디스크 I/O 증가로 인한 지연이 아니라 **OOM killer가 postgres 백엔드 프로세스를 강제 종료시키는 방식으로 장애**가 발생할 수 있음. 쿼리 튜닝보다 우선순위가 높은 항목.

### 3. work_mem=16MB — 위 리스크와 직결, 단 실측 없이 낮추면 안 됨

`max_parallel_workers_per_gather=2`가 default로 켜져 있으면 쿼리 하나가 leader + worker 2개 = **work_mem을 최대 3배(48MB)까지 순간 사용** 가능. 여기에 BM25 스코어 정렬, 벡터 재랭킹처럼 정렬/해시가 들어가는 쿼리가 여러 커넥션에서 동시에 돌면 available 95Mi를 순식간에 소진할 수 있는 조합 — 여기까지는 메모리 리스크 관점.

다만 `work_mem`을 무작정 낮추면 정렬/해시가 메모리 대신 디스크 temp file로 스필(spill)해서 **디스크 I/O가 늘어나는 역효과**가 날 수 있음 (클라우드 볼륨이라 디스크 I/O 자체도 비용/지연 있는 자원). 실제로 지금 정렬/해시가 얼마나 메모리를 쓰는지 확인하지 않고 값을 찍는 건 위험 — 아래 순서로 실측 후 값을 정한다.

**① 전역 카운터로 스필 여부부터 확인 (설정 변경 불필요, 즉시 실행 가능)**
```sql
SELECT datname, temp_files, temp_bytes
FROM pg_stat_database
WHERE datname = current_database();
```
`temp_files=0`이면 지금까지 work_mem을 초과해 디스크로 스필한 적이 없다는 뜻 — 낮춰도 안전하지만 당장 낮출 필요(메모리 절약 외 이득)도 크지 않음. 값이 크면 이미 스필이 나고 있다는 뜻이므로 **낮추면 안 되고 오히려 상향을 검토**해야 함.

```
small_town=# SELECT datname, temp_files, temp_bytes
FROM pg_stat_database
WHERE datname = current_database();
  datname   | temp_files | temp_bytes 
------------+------------+------------
 small_town |        101 |   95920792
(1 row)
```

**② 실제 검색 쿼리로 스필 여부 직접 확인**
BM25 정렬 쿼리, 벡터 재랭킹 쿼리를 `EXPLAIN (ANALYZE, BUFFERS)`로 떠서 `Sort`/`Hash` 노드를 확인. 아래 두 쿼리는 `ArticleSearchService`/`VectorSearchService`가 실제로 실행하는 하이브리드 검색 쿼리를 그대로 재구성한 것 (native `@Query` 원문 기준).

검색어(`paradedb.parse(...)` 안의 텍스트)는 실제 검색어로 바꿔서 실행. 인라인 한글 주석이 붙은 SQL을 터미널에 직접 붙여넣으면 멀티바이트 문자 경계에서 paste가 깨질 수 있으므로(붙여넣기 시 `syntax error at or near` + 깨진 첫 줄이 보이면 이 증상), 아래처럼 파일로 저장해서 `psql -f`로 실행하는 걸 권장.

*BM25 쿼리 — `ArticleRepository.searchByBM25` (`ArticleSearchService.executeBM25Search`가 필터 없을 때 호출)*
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id,
       paradedb.score(id) AS bm25_score,
       published_at
FROM article_analyzed_content
WHERE article_analyzed_content @@@ paradedb.parse('스프링 부트')
ORDER BY bm25_score DESC
LIMIT 100;
```

```
                                                                            QUERY PLAN                                                                             
-------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Limit  (cost=1010.02..1021.69 rows=100 width=20) (actual time=168.294..175.278 rows=100 loops=1)
   Buffers: shared hit=1569 read=3
   ->  Gather Merge  (cost=1010.02..1034.40 rows=209 width=20) (actual time=168.292..175.254 rows=100 loops=1)
         Workers Planned: 2
         Workers Launched: 2
         Buffers: shared hit=1569 read=3
         ->  Parallel Custom Scan (ParadeDB Scan) on article_analyzed_content  (cost=10.00..10.25 rows=33 width=20) (actual time=140.748..141.636 rows=33 loops=3)
               Table: article_analyzed_content
               Index: article_analyzed_content_bm25_idx
               Segment Count: 5
               Heap Fetches: 100
               Exec Method: TopNScanExecState
               Scores: true
                  TopN Order By: pdb.score() desc
                  TopN Limit: 100
                  Queries: 1
               Tantivy Query: {"with_index":{"query":{"parse":{"query_string":"스프링 부트","lenient":null,"conjunction_mode":null}}}}
               Buffers: shared hit=1554 read=3
 Planning:
   Buffers: shared hit=188 read=1
 Planning Time: 4.199 ms
 Execution Time: 175.670 ms
(22 rows)
```

```bash
# 파일로 저장 후 실행하면 붙여넣기 깨짐 없이 안전하게 돎
psql "$DB_URL" -f bm25_explain.sql
```

*Vector 2단계 쿼리 — `ArticleChunkRepository.findArticlesByTwoStageSearch` (`VectorSearchService`가 호출, 상수는 `VectorSearchService`: `DEFAULT_CANDIDATE_LIMIT=200`, `DEFAULT_TOP_K=3`, `DEFAULT_SIMILARITY_THRESHOLD=0.52`, `DEFAULT_MAX_RESULTS=100`)*

`:queryEmbedding`/`:queryBinary` 자리는 실제로는 Clova 임베딩 API 응답(1024차원)이 들어가는 자리라 DB에서 직접 만들 수 없음. 아래 값은 스캔/정렬 비용만 실측하기 위한 **합성(랜덤 시드 고정) 1024차원 벡터**이며, `embedding_binary`는 실제 코드의 이진 양자화 규칙(`BitVectorType.fromFloatArray` — 양수 → 1, 음수 → 0, `global/config/BitVectorType.java`)을 그대로 적용해 벡터 값과 부호가 일치하도록 만들어서 인덱스 스캔 경로가 실측과 동일하게 타도록 했음. 의미상 관련도는 없으므로 리콜/스코어 값 자체는 참고하지 말고 **plan 모양(Index Scan 여부)과 Sort/WindowAgg 비용**만 볼 것. 실제 데이터로 바꾸고 싶으면 `SELECT embedding_normalized, embedding_binary FROM clova_chunk_vectors LIMIT 1;` 결과로 교체.

```sql
EXPLAIN (ANALYZE, BUFFERS)
WITH query_vec AS (
    SELECT l2_normalize(CAST('[0.083656,-0.284994,-0.134982,-0.166074,0.141883,0.10602,0.235308,-0.247837,-0.046847,-0.282122,-0.168817,0.003213,-0.284078,-0.180697,0.089931,0.026965,-0.167736,0.053559,0.185658,-0.296101,0.183492,0.118884,-0.09585,-0.206712,0.274328,-0.098043,-0.244352,-0.24197,0.208497,0.062236,0.184277,0.137839,0.021737,0.283869,-0.072879,0.031224,0.197643,0.071112,0.217024,0.046411,0.122743,-0.272505,-0.163261,-0.126367,-0.252125,-0.160325,-0.239399,-0.133216,0.081411,-0.081101,-0.077891,-0.174296,-0.139813,0.261993,0.088821,0.065479,-0.197317,0.137476,-0.201959,-0.072327,0.293714,0.084,0.03417,0.110769,0.205711,0.1656,-0.162571,-0.28074,-0.110728,-0.139355,-0.17341,0.265746,0.225821,-0.111193,0.093263,-0.062621,0.248729,-0.024689,-0.141072,-0.152023,0.036821,-0.142355,0.050752,0.238694,-0.06036,-0.168408,0.298523,0.005716,-0.245454,-0.27173,-0.234211,0.076468,0.175248,-0.046704,-0.261883,-0.071028,0.297673,0.017469,0.282647,0.216468,-0.293111,0.132433,0.109026,0.022182,-0.139905,0.084577,-0.233069,-0.039141,-0.027766,0.27229,0.225512,-0.141967,0.000352,-0.192809,0.247577,0.222311,-0.120933,0.08337,0.065382,-0.208296,0.157506,0.023627,0.167176,0.018212,-0.299657,-0.105506,-0.288314,0.257459,0.227233,0.198999,-0.115492,-0.265245,0.226806,0.26817,-0.248608,-0.008406,-0.258472,0.156361,0.159501,-0.222965,-0.014831,0.029882,-0.140966,0.22346,-0.046117,-0.172921,0.023578,0.137959,-0.179309,-0.11297,0.29709,0.089927,-0.03714,0.010546,-0.227397,-0.165182,-0.097149,0.052985,-0.161931,-0.16787,-0.257404,0.078662,-0.162635,0.243252,0.215781,-0.257486,-0.157197,0.101387,-0.171458,-0.220613,0.261309,0.042626,-0.016397,0.170772,0.184498,-0.185754,-0.241842,-0.041369,-0.045853,-0.019785,0.137446,0.104019,0.290499,-0.240949,-0.058427,-0.096418,0.217004,-0.150806,-0.185875,-0.030832,-0.046871,-0.132873,-0.150116,0.253959,-0.034122,0.216809,0.030195,-0.269647,0.299569,0.201617,0.281398,0.25582,0.209217,-0.200213,-0.008615,-0.171752,-0.059376,-0.264819,-0.072616,0.291185,-0.140878,0.170442,-0.026995,-0.046196,0.274391,0.297254,0.033461,0.131045,-0.207122,-0.121975,0.281226,0.047508,0.025317,0.148785,-0.265701,0.050507,0.00171,0.211632,-0.20554,0.276467,-0.251933,-0.188505,0.057021,0.105128,-0.158878,-0.228068,0.234172,-0.152271,0.056711,0.071629,-0.048465,0.050203,0.01367,0.260824,-0.177444,0.129715,-0.156788,-0.062528,0.103014,-0.120002,-0.110294,0.151119,-0.256474,-0.025029,0.299073,0.297658,-0.256044,-0.172107,-0.14088,0.259956,0.228519,0.227562,-0.078284,-0.205352,0.200247,0.122124,0.067007,0.29234,0.092386,-0.295306,0.190262,-0.120373,0.098033,0.263358,-0.219425,-0.230743,-0.235778,0.031934,-0.136591,0.062898,0.130567,-0.177842,0.080543,-0.14161,-0.006881,0.243202,0.207662,-0.244621,-0.045855,-0.133992,-0.297873,0.162672,0.082268,-0.142827,0.144739,0.031008,-0.043388,-0.294198,-0.254854,0.229864,0.242357,0.027354,0.200757,0.049506,-0.211144,-0.223533,-0.115045,0.239389,0.177673,0.216422,0.239355,-0.173954,-0.150282,-0.238324,0.16807,0.230481,-0.056174,0.072397,-0.207268,0.257929,0.218763,0.285724,0.186463,0.22885,-0.285128,0.141939,-0.100689,0.25849,0.181341,0.218438,0.18645,-0.139917,0.172425,-0.235143,0.2233,0.215156,-0.16654,0.189952,-0.023818,-0.116885,0.177207,-0.163443,-0.285801,-0.184122,-0.103043,0.218612,0.280133,-0.132525,0.084889,-0.060193,0.28869,0.021729,0.263542,-0.230795,0.28224,-0.192859,0.277521,-0.14072,-0.234958,-0.039262,0.137127,-0.111794,0.063725,0.006854,-0.068883,0.045953,-0.147166,0.125271,-0.298985,0.255345,0.023071,0.131658,0.14517,0.102377,-0.081467,-0.258016,0.098543,-0.10188,-0.111651,0.208809,0.131853,-0.119807,-0.114429,-0.054964,-0.05856,-0.122607,-0.223627,-0.047732,0.264218,0.106391,0.241683,0.069309,-0.11943,0.028762,-0.299756,-0.127852,-0.042067,0.047991,0.092823,-0.021007,-0.034704,-0.171779,-0.016088,0.240708,0.177615,-0.198185,-0.249123,0.009271,0.079765,-0.098887,0.191054,0.150683,0.103677,-0.165216,-0.180522,-0.285345,-0.153094,-0.014918,0.209843,-0.256303,-0.051335,0.077859,-0.183339,0.117813,-0.003374,-0.153609,0.093635,-0.296673,0.150579,0.162028,-0.236048,-0.044912,-0.194468,0.27478,0.010775,-0.269869,-0.150481,0.209002,-0.026123,0.18085,0.100547,0.292735,0.057271,0.270024,0.234856,0.067591,0.131564,0.002867,0.198342,0.028723,0.238325,0.146193,-0.015195,-0.144485,-0.151656,0.082597,0.159488,0.01278,0.076049,-0.135242,-0.25351,-0.128563,-0.136971,-0.108174,0.024091,-0.216976,-0.161243,0.11637,0.123851,-0.261463,-0.05544,0.025567,-0.050535,-0.175899,-0.047914,0.242903,0.050448,0.117314,0.214039,0.159357,-0.071771,-0.296462,-0.088945,0.152085,0.212069,0.272058,-0.048587,0.148509,0.027679,0.061952,-0.167677,-0.168347,-0.038498,-0.282585,-0.098322,0.107485,-0.05741,-0.200973,-0.019566,-0.223423,0.073354,-0.28382,-0.063588,0.038635,-0.283739,0.08565,-0.21858,-0.022981,-0.269829,-0.072538,-0.173004,-0.103893,0.156738,-0.072524,0.151206,0.199155,-0.148637,-0.250856,-0.28837,0.023651,0.299945,-0.090024,0.090086,0.16874,0.091053,0.15254,0.269767,-0.180384,-0.287772,-0.208571,-0.224267,0.101675,0.038382,-0.169221,0.119679,0.160139,-0.199327,0.064348,0.148755,-0.23128,0.191581,0.278832,-0.235141,-0.284593,-0.112826,0.106408,0.274904,-0.062007,0.129009,-0.254402,0.114369,0.076345,-0.238859,0.163489,0.210176,0.060247,-0.227367,0.290307,0.169581,-0.091678,-0.042973,-0.077657,0.003576,-0.095261,0.209745,0.193399,-0.236677,0.276473,0.081351,0.197224,0.124385,-0.038708,0.140277,0.279284,-0.137951,0.18492,0.022904,-0.009901,-0.038655,0.138616,-0.138963,0.211028,0.198439,-0.248002,0.228979,-0.153682,-0.021175,0.066199,-0.072606,-0.28278,0.210572,-0.190896,-0.172728,0.178699,-0.095797,0.228192,0.12071,-0.134239,-0.293909,0.268838,-0.248632,0.132045,-0.006853,0.154899,0.114366,0.087542,-0.005507,0.17576,-0.244168,-0.167042,0.115072,-0.116276,0.048933,-0.016044,0.018553,-0.044698,0.147561,-0.101525,0.121713,-0.13745,-0.149158,-0.227606,-0.184449,-0.228267,0.021518,0.157314,-0.18891,-0.170169,-0.009481,0.134751,0.285964,0.014782,-0.130201,-0.239684,-0.183529,-0.16351,-0.192335,-0.291511,0.020481,-0.135413,0.284577,0.032015,0.11845,-0.224232,0.221077,-0.005473,0.223632,0.044439,-0.018362,-0.035719,-0.189382,-0.269174,0.264638,-0.013362,0.193269,-0.059576,-0.255551,0.077667,-0.267835,-0.210481,0.037704,-0.117699,0.296351,-0.228929,0.158666,0.063791,0.174444,-0.164588,0.013544,-0.029691,-0.034367,0.2161,0.294019,-0.116772,0.072616,0.065779,0.144054,0.268554,-0.175327,-0.173385,0.096257,-0.205766,-0.195712,-0.254961,-0.298395,-0.029698,0.056287,-0.125244,-0.161114,0.124173,0.121793,-0.027581,0.112431,0.254347,0.172697,0.075035,0.09671,0.260201,-0.044917,0.026737,0.088581,0.245047,0.195979,-0.257154,-0.200446,-0.115433,0.149375,0.041524,-0.126834,-0.225388,0.113207,0.11984,0.265606,0.000283,-0.003723,-0.251735,-0.276084,-0.040783,-0.106607,-0.149779,-0.245204,0.277147,0.201575,0.045119,0.270472,0.299743,0.103369,-0.138293,-0.275861,0.153761,-0.0177,0.090906,0.249644,-0.191107,0.051198,0.080871,-0.004965,-0.245255,-0.091223,-0.100015,0.10208,0.21464,-0.102118,0.116204,-0.127069,0.267116,0.18814,0.030058,-0.027104,-0.11129,-0.106036,0.282111,-0.057495,0.008758,0.292872,0.094596,0.025556,-0.052051,-0.18745,-0.082932,0.153866,0.075245,0.155994,-0.177865,0.029532,0.256604,-0.03713,0.11895,-0.227144,0.283888,0.065323,-0.156422,-0.204973,0.030503,0.031351,-0.244074,0.295354,0.247758,-0.023131,-0.22952,0.199286,-0.000975,0.129962,0.005323,-0.135945,0.200834,0.288147,-0.153761,0.030759,-0.069848,0.253121,0.004945,0.227596,0.218416,-0.134252,0.174004,-0.051035,0.260549,0.004643,0.19233,-0.130297,-0.120866,0.052163,0.299341,-0.006216,-0.210843,0.023148,-0.092926,0.03115,0.026058,-0.026793,-0.106934,-0.186809,0.118499,0.043079,-0.159863,0.165327,-0.273812,0.146823,0.123137,0.186845,-0.068353,0.098213,0.192449,0.288491,-0.002803,-0.277788,0.001375,0.054108,0.22182,0.224514,-0.035816,0.015571,-0.025843,0.133466,-0.054013,0.092869,-0.207383,-0.018306,0.281522,-0.096863,0.115623,0.089902,0.211059,0.211405,0.215605,-0.071994,-0.110003,0.13123,0.155641,0.22343,-0.278461,-0.258948,0.078697,0.252557,0.298456,0.14806,-0.039617,-0.240934,0.080249,0.223548,-0.033793,0.116401,0.242054,-0.272405,0.177686,-0.123979,-0.075095,-0.212658,0.0187,0.039557,0.175512,-0.19801,-0.252619,0.222504,0.071826,-0.155502,0.247697,-0.214129,-0.02331,-0.147614,-0.146804,-0.294362,0.18278,0.240726,0.106567,-0.205215,-0.034962,-0.092661,0.052543,0.083363,-0.045415,-0.149941,0.207182,-0.18047,-0.069184,-0.010075,-0.157677,0.043154,0.044887,0.295615,-0.122862,0.286767,0.094938,-0.135312,0.039557,0.11148,0.146801,-0.270573,0.063844,-0.001964,0.242493,-0.128284,0.179316,0.064239,-0.088607,0.081971,0.072535,0.106659,0.132557,0.095509,0.203002,0.076949,0.242042,0.087804,-0.11464,-0.035506,0.047744,0.139416,-0.24592,-0.122934,0.148489,-0.194616,-0.220704,0.023645,0.282894,0.018511,0.248092,0.198284,-0.145818,0.194814,-0.010891,0.183893,0.147936,-0.096771,-0.230898,0.277736,-0.215546,0.2799,0.216084,0.13453,0.287965,0.280362,0.182753,-0.080535,0.174409,-0.291649,0.021943,-0.027128,0.103697,0.103404,0.050736,0.19345,0.264175,-0.234992,-0.159707,-0.284985,0.230541,0.036844,0.249154,-0.16718,-0.26207,0.194313,0.245633,-0.118686,-0.055022,-0.216134,0.267757,-0.117381,-0.004425,-0.241685,0.232356,-0.218602,-0.027814,0.102292,0.145884,0.267584,-0.048524,0.145361,-0.207286,-0.051069,-0.240587,-0.006392,-0.05513,0.270913,-0.28037,-0.077682,-0.03397,0.270333,0.21327,-0.240387,0.111408,0.02668,0.286706,-0.084796,-0.061116,-0.186115,-0.226704,0.20882,-0.02717,0.097661,0.085023,0.058288,-0.287186,0.172077,-0.153859,-0.224446,0.038747,-0.258834,0.159094,-0.175706,-0.170429,0.221817,-0.102864]' AS halfvec)) AS vec
),
candidates AS (
    SELECT
        cac.article_id,
        ccv.embedding_normalized
    FROM clova_article_chunk cac
    JOIN article a ON cac.article_id = a.id
    JOIN clova_chunk_vectors ccv ON ccv.id = cac.id
    WHERE a.deleted_at IS NULL
      AND cac.embedding_binary IS NOT NULL
    ORDER BY cac.embedding_binary <~> CAST('1000111000010011011011001000111111011111100000001000011101001111110000011010100010110011000110001111011101000110101101101111000111001100011001010011001101000100010110010011011000001110001000000101101111100000010100111100111101110100110010110111010010010011000111001111101011000101101001100001101100011111000111100011010111110101111010110100100001101011101010001011010101111100100110000000111101000110000110011011100000100101001011000110010111111111111100011110000010011001000111110001110111000001000010010100000010110001101111100001101101101100011010110111011000101101111011011001011010010010010110010101110100101010101000001100011100000010111010110000101001001010111010011011110010000010011011111101111000110011110000000111111001011011000011010111000101111000111011010110011011001011011010111101011100110010110001101011101110011110101010010111110011100111100110110100011100110100000111000110010000111011011101010110111111111001100100111110101100101111110101011111000111001100010001001110100000100011011100001011101001010010' AS bit(1024))
    LIMIT 200
)
SELECT article_id, AVG(similarity) AS avg_similarity
FROM (
    SELECT
        c.article_id,
        -(c.embedding_normalized <#> q.vec) AS similarity,
        ROW_NUMBER() OVER (
            PARTITION BY c.article_id
            ORDER BY c.embedding_normalized <#> q.vec
        ) AS rn
    FROM candidates c, query_vec q
) ranked
WHERE rn <= 3
  AND similarity >= 0.52
GROUP BY article_id
ORDER BY avg_similarity DESC
LIMIT 100;
```

```
 Limit  (cost=185.33..185.38 rows=20 width=16) (actual time=39.969..39.973 rows=0 loops=1)
   Buffers: shared hit=1774 read=48
   ->  Sort  (cost=185.33..185.38 rows=20 width=16) (actual time=39.969..39.972 rows=0 loops=1)
         Sort Key: (avg(ranked.similarity)) DESC
         Sort Method: quicksort  Memory: 25kB
         Buffers: shared hit=1774 read=48
         ->  GroupAggregate  (cost=176.33..184.90 rows=20 width=16) (actual time=39.966..39.969 rows=0 loops=1)
               Group Key: ranked.article_id
               Buffers: shared hit=1774 read=48
               ->  Subquery Scan on ranked  (cost=176.33..184.31 rows=67 width=16) (actual time=39.964..39.967 rows=0 loops=1)
                     Filter: (ranked.similarity >= '0.52'::double precision)
                     Rows Removed by Filter: 40
                     Buffers: shared hit=1774 read=48
                     ->  WindowAgg  (cost=176.33..181.81 rows=200 width=32) (actual time=39.925..39.960 rows=40 loops=1)
                           Run Condition: (row_number() OVER (?) <= 3)
                           Buffers: shared hit=1774 read=48
                           ->  Sort  (cost=176.31..176.81 rows=200 width=34) (actual time=39.916..39.922 rows=40 loops=1)
                                 Sort Key: c.article_id, ((c.embedding_normalized <#> '[0.015136719, ... -0.018600464]'::halfvec))
                                 Sort Method: quicksort  Memory: 27kB
                                 Buffers: shared hit=1774 read=48
                                 ->  Subquery Scan on c  (cost=44.29..168.67 rows=200 width=34) (actual time=6.599..39.854 rows=40 loops=1)
                                       Buffers: shared hit=1771 read=48
                                       ->  Limit  (cost=44.29..166.17 rows=200 width=34) (actual time=5.498..16.978 rows=40 loops=1)
                                             Buffers: shared hit=1619 read=15
                                             ->  Nested Loop  (cost=44.29..90347.08 rows=148191 width=34) (actual time=5.497..16.961 rows=40 loops=1)
                                                   Buffers: shared hit=1619 read=15
                                                   ->  Nested Loop  (cost=43.87..24420.17 rows=145702 width=152) (actual time=5.468..14.220 rows=40 loops=1)
                                                         Buffers: shared hit=1462 read=12
                                                         ->  Index Scan using idx_clova_chunk_embedding_binary_hnsw on clova_article_chunk cac  (cost=43.58..15884.45 rows=149495 width=1
52) (actual time=5.219..12.676 rows=40 loops=1)
                                                               Order By: (embedding_binary <~> '10001110 ... 010'::bit(1024))
                                                               Filter: (embedding_binary IS NOT NULL)
                                                               Buffers: shared hit=1366 read=12
                                                         ->  Memoize  (cost=0.30..0.33 rows=1 width=8) (actual time=0.036..0.036 rows=1 loops=40)
                                                               Cache Key: cac.article_id
                                                               Cache Mode: logical
                                                               Hits: 8  Misses: 32  Evictions: 0  Overflows: 0  Memory Usage: 4kB
                                                               Buffers: shared hit=96
                                                               ->  Index Scan using article_pkey on article a  (cost=0.29..0.32 rows=1 width=8) (actual time=0.036..0.036 rows=1 loops=32
)
                                                                     Index Cond: (id = cac.article_id)
                                                                     Filter: (deleted_at IS NULL)
                                                                     Buffers: shared hit=96
                                                   ->  Index Scan using clova_chunk_vectors_pkey on clova_chunk_vectors ccv  (cost=0.42..0.45 rows=1 width=26) (actual time=0.067..0.067 
rows=1 loops=40)
                                                         Index Cond: (id = cac.id)
                                                         Buffers: shared hit=157 read=3
 Planning:
   Buffers: shared hit=343
 Planning Time: 2.459 ms
 Execution Time: 40.107 ms
(48 rows)
```

- `Sort Method: quicksort  Memory: 1234kB` → 메모리 내 처리, work_mem 충분
- `Sort Method: external merge  Disk: 5678kB` → **work_mem 부족으로 디스크 스필 발생**, 이 경우 낮추면 안 됨
- 두 쿼리 다 `LIMIT`이 있는 top-N 쿼리라 PostgreSQL이 대개 top-N heapsort(메모리 내)로 처리하지만, `candidates` CTE(최대 200행)에 대한 윈도우 함수(`ROW_NUMBER() OVER (PARTITION BY ...)`)는 별도로 정렬 비용이 들어가는 지점이니 `Sort`/`WindowAgg` 노드를 특히 눈여겨볼 것

**결과 분석 (2026-07-24 실측)**

- **BM25 (175.67ms)**: `Workers Planned: 2, Workers Launched: 2` — `max_parallel_workers_per_gather=2`가 실제 검색 경로에서 워커 2개를 진짜로 띄우는 것을 확인. 2vCPU 서버에서 검색 쿼리 1건 = leader+worker 프로세스 3개이므로, **동시 검색 2건만 겹쳐도 코어 수를 초과하는 프로세스가 경합**함 — `max_parallel_workers_per_gather` 축소 권고를 뒷받침하는 실측 근거. Sort/Hash 노드 자체가 없고(ParadeDB TopNScanExecState) 버퍼도 대부분 shared hit이라, 이 쿼리는 메모리 스필이 아니라 **CPU 경합 리스크** 쪽.
- **Vector 2단계 (40.1ms)**: `Sort Method: quicksort  Memory: 25kB`/`27kB` — 실제 정렬 메모리 사용량이 극히 작아 `work_mem=16MB`는 이 쿼리 기준 압도적으로 여유 있음. 이 쿼리만 놓고 보면 스필 자체가 발생할 수 없는 규모라 work_mem을 낮출 근거도 위험 근거도 없음.
- **이상 징후**: `LIMIT 200`(candidateLimit)을 요청했는데 `Index Scan using idx_clova_chunk_embedding_binary_hnsw ... rows=40`으로 **실제로는 40행만 반환**, 이후 threshold(0.52) 필터에서 40개 전부 걸러져 최종 0행. 합성 벡터가 실제 Clova 임베딩 분포와 동떨어진 지점(out-of-distribution)이라 HNSW 그래프 탐색이 비정상적으로 일찍 끝났을 가능성 — `ef_search=250` 탐색 범위 안에서 candidateLimit(200)을 못 채운 것으로 보임. 실제 서비스 쿼리에서도 candidateLimit 미달 반환이 재현되면 필터링된 HNSW 검색의 recall 저하 문제, 합성 벡터 특유의 아티팩트면 무시 가능 — **재현 여부를 실제 임베딩 값으로 확인 필요**:
  ```sql
  -- 실제 임베딩으로 재현 확인
  SELECT embedding_normalized, embedding_binary FROM clova_chunk_vectors LIMIT 1;
  -- 위 값을 위 Vector 쿼리의 두 CAST 자리에 대입해서 다시 EXPLAIN
  ```
- **`temp_files=101`의 출처**: 이번에 뜬 두 검색 쿼리 모두 스필이 없었으므로(quicksort, 25~27kB), ①에서 확인한 누적 `temp_files=101`/`temp_bytes≈91.5MB`는 검색 핫패스가 아니라 다른 쿼리(크롤링 배치, 임베딩 배치, term 추출, ANALYZE/VACUUM 등)에서 발생했을 가능성이 높음 — `pg_stat_statements`가 설치돼 있으면 ③으로 원인 쿼리를 특정할 것.

→ **갱신된 결론**: 이번 실측 범위(단발성 BM25/Vector 검색)에서는 `work_mem`을 낮출 근거(스필)도, 위험할 근거도 나오지 않음 — 즉 **work_mem은 이 데이터만으로는 건드릴 필요 없음**. 대신 BM25 쿼리에서 병렬 워커 사용이 실측으로 확인됐으므로, `max_parallel_workers_per_gather=0`(또는 1) 조정이 우선순위가 더 명확해짐. 다만 워커를 끄면 단일 쿼리 지연시간(175ms)이 늘어날 수 있으니, 같은 세션에서 `SET max_parallel_workers_per_gather = 0;` 후 동일 쿼리를 다시 떠서 지연시간 변화를 비교한 뒤 전역 적용 여부를 결정할 것. 동시 트래픽 상황에서의 스필 여부(현재 idle=13, active=1인 저트래픽 상태에서 측정한 값이라 실제 부하 시나리오와 다를 수 있음)는 트래픽이 늘어난 뒤 ①번 카운터를 재확인해서 판단.

**③ (선택) 운영 트래픽 기준 지속 모니터링**
```sql
-- pg_stat_statements 확장 설치 여부 확인
SELECT * FROM pg_available_extensions WHERE name = 'pg_stat_statements';
```
설치돼 있으면 `temp_blks_written` 컬럼으로 쿼리별 스필량을 볼 수 있고, 없으면 `log_temp_files = 0`으로 설정해 며칠간 로그에 찍히는 스필을 관찰하는 방법도 있음.

### 4. max_connections=100 — 과잉 설정

실제 접속은 14개(blue/green 앱 인스턴스 + 모니터링 exporter)뿐인데 100까지 열려있음. 커넥션마다 유휴 상태에서도 프로세스 메모리 오버헤드가 있으므로 30~40 정도로 낮춰 여유 메모리를 확보하는 것을 권장.

### 5. 테이블 용량 — article_term 인덱스가 최대 덩치

```
article_term        : 836MB (index 496MB — 테이블(340MB)보다 인덱스가 더 큼)
clova_chunk_vectors  : 417MB (index 408MB — HNSW라 원래 큼, 정상 범주)
clova_chunk_contents : 262MB
article              : 142MB (index 128MB)
clova_article_chunk  : 107MB
term                 : 68MB (index 26MB)
article_analyzed_content: 51MB (index 33MB — BM25 인덱스)
```

DB 전체 용량이 어림잡아 **~1.9GB**로 `shared_buffers(256MB) + OS 캐시(449Mi)`를 합쳐도 전부 못 담는 규모 — 캐시 히트율이 100%가 아니라 98.65%에 머무는 배경. `article_term` 인덱스가 유독 비대하므로 `\di+ article_term*`으로 어떤 인덱스가 걸려있는지, 안 쓰는 인덱스가 있는지 확인할 가치 있음.

### 6. 문서 쿼리 오류 정정 — 실제 테이블명 불일치

원래 문서의 4번(autovacuum) 쿼리는 `article_chunk`/`chunk_vector`로 조회했으나 실제 물리 테이블명이 아니라서(엔티티 `@Table` 확인 결과) 빈 결과가 나옴:

- `ArticleChunk` 엔티티 → 실제 테이블 `clova_article_chunk`
- `ChunkContent` 엔티티 → 실제 테이블 `clova_chunk_contents`
- `ChunkVector` 엔티티 → 실제 테이블 `clova_chunk_vectors` (`embedding_binary`/`embedding_normalized` 컬럼도 이 테이블에 있음, `article`이 아님)

`article_analyzed_content`는 `last_autovacuum`이 한 번도 실행되지 않았고 `last_autoanalyze`도 2026-05-18(약 2개월 전)이지만 `dead_tup=0`이라 당장 급한 문제는 아님 — 다만 데이터가 늘어나기 시작하면 재확인 필요.

7번 `EXPLAIN` 쿼리는 `embedding_binary`가 `article` 테이블이 아니라 `clova_chunk_vectors`에 있어서 컬럼 없음 에러 발생. 정정된 쿼리:

```sql
-- autovacuum/dead tuple (정정된 테이블명)
SELECT relname, last_autovacuum, last_autoanalyze, n_dead_tup, n_live_tup,
       round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS dead_pct
FROM pg_stat_user_tables
WHERE relname IN ('clova_article_chunk', 'article_analyzed_content', 'clova_chunk_vectors', 'clova_chunk_contents', 'article', 'article_term')
ORDER BY dead_pct DESC NULLS LAST;

-- article_term 인덱스 목록/크기
\di+ article_term*
```

HNSW 인덱스 실사용 확인은 임시 단일 컬럼 쿼리보다 실제 하이브리드 검색이 쓰는 전체 쿼리로 떠보는 게 정확함 — 3번 ②에 코드 기준으로 재구성한 BM25/Vector 2단계 쿼리 참고.

### 8. 커넥션 풀(HikariCP) 변경 이력 — git log 기준

`max_connections=100`(DB 서버 설정)을 낮추자는 5번 항목과 별개로, **앱 쪽 HikariCP 풀 크기(커넥션 개수)** 자체는 이미 3월에 튜닝이 끝난 상태. 최근 커밋들은 풀 크기가 아니라 "커넥션 점유 시간"을 줄이는 방향으로 이어지고 있음.

| 날짜 | 커밋 | 변경 내용 |
|------|------|-----------|
| 2026-01-03 | `b588919` | 최초 설정: `maximum-pool-size=10`, `minimum-idle=3` |
| 2026-01-18 | `399faa1` | `10→20`, `min-idle 3→10` (tomcat `threads.max=50` 기준으로 늘림) — 이후 되돌아옴 |
| 2026-02-22 | `0fd6edd` | (풀 크기 아님) `connection-init-sql`에 `hnsw.ef_search=250` 추가 |
| 2026-03-01 | `ebf5a02` | **`20→10`로 축소 전환** (min-idle 10 유지). tomcat `threads.max` 50→40, `max-connections` 1000→300도 같이 축소. `socketTimeout`을 5000→5로 변경(의도는 5초, pgjdbc 단위가 초라 결과적으로 올바른 값이 됨) |
| 2026-03-06 | `9cb74c9` | `socketTimeout` 5→600(초)로 재조정 + `connection-init-sql`에 `statement_timeout=30000` 추가, 배치 쿼리는 `BatchQueryService`로 개별 예외 처리 |
| 2026-03-08 | `b09b83d` | **`10→5`, `min-idle 10→5`로 축소 — 현재값** ("hikaricp 커넥션 풀 사이즈 크기 10 -> 5") |
| 2026-03-15 | `2c0ad7d` | `statement_timeout` 30000→5000 (SEO 커밋에 번들, 문서 상단 "현재 이미 적용된 조치"의 5초 타임아웃이 이때 확정) |
| 2026-06-03 | `b5a615a` | (풀 크기 아님) `leak-detection-threshold=30000` 추가 — 크롤링 Chrome 서브프로세스 문제 수정 커밋에 번들 |
| 2026-06-07 | `1fc4ded` | (풀 크기 아님) 크롤링 중 S3/DeepL/OpenAI 외부 API 호출이 `@Transactional` 안에 있어 커넥션을 30초+ 점유하던 문제 수정 — 외부 API 호출을 트랜잭션 밖으로 분리 |
| 2026-07-23 | `ad64c7a` | (풀 크기 아님) 하이브리드 검색 트랜잭션 스코프 축소 — Vector future 대기(최대 5초) 동안 커넥션 미점유하도록 분리 |

**현재값** (`application-prod.properties`): `maximum-pool-size=5`, `minimum-idle=5` — 2026-03-08 이후 변경 없음.

**정리**:
- **커넥션 개수(풀 크기)**: `20 → 10 → 5`로 3월 초에 이미 공격적으로 줄여놓은 상태. 그 이후 커밋은 풀 크기를 더 건드리지 않음.
- **최근 흐름(6~7월)**: 풀 크기 대신 **개별 커넥션의 점유 시간**을 줄이는 쪽으로 방향이 바뀜 — 외부 API 호출을 트랜잭션 밖으로(`1fc4ded`), 하이브리드 검색 트랜잭션 스코프 축소(`ad64c7a`). 즉 "몇 개를 쓰느냐"보다 "하나를 얼마나 오래 붙잡느냐"를 줄이는 전략으로 이미 두 번 손을 댐.
- **DB 서버 쪽 `max_connections=100`은 이 이력에 전혀 등장하지 않음** — postgresql.conf 설정이라 이 저장소 커밋으로 추적되지 않고, 지금까지 한 번도 낮춘 적이 없음. 앱 쪽 풀은 5(인스턴스당) × blue/green 2대 = 10 정도만 실제로 쓰는데(1라운드 실측 `pg_stat_activity`에서 idle=13, active=1로 확인, 총 14개) DB는 여전히 100까지 열어두고 있어 5번 우선순위(`max_connections` 30~40 축소)가 이 이력과 어긋나지 않고 오히려 유일하게 손 안 댄 지점임을 뒷받침.

### 조치 우선순위 (2026-07-24 실측 반영)

1. **`max_parallel_workers_per_gather=0`(또는 1)으로 축소** — BM25 EXPLAIN에서 워커 2개 실제 사용이 확인됨(검색 1건 = 프로세스 3개, 2vCPU에서 동시 검색 2건이면 코어 초과). 적용 전 `SET max_parallel_workers_per_gather = 0;` 세션에서 같은 BM25 쿼리를 재실행해 지연시간(현재 175.67ms) 변화를 먼저 비교
2. `work_mem`은 이번 실측(quicksort 25~27kB, 스필 없음)으로는 조정 근거 없음 — 트래픽 증가 후 ①(`temp_files`) 재확인 전까지 보류
3. Vector 쿼리의 candidateLimit(200) 대비 실제 반환(40) 불일치 재현 여부 확인 — 실제 임베딩 값으로 재실행해 합성 벡터 아티팩트인지 실제 recall 저하인지 판별
4. 최소한의 swap(512MB~1GB) 추가 — 안전망 확보, 성능보다 장애 방지 목적
5. `max_connections` 30~40으로 축소 — 여유 메모리 확보
6. `article_term` 인덱스 점검 — 위 6번 정정 쿼리로 실사용 여부 확인 후 불필요한 인덱스 정리 검토
7. `temp_files=101`/`temp_bytes≈91.5MB`의 실제 발생 쿼리 특정 — `pg_stat_statements` 설치 여부 확인 후 원인 쿼리 추적 (배치성 작업일 가능성 높음)