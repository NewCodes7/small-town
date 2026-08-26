-- 중복 청크 조사 (읽기 전용) — 2026-08-22
--
-- 배경: clova_article_chunk 에 (article_id, chunk_index) 유니크 제약이 없어
-- 같은 아티클이 두 번 임베딩되면 청크 세트가 통째로 중복 삽입된다.
-- 검색 평가용으로 762개 아티클을 뽑았을 때 7건에서 발견됐다(내용 전부 동일).
--
-- 영향: 벡터 검색은 아티클당 AVG(상위 topK 청크)를 쓰므로 중복이 있으면
--       AVG(s1,s1,s2) >= AVG(s1,s2,s3) 로 점수가 부풀려지고,
--       Stage 1 후보 200칸도 중복이 잠식한다.
--
-- 실행:  psql "$DB_URL" -f chunk_dedup_inspect.sql
\timing on

\echo ''
\echo '=== [1] 전체 범위 ==='
SELECT count(*)                       AS dup_groups,
       sum(n - 1)                     AS rows_to_delete,
       count(DISTINCT article_id)     AS affected_articles,
       (SELECT count(*) FROM clova_article_chunk) AS total_chunk_rows
FROM (SELECT article_id, chunk_index, count(*) AS n
      FROM clova_article_chunk GROUP BY 1, 2 HAVING count(*) > 1) d;

\echo ''
\echo '=== [2] 그룹 안에서 본문이 서로 다른 경우 (기대: 0. 0이 아니면 자동 삭제 금지) ==='
SELECT count(*) AS groups_with_differing_content
FROM (SELECT c.article_id, c.chunk_index
      FROM clova_article_chunk c JOIN clova_chunk_contents cc ON cc.id = c.id
      GROUP BY 1, 2 HAVING count(*) > 1 AND count(DISTINCT md5(cc.content)) > 1) x;

\echo ''
\echo '=== [3] 그룹 안에 대표 청크가 2개 이상 (기대: 0) ==='
SELECT count(*) AS groups_with_multiple_representatives
FROM (SELECT article_id, chunk_index FROM clova_article_chunk
      GROUP BY 1, 2
      HAVING count(*) > 1 AND count(*) FILTER (WHERE is_representative) > 1) x;

\echo ''
\echo '=== [4] 영향받은 아티클 목록 (중복 그룹 수 / 총 행 수) ==='
SELECT article_id,
       count(*)                                   AS dup_groups,
       sum(n)                                     AS rows_now,
       sum(n) - count(*)                          AS rows_to_delete,
       min(first_created)::date                   AS first_created,
       max(last_created)::date                    AS last_created
FROM (SELECT article_id, chunk_index, count(*) AS n,
             min(created_at) AS first_created, max(created_at) AS last_created
      FROM clova_article_chunk GROUP BY 1, 2 HAVING count(*) > 1) d
GROUP BY article_id ORDER BY rows_to_delete DESC;

\echo ''
\echo '=== [5] 샘플 그룹 상세 — 어느 행을 남길지 확인용 ==='
SELECT c.article_id, c.chunk_index, c.id, c.created_at, c.is_representative,
       (c.embedding_binary IS NOT NULL) AS has_binary,
       (v.id IS NOT NULL)               AS has_vector,
       (cc.id IS NOT NULL)              AS has_content,
       left(md5(cc.content), 8)         AS content_md5
FROM clova_article_chunk c
LEFT JOIN clova_chunk_vectors  v  ON v.id  = c.id
LEFT JOIN clova_chunk_contents cc ON cc.id = c.id
WHERE (c.article_id, c.chunk_index) IN (
        SELECT article_id, chunk_index FROM clova_article_chunk
        GROUP BY 1, 2 HAVING count(*) > 1)
ORDER BY c.article_id, c.chunk_index, c.id
LIMIT 40;

\echo ''
\echo '=== [6] 자식 테이블 FK 에 ON DELETE CASCADE 가 걸려 있는가 ==='
SELECT conrelid::regclass AS child_table, conname,
       CASE confdeltype WHEN 'c' THEN 'CASCADE' WHEN 'a' THEN 'NO ACTION'
                        WHEN 'r' THEN 'RESTRICT' ELSE confdeltype::text END AS on_delete
FROM pg_constraint
WHERE confrelid = 'clova_article_chunk'::regclass AND contype = 'f';
