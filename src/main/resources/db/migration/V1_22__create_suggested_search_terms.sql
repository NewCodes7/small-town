CREATE TABLE IF NOT EXISTS suggested_search_term (
    id BIGSERIAL PRIMARY KEY,
    keyword VARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO suggested_search_term (keyword, display_order, is_active)
SELECT v.keyword, v.display_order, v.is_active
FROM (VALUES
  ('대용량 트래픽 처리'::varchar, 0::int, TRUE::boolean),
  ('React 성능 최적화'::varchar,  1::int, TRUE::boolean),
  ('MSA 전환 경험'::varchar,      2::int, TRUE::boolean),
  ('Kafka 실시간 처리'::varchar,  3::int, TRUE::boolean)
) AS v(keyword, display_order, is_active)
WHERE NOT EXISTS (
    SELECT 1 FROM suggested_search_term WHERE keyword = v.keyword
);
