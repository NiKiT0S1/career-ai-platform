package com.careerai.backend.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.careerai.backend.semantic.SemanticIndexingOutcome.*;
import static com.careerai.backend.semantic.SemanticSourceType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticEmbeddingLifecycleServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-19T10:00:00Z");
    private static final SemanticDocumentSnapshot DOCUMENT = new SemanticDocumentSnapshot("Title", "Text", "hash", true);
    @Mock SemanticLifecycleRepository sources;
    @Mock SemanticEmbeddingRepository embeddings;
    @Mock EmbeddingProvider provider;
    @Mock ApplicationEventPublisher events;
    private SemanticEmbeddingLifecycleService service;
    private SemanticSearchProperties search;
    private EmbeddingLifecycleProperties properties;
    private final SemanticContentHashService hashes = new SemanticContentHashService();

    @BeforeEach
    void setUp() {
        search = new SemanticSearchProperties();
        ReflectionTestUtils.setField(search, "enabled", true);
        ReflectionTestUtils.setField(search, "embeddingModel", "test-model");
        ReflectionTestUtils.setField(search, "outputDimensions", 2);
        properties = new EmbeddingLifecycleProperties();
        ReflectionTestUtils.setField(properties, "enabled", true);
        ReflectionTestUtils.setField(properties, "batchSize", 2);
        ReflectionTestUtils.setField(properties, "retryInitialSeconds", 60L);
        ReflectionTestUtils.setField(properties, "retryMaxSeconds", 300L);
        service = new SemanticEmbeddingLifecycleService(search, properties, sources, embeddings, hashes,
                provider, events, Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void unchangedDocumentDoesNotCallProvider() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(embeddings.isUpToDate(CHANNEL_POST, 1, "hash", "test-model", 2)).thenReturn(true);
        assertEquals(UNCHANGED, service.indexChannelPost(1, false));
        verifyNoInteractions(provider, events);
        verify(sources).clearRetry(CHANNEL_POST, 1);
    }

    @Test
    void restoresMissingEmbeddingAndPublishesIndexEvent() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(provider.embedDocument("Title", "Text")).thenReturn(success());
        assertEquals(INDEXED, service.indexChannelPost(1, false));
        verify(embeddings).saveOrUpdate(CHANNEL_POST, 1, "hash", "test-model", new double[]{1, 2});
        verify(events).publishEvent(new ChannelPostSemanticIndexedEvent(1));
    }

    @Test
    void ineligibleOrMissingSourceIsDeletedWithoutProvider() {
        when(embeddings.delete(CHANNEL_POST, 1)).thenReturn(1);
        when(embeddings.delete(FAQ, 2)).thenReturn(1);
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(
                new SemanticDocumentSnapshot("Title", "Text", "hash", false)));
        assertEquals(DELETED, service.indexChannelPost(1, false));
        assertEquals(DELETED, service.indexFaqEntry(2, false));
        verify(embeddings).delete(CHANNEL_POST, 1);
        verify(embeddings).delete(FAQ, 2);
        verifyNoInteractions(provider);
    }

    @Test
    void editDuringProviderCallCannotSaveOldVector() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT), Optional.of(
                new SemanticDocumentSnapshot("Title", "Edited", "new-hash", true)));
        when(provider.embedDocument("Title", "Text")).thenReturn(success());
        assertEquals(STALE, service.indexChannelPost(1, false));
        verify(embeddings, never()).saveOrUpdate(any(), anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(events);
    }

    @Test
    void archiveDuringProviderCallDeletesInsteadOfRecreating() {
        when(embeddings.delete(CHANNEL_POST, 1)).thenReturn(1);
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT), Optional.of(
                new SemanticDocumentSnapshot("Title", "Text", "hash", false)));
        when(provider.embedDocument("Title", "Text")).thenReturn(success());
        assertEquals(DELETED, service.indexChannelPost(1, false));
        verify(embeddings).delete(CHANNEL_POST, 1);
        verify(embeddings, never()).saveOrUpdate(any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void retryBackoffSurvivesServiceRestartAndSkipsProvider() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(sources.findRetry(CHANNEL_POST, 1)).thenReturn(Optional.of(
                new SemanticLifecycleRepository.RetryState(key("hash"), 3, NOW.plusSeconds(120))));
        assertEquals(BACKOFF, service.indexChannelPost(1, false));
        verifyNoInteractions(provider, embeddings);
    }

    @Test
    void changedContentBypassesPreviousFailures() {
        when(sources.findDocument(FAQ, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(sources.findRetry(FAQ, 1)).thenReturn(Optional.of(
                new SemanticLifecycleRepository.RetryState(key("old-hash"), 3, NOW.plusSeconds(120))));
        when(provider.embedDocument("Title", "Text")).thenReturn(success());
        assertEquals(INDEXED, service.indexFaqEntry(1, false));
        verifyNoInteractions(events);
    }

    @Test
    void invalidModelIsNotStoredAndSchedulesRetry() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(provider.embedDocument("Title", "Text")).thenReturn(
                EmbeddingResult.success(new double[]{1, 2}, "other-model", 1));
        assertEquals(FAILED, service.indexChannelPost(1, false));
        verify(sources).recordFailure(eq(CHANNEL_POST), eq(1L), eq(key("hash")), eq(1),
                eq(NOW.plusSeconds(60)), anyString());
        verify(embeddings, never()).saveOrUpdate(any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void invalidDimensionAndNonFiniteVectorAreNotStored() {
        when(sources.findDocument(FAQ, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(provider.embedDocument("Title", "Text")).thenReturn(
                EmbeddingResult.success(new double[]{1}, "test-model", 1),
                EmbeddingResult.success(new double[]{Double.NaN, 2}, "test-model", 1));
        assertEquals(FAILED, service.indexFaqEntry(1, false));
        assertEquals(FAILED, service.indexFaqEntry(1, true));
        verify(embeddings, never()).saveOrUpdate(any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void repeatedFailureBackoffIsCapped() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(sources.findRetry(CHANNEL_POST, 1)).thenReturn(Optional.of(
                new SemanticLifecycleRepository.RetryState(key("hash"), 15, NOW.minusSeconds(1))));
        when(provider.embedDocument("Title", "Text")).thenReturn(EmbeddingResult.failure("test-model", 1, "rate limited"));
        assertEquals(FAILED, service.indexChannelPost(1, false));
        verify(sources).recordFailure(CHANNEL_POST, 1, key("hash"), 16, NOW.plusSeconds(300), "rate limited");
    }

    @Test
    void forcedReindexIgnoresUpToDateAndBackoff() {
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(sources.findRetry(CHANNEL_POST, 1)).thenReturn(Optional.of(
                new SemanticLifecycleRepository.RetryState(key("hash"), 3, NOW.plusSeconds(120))));
        when(provider.embedDocument("Title", "Text")).thenReturn(success());
        assertEquals(INDEXED, service.indexChannelPost(1, true));
        verify(embeddings, never()).isUpToDate(any(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void boundedSweepAdvancesCursorAndCleansOrphans() {
        when(sources.findIdsAfter(CHANNEL_POST, 0, 2)).thenReturn(List.of(1L, 2L));
        when(sources.findIdsAfter(CHANNEL_POST, 2, 2)).thenReturn(List.of(3L));
        when(sources.findIdsAfter(FAQ, 0, 2)).thenReturn(List.of());
        when(sources.deleteOrphans(2)).thenReturn(1);
        EmbeddingReconciliationResult first = service.reconcileBatch();
        assertEquals(2, first.examined());
        assertEquals(2, first.channelCursor());
        assertEquals(1, first.orphanedDeleted());
        EmbeddingReconciliationResult second = service.reconcileBatch();
        assertEquals(1, second.examined());
        assertEquals(0, second.channelCursor());
        verifyNoInteractions(provider);
    }

    @Test
    void concurrentEventDoesNotGenerateTheSameDocumentTwice() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(sources.findDocument(CHANNEL_POST, 1, NOW)).thenReturn(Optional.of(DOCUMENT));
        when(provider.embedDocument("Title", "Text")).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return success();
        });
        CompletableFuture<SemanticIndexingOutcome> first = CompletableFuture.supplyAsync(() -> service.indexChannelPost(1, false));
        try {
            assertTrue(providerEntered.await(5, TimeUnit.SECONDS));
            assertEquals(BUSY, service.indexChannelPost(1, false));
        } finally {
            release.countDown();
        }
        assertEquals(INDEXED, first.get(5, TimeUnit.SECONDS));
        verify(provider, times(1)).embedDocument("Title", "Text");
    }

    @Test
    void disabledSearchHasNoDatabaseOrProviderSideEffects() {
        ReflectionTestUtils.setField(search, "enabled", false);
        assertEquals(DISABLED, service.indexChannelPost(1, false));
        assertFalse(service.reconcileBatch().enabled());
        verifyNoInteractions(sources, embeddings, provider, events);
    }

    private String key(String hash) { return hashes.calculateHash(hash + "\ntest-model\n2"); }
    private EmbeddingResult success() { return EmbeddingResult.success(new double[]{1, 2}, "test-model", 1); }
}
