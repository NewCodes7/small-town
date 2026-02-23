CREATE TABLE crawling_scheduler_run (
    id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    new_items_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);

CREATE INDEX idx_crawling_scheduler_run_started_at
    ON crawling_scheduler_run (started_at DESC);

CREATE TABLE crawling_article_processing_log (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    article_id BIGINT NULL,
    article_title VARCHAR(300),
    article_link TEXT,
    corporation_id BIGINT NULL,
    corporation_name VARCHAR(200),
    is_new BOOLEAN NOT NULL DEFAULT TRUE,
    content_extraction_status VARCHAR(20),
    content_extraction_error TEXT,
    term_analysis_status VARCHAR(20),
    term_analysis_error TEXT,
    embedding_status VARCHAR(20),
    embedding_error TEXT,
    representative_chunk_status VARCHAR(20),
    representative_chunk_error TEXT,
    category_status VARCHAR(20),
    category_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE crawling_article_processing_log
    ADD CONSTRAINT fk_crawling_article_log_run
    FOREIGN KEY (run_id) REFERENCES crawling_scheduler_run(id) ON DELETE CASCADE;

CREATE INDEX idx_crawling_article_log_run_id
    ON crawling_article_processing_log (run_id);

CREATE INDEX idx_crawling_article_log_article_id
    ON crawling_article_processing_log (article_id);
