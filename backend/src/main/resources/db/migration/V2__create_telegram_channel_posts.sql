CREATE TABLE telegram_channel_posts (
                                        id BIGSERIAL PRIMARY KEY,

                                        telegram_chat_id BIGINT NOT NULL,
                                        telegram_message_id BIGINT NOT NULL,

                                        channel_title VARCHAR(255),
                                        channel_username VARCHAR(255),

                                        text TEXT,
                                        raw_update_json TEXT,

                                        posted_at TIMESTAMPTZ,
                                        edited_at TIMESTAMPTZ,

                                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                        CONSTRAINT uk_telegram_channel_post UNIQUE (telegram_chat_id, telegram_message_id)
);