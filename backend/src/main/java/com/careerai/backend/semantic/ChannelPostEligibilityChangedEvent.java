package com.careerai.backend.semantic;

/** A freshness or archive change can remove or recreate a document embedding. */
public record ChannelPostEligibilityChangedEvent(long postId) {
}
