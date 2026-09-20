package com.careerai.backend.semantic;

public enum SemanticIndexingOutcome {
    INDEXED, UNCHANGED, DELETED, FAILED, BACKOFF, BUSY, STALE, DISABLED
}
