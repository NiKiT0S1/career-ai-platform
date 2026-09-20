package com.careerai.backend.channel;

import com.careerai.backend.semantic.ChannelPostSemanticIndexedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = {"careerai.background.enabled", "channel-post-relations.standalone-enabled"}, havingValue = "true", matchIfMissing = true)
public class StandaloneRelationBackgroundWorker {
    private static final Logger log = LoggerFactory.getLogger(StandaloneRelationBackgroundWorker.class);
    private final StandaloneRelationService service;
    private final TelegramChannelPostRepository posts;
    private final AtomicBoolean running = new AtomicBoolean();

    public StandaloneRelationBackgroundWorker(StandaloneRelationService service, TelegramChannelPostRepository posts) {
        this.service = service; this.posts = posts;
    }

    @Async("channelRelationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void saved(TelegramChannelPostSavedEvent event) { discoverAndProcess(event.postId()); }

    @Async("channelRelationExecutor")
    @EventListener
    public void indexed(ChannelPostSemanticIndexedEvent event) { discoverAndProcess(event.postId()); }

    private void discoverAndProcess(long postId) {
        try {
            for (Long candidateId : service.discover(postId)) service.process(candidateId);
        } catch (Exception e) {
            log.warn("Standalone relation processing deferred to reconciliation. postId={}", postId, e);
        }
    }

    @Scheduled(fixedDelayString = "${channel-post-relations.standalone-reconcile-ms:600000}",
            initialDelayString = "${channel-post-relations.standalone-initial-delay-ms:60000}")
    public void reconcile() {
        if (!running.compareAndSet(false, true)) return;
        try {
            // Discover metadata-only candidates too, even when embedding generation is disabled or unavailable.
            for (TelegramChannelPost post : posts.findLatestTextPosts(PageRequest.of(0, 100))) {
                try { service.discover(post.getId()); }
                catch (Exception e) { log.warn("Standalone candidate discovery failed. postId={}", post.getId(), e); }
            }
            service.processPending(10);
        } finally { running.set(false); }
    }
}
