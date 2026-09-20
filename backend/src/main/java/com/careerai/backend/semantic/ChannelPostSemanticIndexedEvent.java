package com.careerai.backend.semantic;

/** Published only after a usable embedding has been stored. */
public record ChannelPostSemanticIndexedEvent(long postId) {
}
