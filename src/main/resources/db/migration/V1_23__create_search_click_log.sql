CREATE TABLE IF NOT EXISTS search_click_log (
    id             BIGSERIAL        PRIMARY KEY,
    search_keyword VARCHAR(500)     NOT NULL,
    search_log_id  BIGINT           NULL,
    article_id     BIGINT           NOT NULL,
    click_position INT              NOT NULL,
    page_number    INT              NOT NULL,
    page_size      INT              NOT NULL,
    sort_type      VARCHAR(20)      NOT NULL,
    total_results  INT              NOT NULL,
    bm25_score     DOUBLE PRECISION NULL,
    vector_score   DOUBLE PRECISION NULL,
    final_score    DOUBLE PRECISION NULL,
    bm25_rank      INT              NULL,
    vector_rank    INT              NULL,
    user_id        BIGINT           NULL,
    ip_address     VARCHAR(45)      NOT NULL,
    user_agent     VARCHAR(500)     NULL,
    created_at     TIMESTAMP        NOT NULL DEFAULT NOW()
);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_scl_search_log') THEN
        ALTER TABLE search_click_log
            ADD CONSTRAINT fk_scl_search_log FOREIGN KEY (search_log_id)
            REFERENCES search_logs(id) ON DELETE SET NULL;
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_scl_article') THEN
        ALTER TABLE search_click_log
            ADD CONSTRAINT fk_scl_article FOREIGN KEY (article_id)
            REFERENCES article(id) ON DELETE CASCADE;
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_scl_user') THEN
        ALTER TABLE search_click_log
            ADD CONSTRAINT fk_scl_user FOREIGN KEY (user_id)
            REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_scl_keyword_created ON search_click_log (search_keyword, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_scl_article_created ON search_click_log (article_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_scl_search_log_id   ON search_click_log (search_log_id);
CREATE INDEX IF NOT EXISTS idx_scl_user_id         ON search_click_log (user_id);
