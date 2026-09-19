ALTER TABLE telegram_channel_post_relations DROP CONSTRAINT IF EXISTS chk_channel_post_relation_origin;
ALTER TABLE telegram_channel_post_relations ADD CONSTRAINT chk_channel_post_relation_origin
    CHECK (relation_origin IN ('MANUAL', 'TELEGRAM_REPLY', 'ADMIN_CONFIRMED', 'SYSTEM_BACKFILL', 'STANDALONE_INFERRED'));

CREATE TABLE standalone_relation_candidates (
    id BIGSERIAL PRIMARY KEY,
    source_post_id BIGINT NOT NULL REFERENCES telegram_channel_posts(id) ON DELETE CASCADE,
    target_post_id BIGINT NOT NULL REFERENCES telegram_channel_posts(id) ON DELETE CASCADE,
    input_hash VARCHAR(64) NOT NULL,
    source_snapshot TEXT NOT NULL, target_snapshot TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    selection_score DOUBLE PRECISION NOT NULL,
    auto_approval_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    selection_reason TEXT,
    proposed_type VARCHAR(30), reason TEXT, confidence DOUBLE PRECISION,
    provider VARCHAR(100), model VARCHAR(150), raw_response TEXT, error TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ, relation_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_standalone_candidate_input UNIQUE (source_post_id, target_post_id, input_hash),
    CONSTRAINT chk_standalone_different_posts CHECK (source_post_id <> target_post_id),
    CONSTRAINT chk_standalone_status CHECK (status IN ('PENDING', 'PROCESSING', 'REVIEW_REQUIRED', 'AUTO_APPROVED', 'APPROVED', 'REJECTED', 'NO_RELATION', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT chk_standalone_attempts CHECK (attempt_count BETWEEN 0 AND 3),
    CONSTRAINT chk_standalone_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_standalone_type CHECK (proposed_type IS NULL OR proposed_type IN ('UNCLASSIFIED','UPDATE','CORRECTION','CANCELLATION','MIXED'))
);
CREATE INDEX idx_standalone_ready ON standalone_relation_candidates(status, next_attempt_at, id);
CREATE INDEX idx_standalone_target ON standalone_relation_candidates(target_post_id);

CREATE TABLE standalone_relation_audit (
    id BIGSERIAL PRIMARY KEY,
    candidate_id BIGINT NOT NULL REFERENCES standalone_relation_candidates(id) ON DELETE CASCADE,
    action VARCHAR(30) NOT NULL, actor VARCHAR(200) NOT NULL, detail TEXT,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_standalone_audit_candidate ON standalone_relation_audit(candidate_id, id);
