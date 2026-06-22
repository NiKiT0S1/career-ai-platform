CREATE TABLE telegram_users (
                                id BIGSERIAL PRIMARY KEY,
                                telegram_user_id BIGINT NOT NULL UNIQUE,
                                username VARCHAR(255),
                                first_name VARCHAR(255),
                                last_name VARCHAR(255),
                                language_code VARCHAR(20),
                                is_bot BOOLEAN NOT NULL DEFAULT FALSE,
                                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_messages (
                               id BIGSERIAL PRIMARY KEY,
                               telegram_user_id BIGINT REFERENCES telegram_users(id),
                               telegram_chat_id BIGINT NOT NULL,
                               role VARCHAR(30) NOT NULL,
                               text TEXT NOT NULL,
                               telegram_message_id BIGINT,
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE bot_runtime_state (
                                   id BIGSERIAL PRIMARY KEY,
                                   state_key VARCHAR(255) NOT NULL UNIQUE,
                                   state_value TEXT NOT NULL,
                                   updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE import_batches (
                                id BIGSERIAL PRIMARY KEY,
                                source_type VARCHAR(50) NOT NULL,
                                original_file_name VARCHAR(500),
                                status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
                                total_items INTEGER NOT NULL DEFAULT 0,
                                successful_items INTEGER NOT NULL DEFAULT 0,
                                failed_items INTEGER NOT NULL DEFAULT 0,
                                error_message TEXT,
                                started_at TIMESTAMP,
                                completed_at TIMESTAMP,
                                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_sources (
                                   id BIGSERIAL PRIMARY KEY,
                                   source_type VARCHAR(50) NOT NULL,
                                   title VARCHAR(500),
                                   description TEXT,
                                   external_id VARCHAR(500),
                                   source_url TEXT,
                                   import_batch_id BIGINT REFERENCES import_batches(id),
                                   status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                                   created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE telegram_channel_posts (
                                        id BIGSERIAL PRIMARY KEY,
                                        source_id BIGINT REFERENCES knowledge_sources(id),
                                        channel_id BIGINT NOT NULL,
                                        message_id BIGINT NOT NULL,
                                        text TEXT,
                                        caption TEXT,
                                        published_at TIMESTAMP NOT NULL,
                                        edited_at TIMESTAMP,
                                        raw_json TEXT,
                                        status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        CONSTRAINT uq_telegram_channel_post UNIQUE (channel_id, message_id)
);

CREATE TABLE documents (
                           id BIGSERIAL PRIMARY KEY,
                           source_id BIGINT REFERENCES knowledge_sources(id),
                           import_batch_id BIGINT REFERENCES import_batches(id),
                           original_file_name VARCHAR(500),
                           stored_file_name VARCHAR(500),
                           mime_type VARCHAR(255),
                           file_size BIGINT,
                           storage_path TEXT,
                           extracted_text TEXT,
                           status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_items (
                                 id BIGSERIAL PRIMARY KEY,
                                 source_id BIGINT REFERENCES knowledge_sources(id),
                                 document_id BIGINT REFERENCES documents(id),
                                 telegram_post_id BIGINT REFERENCES telegram_channel_posts(id),

                                 category VARCHAR(50) NOT NULL,
                                 title VARCHAR(500),
                                 content TEXT NOT NULL,

                                 published_at TIMESTAMP,
                                 valid_from TIMESTAMP,
                                 valid_until TIMESTAMP,

                                 status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                                 freshness_policy VARCHAR(50) NOT NULL DEFAULT 'DEFAULT_TTL',
                                 default_ttl_days INTEGER,
                                 needs_review BOOLEAN NOT NULL DEFAULT FALSE,

                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE knowledge_freshness_rules (
                                           id BIGSERIAL PRIMARY KEY,
                                           category VARCHAR(50) NOT NULL UNIQUE,
                                           default_ttl_days INTEGER,
                                           permanent BOOLEAN NOT NULL DEFAULT FALSE,
                                           description TEXT,
                                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                           updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO knowledge_freshness_rules (category, default_ttl_days, permanent, description)
VALUES
    ('VACANCY', 30, FALSE, 'Vacancies without explicit deadline are considered relevant for 30 days after publication.'),
    ('INTERNSHIP', 45, FALSE, 'Internships without explicit deadline are considered relevant for 45 days after publication.'),
    ('EVENT', 14, FALSE, 'Events without explicit date are considered relevant for 14 days after publication.'),
    ('MASTERCLASS', 14, FALSE, 'Masterclasses without explicit date are considered relevant for 14 days after publication.'),
    ('DEADLINE', NULL, FALSE, 'Deadlines require explicit date extraction or manual review.'),
    ('FAQ', NULL, TRUE, 'FAQ items are permanent until manually archived.'),
    ('PRACTICE_INFO', NULL, TRUE, 'Practice-related instructions are permanent until manually archived.'),
    ('DOCUMENT', NULL, TRUE, 'Documents are permanent until manually archived.'),
    ('GENERAL_INFO', 90, FALSE, 'General information is considered relevant for 90 days unless marked permanent.');

CREATE INDEX idx_chat_messages_telegram_chat_id ON chat_messages (telegram_chat_id);
CREATE INDEX idx_chat_messages_created_at ON chat_messages (created_at);

CREATE INDEX idx_knowledge_items_category ON knowledge_items (category);
CREATE INDEX idx_knowledge_items_status ON knowledge_items (status);
CREATE INDEX idx_knowledge_items_valid_until ON knowledge_items (valid_until);
CREATE INDEX idx_knowledge_items_published_at ON knowledge_items (published_at);

CREATE INDEX idx_telegram_channel_posts_channel_message ON telegram_channel_posts (channel_id, message_id);
CREATE INDEX idx_telegram_channel_posts_published_at ON telegram_channel_posts (published_at);

CREATE INDEX idx_documents_status ON documents (status);
CREATE INDEX idx_knowledge_sources_source_type ON knowledge_sources (source_type);