-- Failed generations survive restarts and use backoff. A changed content/model key
-- is immediately eligible, so a bad old document cannot block an edited document.
CREATE TABLE semantic_embedding_retries (
    source_type VARCHAR(30) NOT NULL,
    source_id BIGINT NOT NULL,
    generation_key VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0),
    retry_after TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(500) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_type, source_id),
    CHECK (source_type IN ('FAQ', 'CHANNEL_POST'))
);

CREATE INDEX idx_semantic_embedding_retries_due ON semantic_embedding_retries (retry_after);
