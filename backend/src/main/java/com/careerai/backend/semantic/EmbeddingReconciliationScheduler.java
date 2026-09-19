package com.careerai.backend.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "careerai.background.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingReconciliationScheduler.class);
    private final SemanticEmbeddingLifecycleService lifecycle;
    private final Executor executor;
    private final AtomicBoolean queued = new AtomicBoolean();

    public EmbeddingReconciliationScheduler(SemanticEmbeddingLifecycleService lifecycle,
            @Qualifier("channelPostIndexingExecutor") Executor executor) {
        this.lifecycle = lifecycle;
        this.executor = executor;
    }

    /** ApplicationReady never blocks startup on provider requests or a full corpus scan. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        lifecycle.resetScan();
    }

    @Scheduled(initialDelayString = "${semantic-search.lifecycle.initial-delay-ms:60000}",
            fixedDelayString = "${semantic-search.lifecycle.fixed-delay-ms:60000}")
    public void reconcile() {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    lifecycle.reconcileBatch();
                } catch (Exception e) {
                    log.error("Embedding reconciliation batch failed; the next scheduled run will retry", e);
                } finally {
                    queued.set(false);
                }
            });
        } catch (RuntimeException e) {
            queued.set(false);
            log.warn("Embedding reconciliation could not be queued; will retry on the next tick", e);
        }
    }
}
