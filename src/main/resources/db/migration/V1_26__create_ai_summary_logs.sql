CREATE TABLE IF NOT EXISTS ai_summary_logs (
    id          BIGSERIAL PRIMARY KEY,
    search_keyword VARCHAR(500) NOT NULL,
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    total_tokens   INTEGER,
    cached         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_summary_logs_created_at ON ai_summary_logs (created_at DESC);
