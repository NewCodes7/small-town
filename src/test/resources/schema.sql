-- 테스트용 article_analyzed_content 테이블 생성
-- Hibernate DDL 이후 실행되어 BM25 검색이 동작하도록 함

CREATE TABLE IF NOT EXISTS article_analyzed_content (
    id             BIGINT PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
    title          TEXT,
    published_at   TIMESTAMP,
    corporation_id BIGINT,
    category_id    BIGINT,
    title_terms    TEXT,
    content_terms  TEXT,
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS article_analyzed_content_bm25_idx ON article_analyzed_content
USING bm25 (id, title_terms, content_terms)
WITH (
    key_field='id',
    text_fields='{"title_terms": {}, "content_terms": {}}'
);
