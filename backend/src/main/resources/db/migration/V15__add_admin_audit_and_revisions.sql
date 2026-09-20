ALTER TABLE telegram_channel_posts ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE telegram_channel_post_metadata ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE faq_entries ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE admin_audit_events (
    id BIGSERIAL PRIMARY KEY,
    actor_telegram_id BIGINT NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT,
    details TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_admin_audit_events_time ON admin_audit_events (occurred_at DESC, id DESC);
