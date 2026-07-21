package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Находит исходный пост для Telegram Reply
 * и создаёт либо обновляет relation.
 *
 * Сетевые LLM-вызовы здесь не выполняются.
 */

@Service
public class TelegramReplyRelationPreparationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramReplyRelationPreparationService.class);

    private static final Set<TelegramChannelPostRelationOrigin> AUTOMATIC_ORIGINS =
            EnumSet.of(
                    TelegramChannelPostRelationOrigin.TELEGRAM_REPLY,
                    TelegramChannelPostRelationOrigin.SYSTEM_BACKFILL
            );

    private final TelegramChannelPostRepository postRepository;
    private final TelegramChannelPostRelationRepository relationRepository;
    private final TelegramChannelPostRelationRootResolver rootResolver;
    private final TelegramRelationClassificationInputHashService inputHashService;
    private final Clock clock;

    public TelegramReplyRelationPreparationService(
            TelegramChannelPostRepository postRepository,
            TelegramChannelPostRelationRepository relationRepository,
            TelegramChannelPostRelationRootResolver rootResolver,
            TelegramRelationClassificationInputHashService inputHashService,
            Clock clock
    ) {
        this.postRepository = postRepository;
        this.relationRepository = relationRepository;
        this.rootResolver = rootResolver;
        this.inputHashService = inputHashService;
        this.clock = clock;
    }

    /**
     * @return relationId, которую требуется обработать
     */
    @Transactional
    public Optional<Long> prepare(Long sourcePostId,TelegramChannelPostRelationOrigin origin) {
        if (sourcePostId == null) {
            return Optional.empty();
        }

        TelegramChannelPost sourcePost = postRepository.findById(sourcePostId)
                        .orElse(null);

        if (sourcePost == null) {
            log.warn("Telegram reply relation preparation skipped: source post was not found. sourcePostId={}", sourcePostId);
            return Optional.empty();
        }

        Long replyToMessageId = sourcePost.getReplyToTelegramMessageId();

        if (replyToMessageId == null) {
            return Optional.empty();
        }

        TelegramChannelPost directTargetPost = postRepository.findByTelegramChatIdAndTelegramMessageId(
                                sourcePost.getTelegramChatId(),
                                replyToMessageId
                        )
                        .orElse(null);

        if (directTargetPost == null) {
            log.warn("Telegram reply relation preparation postponed: replied post was not found. sourcePostId={}, chatId={}, replyToMessageId={}", sourcePostId, sourcePost.getTelegramChatId(), replyToMessageId);
            return Optional.empty();
        }

        TelegramChannelPostRootResolution rootResolution = rootResolver.resolveRoot(directTargetPost);

        if (!rootResolution.resolved()) {
            log.warn("Telegram reply relation preparation skipped: root could not be resolved. sourcePostId={}, directTargetPostId={}, status={}, reason={}", sourcePostId, directTargetPost.getId(), rootResolution.status(), rootResolution.reason());
            return Optional.empty();
        }

        TelegramChannelPost rootPost = rootResolution.rootPost();

        if (Objects.equals(sourcePost.getId(), rootPost.getId())) {
            log.warn("Telegram reply relation preparation skipped: source and root are the same post. postId={}", sourcePostId);
            return Optional.empty();
        }

        List<TelegramChannelPostRelation> sourceRelations = relationRepository.findAllBySourcePostIdWithTarget(sourcePostId);

        boolean hasManualAuthority = sourceRelations.stream().anyMatch(this::isManualAuthority);

        if (hasManualAuthority) {
            log.info("Telegram reply automatic relation skipped: source post already has a manual or admin-confirmed relation. sourcePostId={}", sourcePostId);
            return Optional.empty();
        }

        /*
         * Автоматическая связь всегда ведёт
         * непосредственно к корню.
         */
        List<TelegramChannelPostRelation> outdatedAutomaticRelations = sourceRelations.stream()
                        .filter(this::isAutomatic)
                        .filter(relation ->
                                        !Objects.equals(
                                                relation
                                                        .getTargetPost()
                                                        .getId(),
                                                rootPost.getId()
                                        )
                        )
                        .toList();

        if (!outdatedAutomaticRelations.isEmpty()) {
            relationRepository.deleteAll(outdatedAutomaticRelations);
        }

        TelegramReplyRelationClassificationContext context = createContext(sourcePost, rootPost);

        String inputHash = inputHashService.calculate(context, TelegramRelationClassifierVersion.CURRENT);

        Optional<TelegramChannelPostRelation> existingOptional = relationRepository.findBySourcePostIdAndTargetPostId(
                                sourcePostId,
                                rootPost.getId()
                        );

        if (existingOptional.isEmpty()) {
            TelegramChannelPostRelation relation = createPendingRelation(
                            sourcePost,
                            rootPost,
                            origin,
                            inputHash
                    );

            TelegramChannelPostRelation saved = relationRepository.save(relation);

            log.info("Automatic Telegram reply relation prepared. relationId={}, sourcePostId={}, targetPostId={}, origin={}, inputHash={}", saved.getId(), sourcePostId, rootPost.getId(), origin, inputHash);

            return Optional.of(saved.getId());
        }

        TelegramChannelPostRelation relation = existingOptional.get();

        if (isManualAuthority(relation)) {
            return Optional.empty();
        }

        boolean inputChanged = !Objects.equals(
                        relation.getClassificationInputHash(),
                        inputHash
                );

        boolean versionChanged = !Objects.equals(
                        relation.getClassifierVersion(),
                        TelegramRelationClassifierVersion.CURRENT
                );

        if (inputChanged || versionChanged) {
            resetToPending(
                    relation,
                    origin,
                    inputHash
            );

            relationRepository.save(relation);

            log.info("Automatic Telegram reply relation reset for reclassification. relationId={}, sourcePostId={}, targetPostId={}, inputChanged={}, versionChanged={}", relation.getId(), sourcePostId, rootPost.getId(), inputChanged, versionChanged);

            return Optional.of(relation.getId());
        }

        if (relation.getClassificationStatus()
                == TelegramChannelPostRelationClassificationStatus.PENDING
                || relation.getClassificationStatus()
                == TelegramChannelPostRelationClassificationStatus.FAILED) {
            return Optional.of(relation.getId());
        }

        return Optional.empty();
    }

    private TelegramChannelPostRelation createPendingRelation(
            TelegramChannelPost sourcePost,
            TelegramChannelPost targetPost,
            TelegramChannelPostRelationOrigin origin,
            String inputHash
    ) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        TelegramChannelPostRelation relation = new TelegramChannelPostRelation();

        relation.setSourcePost(sourcePost);
        relation.setTargetPost(targetPost);
        relation.setRelationType(TelegramChannelPostRelationType.UNCLASSIFIED);
        relation.setReason(null);
        relation.setRelationOrigin(origin);
        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.PENDING);
        relation.setClassifierVersion(TelegramRelationClassifierVersion.CURRENT);
        relation.setClassificationInputHash(inputHash);
        relation.setClassificationAttemptCount(0);
        relation.setCreatedAt(now);
        relation.setUpdatedAt(now);

        return relation;
    }

    private void resetToPending(
            TelegramChannelPostRelation relation,
            TelegramChannelPostRelationOrigin origin,
            String inputHash
    ) {
        relation.setRelationType(TelegramChannelPostRelationType.UNCLASSIFIED);
        relation.setReason(null);

        if (relation.getRelationOrigin()
                == TelegramChannelPostRelationOrigin.SYSTEM_BACKFILL
                && origin
                == TelegramChannelPostRelationOrigin.TELEGRAM_REPLY) {
            relation.setRelationOrigin(origin);
        }

        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.PENDING);
        relation.setProposedRelationType(null);
        relation.setProposedReason(null);
        relation.setClassificationConfidence(null);
        relation.setClassificationProvider(null);
        relation.setClassificationModel(null);
        relation.setClassifierVersion(TelegramRelationClassifierVersion.CURRENT);
        relation.setClassificationRawResponse(null);
        relation.setClassificationError(null);
        relation.setClassifiedAt(null);
        relation.setClassificationAttemptCount(0);
        relation.setClassificationNextAttemptAt(null);
        relation.setProcessingStartedAt(null);
        relation.setClassificationInputHash(inputHash);
        relation.setUpdatedAt(OffsetDateTime.now(clock));
    }

    private TelegramReplyRelationClassificationContext createContext(
            TelegramChannelPost sourcePost,
            TelegramChannelPost targetPost
    ) {
        return new TelegramReplyRelationClassificationContext(
                sourcePost.getId(),
                sourcePost.getText(),
                effectiveDate(sourcePost),
                targetPost.getId(),
                targetPost.getText(),
                effectiveDate(targetPost)
        );
    }

    private OffsetDateTime effectiveDate(TelegramChannelPost post) {
        if (post.getEditedAt() != null) {
            return post.getEditedAt();
        }

        if (post.getPostedAt() != null) {
            return post.getPostedAt();
        }

        return post.getCreatedAt();
    }

    private boolean isAutomatic(TelegramChannelPostRelation relation) {
        return relation != null && AUTOMATIC_ORIGINS.contains(relation.getRelationOrigin());
    }

    private boolean isManualAuthority(TelegramChannelPostRelation relation) {
        if (relation == null || relation.getRelationOrigin() == null) {
            return false;
        }

        return relation.getRelationOrigin()
                == TelegramChannelPostRelationOrigin.MANUAL
                || relation.getRelationOrigin()
                == TelegramChannelPostRelationOrigin.ADMIN_CONFIRMED;
    }
}