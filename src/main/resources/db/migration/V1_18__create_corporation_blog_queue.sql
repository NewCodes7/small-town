CREATE TABLE corporation_blog_queue (
    id BIGSERIAL PRIMARY KEY,
    blog_url VARCHAR(500) NOT NULL,
    company_name VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    analysis_result TEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_corp_blog_queue_status ON corporation_blog_queue (status);
CREATE INDEX idx_corp_blog_queue_created_at ON corporation_blog_queue (created_at DESC);
