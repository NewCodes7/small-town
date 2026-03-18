-- article_analyzed_content 테이블 생성 (article_search_view Materialized View 대체)
-- REFRESH 비용(30초, CPU 53%, Disk IO ~74%) 제거를 위해 일반 테이블로 전환
-- Term CRUD 시 애플리케이션 레벨에서 정합성 보장 (ArticleAnalyzedContentService)

-- 1. 테이블 생성
CREATE TABLE article_analyzed_content (
    id             BIGINT PRIMARY KEY REFERENCES article(id) ON DELETE CASCADE,
    title          TEXT,
    published_at   TIMESTAMP,
    corporation_id BIGINT,
    category_id    BIGINT,
    title_terms    TEXT,
    content_terms  TEXT,
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 2. 기존 데이터 이관 (무중단: article_search_view는 이 시점에 아직 존재)
INSERT INTO article_analyzed_content (id, title, published_at, corporation_id, category_id, title_terms, content_terms, updated_at)
SELECT id, title, published_at, corporation_id, category_id, title_terms, content_terms, NOW()
FROM article_search_view;

-- 3. BM25 인덱스 (V1_11 패턴 동일)
CREATE INDEX article_analyzed_content_bm25_idx ON article_analyzed_content
USING bm25 (id, title_terms, content_terms)
WITH (
    key_field='id',
    text_fields='{"title_terms": {}, "content_terms": {}}'
);
