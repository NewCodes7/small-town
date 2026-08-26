-- 청크 중복 제거 + (article_id, chunk_index) 유니크 제약
--
-- 왜: clova_article_chunk 에 유니크 제약이 없어 같은 아티클이 두 번 임베딩되면 청크 세트가
-- 통째로 중복 삽입된다. 2026-08-22 검색 정확도 평가에서 762개 아티클 중 7건 발견(내용 전부 동일).
-- 벡터 검색이 아티클당 AVG(상위 topK 청크)를 쓰기 때문에 중복은 그 아티클의 점수를 부풀린다
-- (AVG(s1,s1,s2) >= AVG(s1,s2,s3)). Stage 1 후보 200칸도 중복이 잠식한다.
--
-- prod 는 chunk_dedup_apply.sql 로 선반영하므로 아래 DELETE 는 no-op 이다.
-- 다른 환경(로컬/복원본)을 위해 마이그레이션 안에도 같은 정리를 둔다.
--
-- 남길 행: 대표 청크 > embedding_binary 있는 행 > 벡터/본문 있는 행 > id 큰 행(최근).
-- 자식 테이블은 V1_5/V1_7 에서 ON DELETE CASCADE 로 만들었지만 ddl-auto 로 생성된 환경에는
-- CASCADE 없는 FK 가 있다. 자식을 명시적으로 먼저 지워 어느 쪽이든 동작하게 한다.

CREATE TEMP TABLE chunk_dup_delete ON COMMIT DROP AS
SELECT c.id
FROM clova_article_chunk c
JOIN (SELECT article_id, chunk_index
      FROM clova_article_chunk
      GROUP BY article_id, chunk_index
      HAVING count(*) > 1) g
  ON g.article_id = c.article_id AND g.chunk_index = c.chunk_index
LEFT JOIN clova_chunk_vectors  v  ON v.id  = c.id
LEFT JOIN clova_chunk_contents cc ON cc.id = c.id
WHERE c.id <> (
    SELECT k.id
    FROM clova_article_chunk k
    LEFT JOIN clova_chunk_vectors  kv ON kv.id = k.id
    LEFT JOIN clova_chunk_contents kc ON kc.id = k.id
    WHERE k.article_id = c.article_id AND k.chunk_index = c.chunk_index
    ORDER BY COALESCE(k.is_representative, false) DESC,
             (k.embedding_binary IS NOT NULL)     DESC,
             (kv.id IS NOT NULL)                  DESC,
             (kc.id IS NOT NULL)                  DESC,
             k.id                                 DESC
    LIMIT 1
);

DELETE FROM clova_chunk_vectors  WHERE id IN (SELECT id FROM chunk_dup_delete);
DELETE FROM clova_chunk_contents WHERE id IN (SELECT id FROM chunk_dup_delete);
DELETE FROM clova_article_chunk  WHERE id IN (SELECT id FROM chunk_dup_delete);

-- 재발 방지. 이 인덱스가 (article_id) 선두 조회도 커버하지만, 기존
-- idx_clova_chunk_article_id 는 이미 다른 실행계획에 쓰이고 있어 건드리지 않는다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_clova_chunk_article_index') THEN
        ALTER TABLE clova_article_chunk
            ADD CONSTRAINT uq_clova_chunk_article_index UNIQUE (article_id, chunk_index);
    END IF;
END $$;
