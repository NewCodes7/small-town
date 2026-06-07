CREATE TABLE suggested_search_term (
    id BIGSERIAL PRIMARY KEY,
    keyword VARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO suggested_search_term (keyword, display_order) VALUES
  ('대용량 트래픽 처리', 0),
  ('React 성능 최적화', 1),
  ('MSA 전환 경험', 2),
  ('Kafka 실시간 처리', 3);
