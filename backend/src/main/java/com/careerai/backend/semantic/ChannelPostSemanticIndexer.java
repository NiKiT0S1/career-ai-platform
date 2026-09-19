package com.careerai.backend.semantic;

import com.careerai.backend.channel.TelegramChannelPostSavedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Events use the same validation, retry and locking path as scheduled repairs. */
@Service
@ConditionalOnProperty(name = "careerai.background.enabled", havingValue = "true", matchIfMissing = true)
public class ChannelPostSemanticIndexer {
    private final SemanticEmbeddingLifecycleService lifecycle;

    public ChannelPostSemanticIndexer(SemanticEmbeddingLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Async("channelPostIndexingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleSavedPost(TelegramChannelPostSavedEvent event) {
        lifecycle.indexChannelPost(event.postId(), false);
    }

    @Async("channelPostIndexingExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleEligibilityChange(ChannelPostEligibilityChangedEvent event) {
        lifecycle.indexChannelPost(event.postId(), false);
    }
}
