package com.careerai.backend.semantic;

/** Counts cover only this bounded batch, never a claim that the entire corpus is repaired. */
public record EmbeddingReconciliationResult(
        boolean running, boolean enabled, int examined, int indexed, int deleted,
        int failed, int deferred, int unchanged, int orphanedDeleted,
        long channelCursor, long faqCursor
) {
}
