ALTER TABLE corporation_blog_queue
    ADD COLUMN home_link     VARCHAR(500),
    ADD COLUMN logo_url      VARCHAR(500),
    ADD COLUMN crew_link     VARCHAR(500),
    ADD COLUMN alternate_name VARCHAR(100),
    ADD COLUMN is_domestic   INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN blog_type     VARCHAR(20) NOT NULL DEFAULT 'DEFAULT';
