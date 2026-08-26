-- 중복 청크 삭제 — 2026-08-22
--
-- 먼저 chunk_dedup_inspect.sql 로 범위를 확인할 것.
--
-- 안전장치: 예상과 다르면 RAISE EXCEPTION 으로 **트랜잭션째 롤백**된다.
--   G1 본문이 서로 다른 중복 그룹이 있으면 중단 (어느 쪽이 진짜인지 알 수 없다)
--   G2 삭제 대상이 전체의 5% 를 넘으면 중단 (조회 조건이 잘못됐다는 뜻)
--   G3 삭제 후에도 중복이 남으면 중단
--
-- 자식 테이블(clova_chunk_vectors / clova_chunk_contents)은 마이그레이션상 ON DELETE CASCADE 지만
-- (V1_5, V1_7) 환경에 따라 Hibernate ddl-auto 가 만든 CASCADE 없는 FK 일 수 있다.
-- 그래서 **자식을 명시적으로 먼저 지운다** — CASCADE 유무와 무관하게 동작한다.
--
-- 남길 행의 우선순위:
--   1) 대표 청크(is_representative) — RepresentativeChunkService 가 아티클당 1행만 세운다
--   2) embedding_binary 있는 행     — 없으면 Stage 1 HNSW 후보에 아예 못 든다
--   3) 벡터/본문이 있는 행
--   4) id 가 큰 행 (최근 것) — 본문이 다를 경우 최신 추출을 남기는 편이 안전하다
--
-- 실행:  psql "$DB_URL" -f chunk_dedup_apply.sql
\set ON_ERROR_STOP on
\timing on

BEGIN;
SET LOCAL statement_timeout = '300s';

CREATE TEMP TABLE dup_group ON COMMIT DROP AS
SELECT article_id, chunk_index
FROM clova_article_chunk GROUP BY 1, 2 HAVING count(*) > 1;

\echo ''
\echo '=== 중복 그룹 ==='
SELECT count(*) AS dup_groups FROM dup_group;

-- G1: 본문이 다른 그룹이 있으면 중단
DO $$
DECLARE bad int;
BEGIN
    SELECT count(*) INTO bad
    FROM (SELECT c.article_id, c.chunk_index
          FROM clova_article_chunk c
          JOIN dup_group g USING (article_id, chunk_index)
          JOIN clova_chunk_contents cc ON cc.id = c.id
          GROUP BY 1, 2 HAVING count(DISTINCT md5(cc.content)) > 1) x;
    IF bad > 0 THEN
        RAISE EXCEPTION '중단: 본문이 서로 다른 중복 그룹 %건. 어느 쪽이 진짜인지 확인 후 수동 처리할 것', bad;
    END IF;
END $$;

CREATE TEMP TABLE to_delete ON COMMIT DROP AS
SELECT id FROM (
    SELECT c.id,
           row_number() OVER (
               PARTITION BY c.article_id, c.chunk_index
               ORDER BY COALESCE(c.is_representative, false) DESC,
                        (c.embedding_binary IS NOT NULL)   DESC,
                        (v.id  IS NOT NULL)                DESC,
                        (cc.id IS NOT NULL)                DESC,
                        c.id                               DESC
           ) AS rn
    FROM clova_article_chunk c
    JOIN dup_group g USING (article_id, chunk_index)
    LEFT JOIN clova_chunk_vectors  v  ON v.id  = c.id
    LEFT JOIN clova_chunk_contents cc ON cc.id = c.id
) t WHERE rn > 1;

\echo ''
\echo '=== 삭제 대상 ==='
SELECT (SELECT count(*) FROM to_delete)                AS rows_to_delete,
       (SELECT count(*) FROM clova_article_chunk)      AS rows_total,
       round(100.0 * (SELECT count(*) FROM to_delete)
             / NULLIF((SELECT count(*) FROM clova_article_chunk), 0), 2) AS pct;

-- G2: 비율이 비정상이면 중단
DO $$
DECLARE d bigint; t bigint;
BEGIN
    SELECT count(*) INTO d FROM to_delete;
    SELECT count(*) INTO t FROM clova_article_chunk;
    IF d = 0 THEN
        RAISE NOTICE '삭제할 중복이 없다. 이미 정리됐거나 조회 조건을 확인할 것';
    ELSIF d > t * 0.05 THEN
        RAISE EXCEPTION '중단: 삭제 대상이 %건으로 전체 %건의 5%% 를 넘는다', d, t;
    END IF;
END $$;

-- 대표 청크는 절대 지우지 않는다 (지우면 그 아티클의 대표 본문 조회가 빈다)
DO $$
DECLARE bad bigint;
BEGIN
    SELECT count(*) INTO bad
    FROM clova_article_chunk c JOIN to_delete d ON d.id = c.id
    WHERE COALESCE(c.is_representative, false);
    IF bad > 0 THEN
        RAISE EXCEPTION '중단: 삭제 대상에 대표 청크가 %건 포함됐다', bad;
    END IF;
END $$;

DELETE FROM clova_chunk_vectors  WHERE id IN (SELECT id FROM to_delete);
DELETE FROM clova_chunk_contents WHERE id IN (SELECT id FROM to_delete);
DELETE FROM clova_article_chunk  WHERE id IN (SELECT id FROM to_delete);

-- G3: 남은 중복이 있으면 중단
DO $$
DECLARE left_over bigint;
BEGIN
    SELECT count(*) INTO left_over
    FROM (SELECT article_id, chunk_index FROM clova_article_chunk
          GROUP BY 1, 2 HAVING count(*) > 1) x;
    IF left_over > 0 THEN
        RAISE EXCEPTION '중단: 삭제 후에도 중복 그룹 %건이 남았다', left_over;
    END IF;
END $$;

\echo ''
\echo '=== 삭제 후 검증 (chunks = contents 여야 하고, 고아가 0 이어야 한다) ==='
SELECT (SELECT count(*) FROM clova_article_chunk)  AS chunks,
       (SELECT count(*) FROM clova_chunk_contents) AS contents,
       (SELECT count(*) FROM clova_chunk_vectors)  AS vectors,
       (SELECT count(*) FROM clova_article_chunk c
          WHERE NOT EXISTS (SELECT 1 FROM clova_chunk_contents x WHERE x.id = c.id)) AS chunks_without_content,
       (SELECT count(*) FROM clova_article_chunk c
          WHERE NOT EXISTS (SELECT 1 FROM clova_chunk_vectors x WHERE x.id = c.id))  AS chunks_without_vector;

COMMIT;

\echo ''
\echo '완료. 재발 방지 유니크 제약은 마이그레이션 V1_39 로 다음 배포 때 적용된다.'
