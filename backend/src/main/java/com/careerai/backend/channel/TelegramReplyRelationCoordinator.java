package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Координирует подготовку,
 * классификацию и reconciliation.
 */

@Service
public class TelegramReplyRelationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TelegramReplyRelationCoordinator.class);

    private static final int BACKFILL_BATCH_SIZE = 50;

    private static final int RECLASSIFICATION_SCAN_SIZE = 50;

    private static final int CLASSIFICATION_BATCH_SIZE = 20;

    private final TelegramReplyRelationPreparationService preparationService;
    private final TelegramReplyRelationClassificationProcessor classificationProcessor;
    private final TelegramChannelPostRelationClassificationStateService stateService;
    private final TelegramChannelPostRepository postRepository;
    private final TelegramChannelPostRelationRepository relationRepository;

    public TelegramReplyRelationCoordinator(
            TelegramReplyRelationPreparationService preparationService,
            TelegramReplyRelationClassificationProcessor classificationProcessor,
            TelegramChannelPostRelationClassificationStateService stateService,
            TelegramChannelPostRepository postRepository,
            TelegramChannelPostRelationRepository relationRepository
    ) {
        this.preparationService = preparationService;
        this.classificationProcessor = classificationProcessor;
        this.stateService = stateService;
        this.postRepository = postRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * Обрабатывает новый или отредактированный пост.
     */
    public void processSavedPost(Long postId) {
        Optional<Long> relationId = preparationService.prepare(
                        postId,
                        TelegramChannelPostRelationOrigin.TELEGRAM_REPLY
                );

        relationId.ifPresent(classificationProcessor::processRelation);
    }

    /**
     * Восстанавливает пропущенные и незавершённые задачи.
     */
    public void reconcile() {
        int staleRecovered = stateService.recoverStaleProcessing();

        Set<Long> relationIdsToProcess = new LinkedHashSet<>();

        List<Long> replyPostsWithoutRelation = postRepository.findReplyPostIdsWithoutRelation(PageRequest.of(0, BACKFILL_BATCH_SIZE));

        for (Long postId : replyPostsWithoutRelation) {
            preparationService.prepare(postId, TelegramChannelPostRelationOrigin.SYSTEM_BACKFILL)
                    .ifPresent(relationIdsToProcess::add);
        }

        List<Long> automaticSourcePostIds = relationRepository
                        .findAutomaticSourcePostIds(
                                List.of(
                                        TelegramChannelPostRelationOrigin.TELEGRAM_REPLY,
                                        TelegramChannelPostRelationOrigin.SYSTEM_BACKFILL
                                ),
                                PageRequest.of(0, RECLASSIFICATION_SCAN_SIZE)
                        );

        for (Long sourcePostId : automaticSourcePostIds) {
            preparationService.prepare(sourcePostId, TelegramChannelPostRelationOrigin.SYSTEM_BACKFILL)
                    .ifPresent(relationIdsToProcess::add);
        }

        relationIdsToProcess.addAll(stateService.findReadyRelationIds(CLASSIFICATION_BATCH_SIZE));

        for (Long relationId : relationIdsToProcess) {
            classificationProcessor.processRelation(relationId);
        }

        log.info("Telegram reply relation reconciliation finished. staleRecovered={}, missingReplyRelations={}, scannedAutomaticSources={}, selectedRelations={}", staleRecovered, replyPostsWithoutRelation.size(), automaticSourcePostIds.size(), relationIdsToProcess.size());
    }
}