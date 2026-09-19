package com.careerai.backend.semantic;

/** Publish inside the FAQ mutation transaction; indexing runs after commit. */
public record FaqEntryChangedEvent(long entryId) {
}
