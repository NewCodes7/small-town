ALTER TABLE hacker_news_item ADD COLUMN rank INTEGER;
ALTER TABLE hacker_news_item ADD COLUMN crawl_batch_at TIMESTAMP;

CREATE INDEX idx_hn_item_batch_rank ON hacker_news_item (crawl_batch_at DESC, rank ASC);
