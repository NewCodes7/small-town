-- clova_article_chunk.content 컬럼 분리
-- content TEXT는 TOAST I/O를 유발하므로 별도 테이블로 이관
-- clova_chunk_vectors (V1_5) 패턴과 동일한 @MapsId 구조

CREATE TABLE clova_chunk_contents (
    id BIGINT PRIMARY KEY REFERENCES clova_article_chunk(id) ON DELETE CASCADE,
    content TEXT NOT NULL
);

INSERT INTO clova_chunk_contents (id, content)
SELECT id, content FROM clova_article_chunk;

ALTER TABLE clova_article_chunk DROP COLUMN content;
