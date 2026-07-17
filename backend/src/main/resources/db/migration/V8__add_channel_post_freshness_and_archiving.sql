ALTER TABLE telegram_channel_posts
    ADD COLUMN freshness_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN freshness_reason TEXT,
    ADD COLUMN freshness_checked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN archive_reason TEXT;

ALTER TABLE telegram_channel_posts
    ADD CONSTRAINT chk_channel_post_freshness_status
        CHECK (
            freshness_status IN (
                                 'ACTIVE',
                                 'UNKNOWN',
                                 'EXPIRED',
                                 'INVALID'
                )
            );

ALTER TABLE telegram_channel_posts
    ADD CONSTRAINT chk_channel_post_archived_state
        CHECK (
            (
                is_archived = FALSE
                    AND archived_at IS NULL
                )
                OR
            (
                is_archived = TRUE
                    AND archived_at IS NOT NULL
                )
            );

CREATE INDEX idx_channel_posts_freshness_and_archive
    ON telegram_channel_posts (
                               is_archived,
                               freshness_status
        );

CREATE INDEX idx_channel_posts_expires_at
    ON telegram_channel_posts (expires_at);