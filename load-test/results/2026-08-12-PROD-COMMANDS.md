# 운영 서버에서 실행할 명령어 — 검색 커밋 부하테스트 (2026-08-12)

k6 실행과 Prometheus/Loki 수집은 Claude가 처리한다. 이 문서는 **운영 서버 셸에서만 가능한 작업**의
명령어 모음이다. 각 단계 끝나면 Claude에게 알려주면 된다.

결과 기록: `2026-08-12-search-unpushed-commits-ab.md`

**현재 범위**: before(`origin/main`, W1·W2 측정 완료) vs after(커밋 6개 전체 적용) **1회 비교**.
`.env`는 건드리지 않는다 — compose 기본값이 곧 전체 적용 상태다
(`REUSE_STAGE1_CANDIDATES=true`, `CROSS_SCORING_TWO_STAGE=true`, `STAGE2_LIMIT=20`).

---

## 1. 푸시

```bash
git push origin main
```

## 2. 배포 확인

```bash
./deploy.sh status
```

```bash
docker exec newcodes-backend-green env | grep SEARCH_HYBRID_ || docker exec newcodes-backend-blue env | grep SEARCH_HYBRID_
```

기대: `TWO_STAGE=true`, `REUSE_STAGE1_CANDIDATES=true`, `STAGE2_LIMIT=20`

여기까지 하고 알려주면 Claude가 사다리(13.5분)를 띄운다.

---

## 3. (선택) pgss 스냅샷 — 항목별 판정용

없어도 헤드라인 결론은 나온다. 뜨면 아래 항목이 추가로 판정된다:
`#1 Category select → 0` / `#2 유의어 per_search → 1` / `#4 cross-scoring 조각수 → 1` /
`#5·6 퍼널 blks_per_call`.

최초 1회 스크립트 설치:

```bash
cat > pgss_snap.sql <<'SQL'
CREATE TABLE IF NOT EXISTS loadtest_pgss_snap (
  tag text NOT NULL, ts timestamptz NOT NULL DEFAULT now(),
  queryid bigint, calls bigint, total_exec_time double precision,
  blks bigint, query text);
DELETE FROM loadtest_pgss_snap WHERE tag = :'tag';
INSERT INTO loadtest_pgss_snap (tag, queryid, calls, total_exec_time, blks, query)
SELECT :'tag', queryid, calls, total_exec_time, shared_blks_hit + shared_blks_read,
       lower(regexp_replace(query, '\s+', ' ', 'g'))
FROM pg_stat_statements
WHERE query NOT ILIKE '%pg_stat_statements%' AND query NOT ILIKE '%loadtest_pgss_snap%';
SELECT :'tag' AS tag, count(*) AS rows, sum(calls) AS calls
FROM loadtest_pgss_snap WHERE tag = :'tag';
SQL
```

```bash
cat > pgss_diff.sql <<'SQL'
\pset pager off
WITH d AS (
  SELECT COALESCE(b.queryid,a.queryid) AS queryid, COALESCE(b.query,a.query) AS q,
         COALESCE(b.calls,0)-COALESCE(a.calls,0) AS calls,
         COALESCE(b.total_exec_time,0)-COALESCE(a.total_exec_time,0) AS ms,
         COALESCE(b.blks,0)-COALESCE(a.blks,0) AS blks
  FROM (SELECT * FROM loadtest_pgss_snap WHERE tag=:'b') b
  FULL JOIN (SELECT * FROM loadtest_pgss_snap WHERE tag=:'a') a USING (queryid)),
c AS (SELECT CASE
      WHEN q LIKE '%embedding_binary%<~>%' AND q LIKE '%clova_chunk_contents%' THEN '워밍 HNSW'
      WHEN q LIKE '%embedding_binary%<~>%' AND q LIKE '%stage1%'                THEN 'cross-scoring 퍼널'
      WHEN q LIKE '%embedding_binary%<~>%'                                      THEN '벡터 2단계 본검색'
      WHEN q LIKE '%<#>%' AND q NOT LIKE '%embedding_binary%'                   THEN 'cross-scoring 단일'
      WHEN q LIKE '%@@@%'                    THEN 'BM25'
      WHEN q LIKE '%search_query_embedding%' THEN '임베딩 캐시'
      WHEN q LIKE '%term_synonym%'           THEN '유의어'
      WHEN q LIKE '%from category%'          THEN 'Category'
      ELSE '기타' END AS 구분, calls, ms, blks FROM d WHERE calls > 0),
n AS (SELECT NULLIF(sum(calls),0) AS searches FROM c WHERE 구분 = 'BM25')
SELECT 구분, sum(calls) AS calls,
       round((sum(calls)::numeric / (SELECT searches FROM n)), 2) AS per_search,
       round(sum(ms)::numeric,1) AS total_ms,
       round((sum(ms)/NULLIF(sum(calls),0))::numeric,2) AS mean_ms,
       round(100*sum(ms)/NULLIF(SUM(sum(ms)) OVER (),0)) AS pct,
       sum(blks) AS blks,
       round(sum(blks)::numeric/NULLIF(sum(calls),0),0) AS blks_per_call,
       count(*) AS 조각수
FROM c GROUP BY 구분 ORDER BY sum(ms) DESC;
SQL
```

Claude가 사다리를 띄우기 **직전**:

```bash
psql -v tag=z_start -f pgss_snap.sql
```

사다리 **종료 후**:

```bash
psql -v tag=z_end -f pgss_snap.sql
psql -v a=z_start -v b=z_end -f pgss_diff.sql
```

---

## 부록 — 항목별로 더 파고들 때 (지금은 안 함)

전체 비교에서 뭔가 나오면 그때 아래로 변인을 가른다. 전부 `.env` 수정 + `./deploy.sh deploy`.

| 목적 | `.env` |
|---|---|
| 퍼널만 끄기 (#5·6 격리) | `SEARCH_HYBRID_CROSS_SCORING_TWO_STAGE=false` |
| 후보 재활용 끄기 (#3 격리) | `SEARCH_HYBRID_REUSE_STAGE1_CANDIDATES=false` |
| 컷 스윕 (파레토 비용 곡선) | `SEARCH_HYBRID_CROSS_SCORING_STAGE2_LIMIT=10` / `30` / `50` |

원복은 해당 줄 삭제 후 `./deploy.sh deploy`.
컷 스윕 실행은 사다리 전체 대신 `VU_LEVELS=2,5`(약 7분)로 충분하다.
