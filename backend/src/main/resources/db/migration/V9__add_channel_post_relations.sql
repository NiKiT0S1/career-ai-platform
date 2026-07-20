CREATE TABLE telegram_channel_post_relations
(
    id BIGSERIAL PRIMARY KEY,

    source_post_id BIGINT NOT NULL,
    target_post_id BIGINT NOT NULL,

    relation_type VARCHAR(30) NOT NULL,
    reason TEXT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_channel_post_relation_source
        FOREIGN KEY (source_post_id)
            REFERENCES telegram_channel_posts (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_channel_post_relation_target
        FOREIGN KEY (target_post_id)
            REFERENCES telegram_channel_posts (id)
            ON DELETE CASCADE,

    CONSTRAINT chk_channel_post_relation_type
        CHECK (
            relation_type IN (
                              'UPDATE',
                              'CORRECTION',
                              'CANCELLATION'
                )
            ),

    CONSTRAINT chk_channel_post_relation_different_posts
        CHECK (source_post_id <> target_post_id),

    CONSTRAINT uk_channel_post_relation
        UNIQUE (
                source_post_id,
                target_post_id,
                relation_type
            )
);

CREATE INDEX idx_channel_post_relation_source
    ON telegram_channel_post_relations (source_post_id);

CREATE INDEX idx_channel_post_relation_target
    ON telegram_channel_post_relations (target_post_id);