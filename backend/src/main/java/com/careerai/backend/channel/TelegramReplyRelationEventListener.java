package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/**
 * Запускает обработку reply-поста
 * только после успешного commit.
 */

@Component
public class TelegramReplyRelationEventListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramReplyRelationEventListener.class);

    private final TelegramReplyRelationCoordinator coordinator;

    public TelegramReplyRelationEventListener(
            TelegramReplyRelationCoordinator coordinator
    ) {
        this.coordinator = coordinator;
    }

    @Async("channelRelationExecutor")
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void handleSavedPost(TelegramChannelPostSavedEvent event) {
        try {
            coordinator.processSavedPost(event.postId());
        }
        catch (Exception e) {
            log.error("Telegram reply relation event processing failed. postId={}", event.postId(), e);
        }
    }
}