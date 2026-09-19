package com.careerai.backend.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** API calls happen without a database transaction. Every save rechecks the source snapshot. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class SemanticEmbeddingLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(SemanticEmbeddingLifecycleService.class);
    private final SemanticSearchProperties search;
    private final EmbeddingLifecycleProperties properties;
    private final SemanticLifecycleRepository sources;
    private final SemanticEmbeddingRepository embeddings;
    private final SemanticContentHashService hashes;
    private final EmbeddingProvider provider;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AtomicBoolean reconciling = new AtomicBoolean();
    private final AtomicLong channelCursor = new AtomicLong();
    private final AtomicLong faqCursor = new AtomicLong();
    // Fixed-size stripes bound memory and protect both event and reconciliation paths.
    private final ReentrantLock[] locks = new ReentrantLock[64];

    public SemanticEmbeddingLifecycleService(SemanticSearchProperties search, EmbeddingLifecycleProperties properties,
            SemanticLifecycleRepository sources, SemanticEmbeddingRepository embeddings, SemanticContentHashService hashes,
            EmbeddingProvider provider, ApplicationEventPublisher events, Clock clock) {
        this.search = search;
        this.properties = properties;
        this.sources = sources;
        this.embeddings = embeddings;
        this.hashes = hashes;
        this.provider = provider;
        this.events = events;
        this.clock = clock;
        Arrays.setAll(locks, i -> new ReentrantLock());
    }

    public SemanticIndexingOutcome indexChannelPost(long id, boolean force) {
        return index(SemanticSourceType.CHANNEL_POST, id, force);
    }

    public SemanticIndexingOutcome indexFaqEntry(long id, boolean force) {
        return index(SemanticSourceType.FAQ, id, force);
    }

    /** Restart a keyset sweep; this method makes no external requests. */
    public void resetScan() {
        channelCursor.set(0);
        faqCursor.set(0);
    }

    public EmbeddingReconciliationResult reconcileBatch() {
        if (!search.isEnabled() || !properties.isEnabled()) {
            return summary(false, false, Map.of(), 0);
        }
        if (!reconciling.compareAndSet(false, true)) {
            return summary(true, true, Map.of(), 0);
        }
        try {
            int limit = Math.max(1, Math.min(100, properties.getBatchSize()));
            int orphans = sources.deleteOrphans(limit);
            Map<SemanticIndexingOutcome, Integer> counts = new EnumMap<>(SemanticIndexingOutcome.class);
            int unavailable = sources.deleteUnavailable(limit, now());
            reconcileSource(SemanticSourceType.CHANNEL_POST, channelCursor, limit, counts);
            reconcileSource(SemanticSourceType.FAQ, faqCursor, limit, counts);
            EmbeddingReconciliationResult batch = summary(false, true, counts, orphans);
            EmbeddingReconciliationResult result = new EmbeddingReconciliationResult(batch.running(), batch.enabled(),
                    batch.examined(), batch.indexed(), batch.deleted() + unavailable, batch.failed(), batch.deferred(),
                    batch.unchanged(), batch.orphanedDeleted(), batch.channelCursor(), batch.faqCursor());
            log.info("Embedding reconciliation batch: examined={}, indexed={}, deleted={}, failed={}, deferred={}, orphans={}",
                    result.examined(), result.indexed(), result.deleted(), result.failed(), result.deferred(), orphans);
            return result;
        } finally {
            reconciling.set(false);
        }
    }

    private void reconcileSource(SemanticSourceType type, AtomicLong cursor, int limit,
                                 Map<SemanticIndexingOutcome, Integer> counts) {
        long start = cursor.get();
        List<Long> ids = sources.findIdsAfter(type, start, limit);
        for (Long id : ids) {
            counts.merge(index(type, id, false), 1, Integer::sum);
        }
        long next = ids.size() < limit ? 0 : ids.getLast();
        // Do not overwrite an admin reset performed during a slow generation.
        cursor.compareAndSet(start, next);
    }

    private SemanticIndexingOutcome index(SemanticSourceType type, long id, boolean force) {
        if (!search.isEnabled()) {
            return SemanticIndexingOutcome.DISABLED;
        }
        ReentrantLock lock = locks[Math.floorMod(Objects.hash(type, id), locks.length)];
        if (!lock.tryLock()) {
            return SemanticIndexingOutcome.BUSY;
        }
        String generationKey = null;
        int previousAttempts = 0;
        try {
            SemanticDocumentSnapshot source = sources.findDocument(type, id, now()).orElse(null);
            if (source == null || !source.searchable()) {
                return remove(type, id);
            }
            generationKey = hashes.calculateHash(source.hash() + "\n" + search.getEmbeddingModel()
                    + "\n" + search.getOutputDimensions());
            var retry = sources.findRetry(type, id).orElse(null);
            if (retry != null && generationKey.equals(retry.generationKey())) {
                previousAttempts = retry.attempts();
                if (!force && now().isBefore(retry.retryAfter())) {
                    return SemanticIndexingOutcome.BACKOFF;
                }
            }
            if (!force && embeddings.isUpToDate(type, id, source.hash(),
                    search.getEmbeddingModel(), search.getOutputDimensions())) {
                sources.clearRetry(type, id);
                return SemanticIndexingOutcome.UNCHANGED;
            }

            EmbeddingResult result = provider.embedDocument(source.title(), source.content());
            if (!valid(result)) {
                return failure(type, id, generationKey, previousAttempts,
                        result == null || result.success() ? "Invalid embedding model, dimension or vector" : result.errorMessage());
            }

            // An edit/archive/delete may have committed while Gemini was running.
            SemanticDocumentSnapshot current = sources.findDocument(type, id, now()).orElse(null);
            if (current == null || !current.searchable()) {
                return remove(type, id);
            }
            if (!source.hash().equals(current.hash())) {
                sources.clearRetry(type, id);
                return SemanticIndexingOutcome.STALE;
            }
            embeddings.saveOrUpdate(type, id, source.hash(), result.model(), result.values());
            sources.clearRetry(type, id);
            if (type == SemanticSourceType.CHANNEL_POST) {
                events.publishEvent(new ChannelPostSemanticIndexedEvent(id));
            }
            return SemanticIndexingOutcome.INDEXED;
        } catch (Exception e) {
            log.error("Embedding lifecycle failed. sourceType={}, sourceId={}", type, id, e);
            if (generationKey != null) {
                try {
                    return failure(type, id, generationKey, previousAttempts, e.getClass().getSimpleName());
                } catch (Exception retryFailure) {
                    log.error("Could not persist embedding retry. sourceType={}, sourceId={}", type, id, retryFailure);
                }
            }
            return SemanticIndexingOutcome.FAILED;
        } finally {
            lock.unlock();
        }
    }

    private boolean valid(EmbeddingResult result) {
        return result != null && result.success() && Objects.equals(search.getEmbeddingModel(), result.model())
                && result.values() != null && result.values().length == search.getOutputDimensions()
                && result.values().length > 0 && Arrays.stream(result.values()).allMatch(Double::isFinite)
                && Arrays.stream(result.values()).anyMatch(value -> value != 0.0);
    }

    private SemanticIndexingOutcome remove(SemanticSourceType type, long id) {
        int deleted = embeddings.delete(type, id);
        sources.clearRetry(type, id);
        return deleted > 0 ? SemanticIndexingOutcome.DELETED : SemanticIndexingOutcome.UNCHANGED;
    }

    private SemanticIndexingOutcome failure(SemanticSourceType type, long id, String key, int previous, String error) {
        int attempts = Math.min(31, previous + 1);
        long initial = Math.max(1, properties.getRetryInitialSeconds());
        long max = Math.max(initial, properties.getRetryMaxSeconds());
        long multiplier = 1L << Math.min(30, attempts - 1);
        long delay = initial > max / multiplier ? max : initial * multiplier;
        sources.recordFailure(type, id, key, attempts, now().plusSeconds(delay), error);
        return SemanticIndexingOutcome.FAILED;
    }

    private EmbeddingReconciliationResult summary(boolean running, boolean enabled,
            Map<SemanticIndexingOutcome, Integer> counts, int orphans) {
        return new EmbeddingReconciliationResult(running, enabled, counts.values().stream().mapToInt(Integer::intValue).sum(),
                counts.getOrDefault(SemanticIndexingOutcome.INDEXED, 0),
                counts.getOrDefault(SemanticIndexingOutcome.DELETED, 0),
                counts.getOrDefault(SemanticIndexingOutcome.FAILED, 0),
                counts.getOrDefault(SemanticIndexingOutcome.BACKOFF, 0) + counts.getOrDefault(SemanticIndexingOutcome.BUSY, 0)
                        + counts.getOrDefault(SemanticIndexingOutcome.STALE, 0),
                counts.getOrDefault(SemanticIndexingOutcome.UNCHANGED, 0), orphans, channelCursor.get(), faqCursor.get());
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
}
