-- article_search_view Materialized View 삭제
-- article_analyzed_content 테이블로 완전 전환 후 뷰 제거
-- BM25 인덱스는 CASCADE로 함께 삭제됨

DROP MATERIALIZED VIEW IF EXISTS article_search_view CASCADE;

DROP FUNCTION IF EXISTS refresh_article_search_index();
