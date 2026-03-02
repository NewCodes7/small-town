-- 테스트용 article_search_view Materialized View 생성
-- Hibernate DDL 이후 실행되어 BM25 검색이 동작하도록 함

CREATE MATERIALIZED VIEW IF NOT EXISTS article_search_view AS
SELECT
    a.id,
    a.title,
    a.published_at,
    a.corporation_id,
    a.category_id,
    terms.title_terms,
    terms.content_terms
FROM article a
LEFT JOIN (
    SELECT
        at.article_id,
        STRING_AGG(CASE WHEN at.source IN ('TITLE', 'BOTH') THEN t.term END, ' ' ORDER BY at.score DESC) AS title_terms,
        STRING_AGG(CASE WHEN at.source IN ('CONTENT', 'BOTH') THEN t.term END, ' ' ORDER BY at.score DESC) AS content_terms
    FROM article_term at
    JOIN term t ON at.term_id = t.id
    GROUP BY at.article_id
) terms ON a.id = terms.article_id
WHERE a.deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS article_search_view_id_idx ON article_search_view (id);

CREATE INDEX IF NOT EXISTS article_search_view_bm25_idx ON article_search_view
USING bm25 (id, title_terms, content_terms)
WITH (
    key_field='id',
    text_fields='{"title_terms": {}, "content_terms": {}}'
);

CREATE OR REPLACE FUNCTION refresh_article_search_index()
RETURNS void AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY article_search_view;
END;
$$ LANGUAGE plpgsql;
