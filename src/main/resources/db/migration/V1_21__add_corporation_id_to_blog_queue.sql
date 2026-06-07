ALTER TABLE corporation_blog_queue
    ADD COLUMN IF NOT EXISTS corporation_id BIGINT REFERENCES corporation(id);
