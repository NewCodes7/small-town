CREATE TABLE IF NOT EXISTS rag_query_log (
    id                       BIGSERIAL PRIMARY KEY,
    question                 VARCHAR(500) NOT NULL,
    extracted_corporations   TEXT,
    extracted_keywords       VARCHAR(500),
    vector_query             VARCHAR(500),
    matched_corporation_ids  TEXT,
    article_count            INTEGER,
    outcome                  VARCHAR(20) NOT NULL,
    input_tokens             INTEGER,
    output_tokens            INTEGER,
    total_tokens             INTEGER,
    created_at               TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rag_query_log_created_at ON rag_query_log (created_at DESC);
