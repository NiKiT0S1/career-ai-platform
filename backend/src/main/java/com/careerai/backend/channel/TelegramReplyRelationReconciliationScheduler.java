package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition
        .ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Периодически восстанавливает пропущенные
 * или не завершившиеся задачи связей.
 */

@Component
@ConditionalOnProperty(
        prefix = "channel-post-relations",
        name = "reconciliation-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TelegramReplyRelationReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TelegramReplyRelationReconciliationScheduler.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final TelegramReplyRelationCoordinator coordinator;

    public TelegramReplyRelationReconciliationScheduler(
            TelegramReplyRelationCoordinator coordinator
    ) {
        this.coordinator = coordinator;
    }

    @Scheduled(
            cron = "${channel-post-relations.reconciliation-cron:0 */10 * * * *}",
            zone = "${careerai.time-zone:Asia/Almaty}"
    )
    public void reconcile() {
        if (!running.compareAndSet(
                false,
                true
        )) {
            log.info("Telegram reply relation reconciliation skipped: previous run is still active");
            return;
        }

        try {
            coordinator.reconcile();
        }
        catch (Exception e) {
            log.error("Telegram reply relation reconciliation failed", e);
        }
        finally {
            running.set(false);
        }
    }
}