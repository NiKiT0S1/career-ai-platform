CREATE TABLE semantic_embeddings
(
    id                   BIGSERIAL PRIMARY KEY,
    source_type          VARCHAR(30) NOT NULL,
    source_id            BIGINT NOT NULL,
    content_hash         VARCHAR(64) NOT NULL,
    embedding_model      VARCHAR(100) NOT NULL,
    embedding_dimensions INTEGER NOT NULL,
    embedding            DOUBLE PRECISION[] NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_semantic_embeddings_source
        UNIQUE (source_type, source_id),

    CONSTRAINT chk_semantic_embeddings_source_type
        CHECK (source_type IN ('FAQ', 'CHANNEL_POST')),

    CONSTRAINT chk_semantic_embeddings_dimensions
        CHECK (embedding_dimensions > 0)
);

CREATE INDEX idx_semantic_embeddings_source_type
    ON semantic_embeddings (source_type);

CREATE INDEX idx_semantic_embeddings_source_id
    ON semantic_embeddings (source_id);