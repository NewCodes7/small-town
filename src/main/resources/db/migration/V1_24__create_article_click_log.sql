CREATE TABLE IF NOT EXISTS article_click_log (
    id                BIGSERIAL    PRIMARY KEY,
    article_id        BIGINT       NOT NULL,
    source            VARCHAR(30)  NOT NULL,
    source_context_id BIGINT       NULL,
    user_id           BIGINT       NULL,
    ip_address        VARCHAR(45)  NOT NULL,
    user_agent        VARCHAR(500) NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_acl_article') THEN
        ALTER TABLE article_click_log
            ADD CONSTRAINT fk_acl_article FOREIGN KEY (article_id)
            REFERENCES article(id) ON DELETE CASCADE;
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_acl_user') THEN
        ALTER TABLE article_click_log
            ADD CONSTRAINT fk_acl_user FOREIGN KEY (user_id)
            REFERENCES users(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_acl_source_created  ON article_click_log (source, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_acl_article_created ON article_click_log (article_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_acl_user_id         ON article_click_log (user_id);
