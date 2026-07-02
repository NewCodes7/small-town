-- V1_14에서 Flyway 베이스라인(1.19) 이전에 생성되어 누락된 BM25 인덱스 복구
CREATE INDEX IF NOT EXISTS article_analyzed_content_bm25_idx ON article_analyzed_content
USING bm25 (id, title_terms, content_terms)
WITH (
    key_field='id',
    text_fields='{"title_terms": {}, "content_terms": {}}'
);
