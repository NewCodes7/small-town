-- 새 테이블 생성 (ON DELETE CASCADE로 청크 삭제 시 자동 삭제)
CREATE TABLE clova_chunk_vectors (
    id BIGINT PRIMARY KEY REFERENCES clova_article_chunk(id) ON DELETE CASCADE,
    embedding_normalized halfvec(1024)
);

-- 기존 데이터 마이그레이션
INSERT INTO clova_chunk_vectors (id, embedding_normalized)
SELECT id, embedding_normalized
FROM clova_article_chunk
WHERE embedding_normalized IS NOT NULL;

-- 기존 테이블에서 컬럼 제거
ALTER TABLE clova_article_chunk DROP COLUMN embedding_normalized;
