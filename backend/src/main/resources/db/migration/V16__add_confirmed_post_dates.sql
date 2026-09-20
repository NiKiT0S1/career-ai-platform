ALTER TABLE telegram_channel_posts
    ADD COLUMN confirmed_date date,
    ADD COLUMN confirmed_date_boundary varchar(30),
    ADD COLUMN confirmed_date_purpose varchar(40),
    ADD COLUMN confirmed_date_source_hash varchar(64),
    ADD COLUMN date_confirmed_by bigint,
    ADD COLUMN date_confirmed_at timestamptz,
    ADD COLUMN date_confirmation_reason text,
    ADD CONSTRAINT ck_confirmed_post_date CHECK (
        (confirmed_date IS NULL AND confirmed_date_boundary IS NULL AND confirmed_date_purpose IS NULL
            AND confirmed_date_source_hash IS NULL AND date_confirmed_by IS NULL
            AND date_confirmed_at IS NULL AND date_confirmation_reason IS NULL)
        OR
        (confirmed_date IS NOT NULL AND confirmed_date_boundary IS NOT NULL
            AND confirmed_date_boundary IN ('INCLUSIVE', 'EXCLUSIVE') AND confirmed_date_purpose IS NOT NULL
            AND confirmed_date_purpose IN ('APPLICATION_DEADLINE', 'EVENT_DATE', 'PRACTICE_END')
            AND confirmed_date_source_hash IS NOT NULL AND length(confirmed_date_source_hash) = 64
            AND date_confirmed_by IS NOT NULL AND date_confirmed_at IS NOT NULL
            AND date_confirmation_reason IS NOT NULL)
    );

-- Do not preserve ACTIVE/EXPIRED decisions made using the old inferred-year rule.
-- The normal freshness recalculation will evaluate explicit dates again from source.
UPDATE telegram_channel_posts
SET freshness_status = 'UNKNOWN', expires_at = NULL, freshness_checked_at = NULL,
    freshness_reason = 'Политика обработки дат обновлена; ожидается повторная проверка исходного текста',
    revision = revision + 1;
