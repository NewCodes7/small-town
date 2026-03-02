-- article_search_view title_terms 버그 수정
-- BOTH source term이 title_terms에 포함되지 않던 문제 수정
-- (기술 키워드는 대부분 제목+본문 모두 등장하여 source=BOTH로 저장되는데,
--  기존 view는 source='TITLE'만 title_terms에 포함시켜 title 부스트가 사실상 무효였음)

DROP MATERIALIZED VIEW IF EXISTS article_search_view CASCADE;

CREATE MATERIALIZED VIEW article_search_view AS
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

CREATE UNIQUE INDEX article_search_view_id_idx ON article_search_view (id);

CREATE INDEX article_search_view_bm25_idx ON article_search_view
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
