package com.careerai.backend.semantic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@ConditionalOnProperty(name = "careerai.background.enabled", havingValue = "true", matchIfMissing = true)
public class FaqSemanticIndexer {
    private final SemanticEmbeddingLifecycleService lifecycle;

    public FaqSemanticIndexer(SemanticEmbeddingLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Async("channelPostIndexingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleFaqChanged(FaqEntryChangedEvent event) {
        lifecycle.indexFaqEntry(event.entryId(), false);
    }
}
