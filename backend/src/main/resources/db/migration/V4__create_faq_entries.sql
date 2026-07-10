CREATE TABLE faq_entries (
                             id BIGSERIAL PRIMARY KEY,

                             category VARCHAR(100) NOT NULL,
                             slug VARCHAR(150) NOT NULL,

                             question TEXT NOT NULL,
                             short_answer TEXT NOT NULL,
                             full_answer TEXT NOT NULL,
                             keywords TEXT,

                             priority INTEGER NOT NULL DEFAULT 100,
                             is_active BOOLEAN NOT NULL DEFAULT TRUE,

                             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                             updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                             CONSTRAINT uk_faq_entries_slug UNIQUE (slug)
);

CREATE INDEX idx_faq_entries_category
    ON faq_entries (category);

CREATE INDEX idx_faq_entries_is_active
    ON faq_entries (is_active);

CREATE INDEX idx_faq_entries_priority
    ON faq_entries (priority);