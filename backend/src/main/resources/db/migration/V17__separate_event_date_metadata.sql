ALTER TABLE telegram_channel_post_metadata
    ADD COLUMN event_date_text VARCHAR(500);

-- Existing summaries conflated event dates and registration deadlines. Re-extract from
-- the retained source; historical knowledge is not deleted and embeddings are reconciled.
UPDATE telegram_channel_post_metadata
SET extraction_status = 'PENDING', extraction_error = NULL, extracted_at = NULL,
    revision = revision + 1
WHERE post_type = 'EVENT';
