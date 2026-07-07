CREATE TABLE telegram_channel_post_metadata (
                                                id BIGSERIAL PRIMARY KEY,

                                                post_id BIGINT NOT NULL,

                                                post_type VARCHAR(50) NOT NULL DEFAULT 'OTHER',

                                                title VARCHAR(500),
                                                company VARCHAR(500),
                                                technologies TEXT,
                                                level_text VARCHAR(255),
                                                format_text VARCHAR(255),

                                                deadline_text VARCHAR(500),
                                                practice_start_text VARCHAR(255),
                                                practice_end_text VARCHAR(255),

                                                summary TEXT,

                                                is_relevant_for_practice BOOLEAN NOT NULL DEFAULT FALSE,

                                                extraction_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
                                                extraction_error TEXT,
                                                extracted_at TIMESTAMPTZ,

                                                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                                CONSTRAINT uk_telegram_channel_post_metadata_post_id UNIQUE (post_id),

                                                CONSTRAINT fk_telegram_channel_post_metadata_post
                                                    FOREIGN KEY (post_id)
                                                        REFERENCES telegram_channel_posts (id)
                                                        ON DELETE CASCADE
);

CREATE INDEX idx_telegram_channel_post_metadata_post_type
    ON telegram_channel_post_metadata (post_type);

CREATE INDEX idx_telegram_channel_post_metadata_extraction_status
    ON telegram_channel_post_metadata (extraction_status);