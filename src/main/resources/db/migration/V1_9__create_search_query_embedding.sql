CREATE TABLE search_query_embedding (
    id BIGSERIAL PRIMARY KEY,
    search_keyword VARCHAR(500) NOT NULL,
    normalized_keyword VARCHAR(500) NOT NULL,
    search_log_id BIGINT NULL,
    embedding vector(1536),
    embedding_generated_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE search_query_embedding
    ADD CONSTRAINT uk_search_query_embedding_normalized
    UNIQUE (normalized_keyword);

CREATE INDEX idx_search_query_embedding_normalized
    ON search_query_embedding (normalized_keyword);

CREATE INDEX idx_search_query_embedding_keyword
    ON search_query_embedding (search_keyword);

CREATE INDEX idx_search_query_embedding_search_log_id
    ON search_query_embedding (search_log_id);

ALTER TABLE search_query_embedding
    ADD CONSTRAINT fk_search_query_embedding_search_log
    FOREIGN KEY (search_log_id) REFERENCES search_logs(id) ON DELETE SET NULL;
