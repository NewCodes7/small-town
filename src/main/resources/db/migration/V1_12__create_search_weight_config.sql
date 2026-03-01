CREATE TABLE search_weight_config (
    id                BIGSERIAL PRIMARY KEY,
    complexity        VARCHAR(20) NOT NULL UNIQUE,
    title_multiplier  DOUBLE PRECISION NOT NULL,
    bm25_nsf_weight   DOUBLE PRECISION NOT NULL,
    vector_nsf_weight DOUBLE PRECISION NOT NULL,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(100)
);

INSERT INTO search_weight_config (complexity, title_multiplier, bm25_nsf_weight, vector_nsf_weight) VALUES
  ('SIMPLE',   3.0, 0.6, 0.4),
  ('MODERATE', 2.0, 0.5, 0.5),
  ('COMPLEX',  1.0, 0.4, 0.6);
