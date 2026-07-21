ALTER TABLE telegram_channel_posts
    ADD COLUMN reply_to_telegram_message_id BIGINT;

CREATE INDEX idx_channel_posts_reply_reference
    ON telegram_channel_posts
        (
         telegram_chat_id,
         reply_to_telegram_message_id
            )
    WHERE reply_to_telegram_message_id IS NOT NULL;


ALTER TABLE telegram_channel_post_relations
    ADD COLUMN relation_origin VARCHAR(30)
        NOT NULL
        DEFAULT 'MANUAL';

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN classification_confidence
        DOUBLE PRECISION;

ALTER TABLE telegram_channel_post_relations
    ADD COLUMN updated_at TIMESTAMPTZ
        NOT NULL
        DEFAULT NOW();


ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_origin
        CHECK (
            relation_origin IN (
                                'MANUAL',
                                'TELEGRAM_REPLY',
                                'ADMIN_CONFIRMED',
                                'SYSTEM_BACKFILL'
                )
            );

ALTER TABLE telegram_channel_post_relations
    ADD CONSTRAINT chk_channel_post_relation_confidence
        CHECK (
            classification_confidence IS NULL
                OR (
                classification_confidence >= 0.0
                    AND classification_confidence <= 1.0
                )
            );