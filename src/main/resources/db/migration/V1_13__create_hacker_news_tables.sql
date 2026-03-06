CREATE TABLE hacker_news_item (
    id               BIGSERIAL PRIMARY KEY,
    hn_id            BIGINT NOT NULL UNIQUE,
    title            VARCHAR(500) NOT NULL,
    translated_title VARCHAR(500),
    url              TEXT,
    hn_url           TEXT,
    author           VARCHAR(100),
    score            INTEGER NOT NULL DEFAULT 0,
    comment_count    INTEGER NOT NULL DEFAULT 0,
    hn_created_at    TIMESTAMP,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at       TIMESTAMP
);

CREATE INDEX idx_hn_item_score ON hacker_news_item(score DESC);
CREATE INDEX idx_hn_item_hn_created ON hacker_news_item(hn_created_at DESC);

CREATE TABLE hacker_news_comment (
    id                    BIGSERIAL PRIMARY KEY,
    hn_id                 BIGINT NOT NULL UNIQUE,
    hacker_news_item_id   BIGINT NOT NULL REFERENCES hacker_news_item(id),
    parent_hn_id          BIGINT,
    author                VARCHAR(100),
    text_content          TEXT,
    translated_text       TEXT,
    hn_created_at         TIMESTAMP,
    depth                 INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at            TIMESTAMP
);

CREATE INDEX idx_hn_comment_item_id ON hacker_news_comment(hacker_news_item_id);
