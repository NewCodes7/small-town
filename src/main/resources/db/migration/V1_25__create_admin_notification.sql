CREATE TABLE IF NOT EXISTS admin_notification (
    id             BIGSERIAL    PRIMARY KEY,
    type           VARCHAR(40)  NOT NULL,
    title          VARCHAR(300) NOT NULL,
    message        TEXT,
    reference_type VARCHAR(40)  NULL,
    reference_id   BIGINT       NULL,
    is_read        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    read_at        TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_notification_unread  ON admin_notification (is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_notification_created ON admin_notification (created_at DESC);
