package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Управляет ручными связями Telegram-постов.
 */

@Service
public class TelegramChannelPostRelationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostRelationService.class);

    private final TelegramChannelPostRelationRepository relationRepository;
    private final TelegramChannelPostRepository postRepository;
    private final Clock clock;

    public TelegramChannelPostRelationService(
            TelegramChannelPostRelationRepository relationRepository,
            TelegramChannelPostRepository postRepository,
            Clock clock
    ) {
        this.relationRepository = relationRepository;
        this.postRepository = postRepository;
        this.clock = clock;
    }

    @Transactional
    public TelegramChannelPostRelationResult link(
            long sourcePostId,
            long targetPostId,
            TelegramChannelPostRelationType relationType,
            String reason
    ) {
        validateDifferentPosts(
                sourcePostId,
                targetPostId
        );

        TelegramChannelPostRelationType safeType = requireConfirmedRelationType(relationType);

        String normalizedReason = normalizeReason(reason);

        Optional<TelegramChannelPostRelation> existingOptional = relationRepository
                        .findBySourcePostIdAndTargetPostId(
                                sourcePostId,
                                targetPostId
                        );

        if (existingOptional.isPresent()) {
            TelegramChannelPostRelation relation = existingOptional.get();

            boolean changed = relation.getRelationType() != safeType || !Objects.equals(relation.getReason(), normalizedReason)
                            || relation.getRelationOrigin()
                            != TelegramChannelPostRelationOrigin.MANUAL
                            || relation.getClassificationStatus()
                            != TelegramChannelPostRelationClassificationStatus.NOT_REQUIRED;

            if (changed) {
                relation.setRelationType(safeType);
                relation.setReason(normalizedReason);
                relation.setRelationOrigin(TelegramChannelPostRelationOrigin.MANUAL);

                clearAutomaticClassification(relation);

                relation.setUpdatedAt(OffsetDateTime.now(clock));

                relationRepository.save(relation);
            }

            log.info("Manual Telegram channel post relation updated or retained. relationId={}, sourcePostId={}, targetPostId={}, relationType={}, changed={}", relation.getId(), sourcePostId, targetPostId, safeType, changed);

            return toResult(
                    relation,
                    true,
                    changed
            );
        }

        TelegramChannelPost sourcePost = findPost(sourcePostId);

        TelegramChannelPost targetPost = findPost(targetPostId);

        OffsetDateTime now = OffsetDateTime.now(clock);

        TelegramChannelPostRelation relation = new TelegramChannelPostRelation();

        relation.setSourcePost(sourcePost);
        relation.setTargetPost(targetPost);
        relation.setRelationType(safeType);
        relation.setReason(normalizedReason);
        relation.setRelationOrigin(TelegramChannelPostRelationOrigin.MANUAL);
        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.NOT_REQUIRED);
        relation.setCreatedAt(now);
        relation.setUpdatedAt(now);

        relationRepository.save(relation);

        log.info("Manual Telegram channel post relation created. relationId={}, sourcePostId={}, targetPostId={}, relationType={}, reason={}", relation.getId(), sourcePostId, targetPostId, safeType, normalizedReason);

        return toResult(
                relation,
                true,
                true
        );
    }

    @Transactional
    public TelegramChannelPostRelationResult unlink(
            long sourcePostId,
            long targetPostId,
            TelegramChannelPostRelationType relationType
    ) {
        Optional<TelegramChannelPostRelation> existingOptional = relationRepository.findBySourcePostIdAndTargetPostId(
                                sourcePostId,
                                targetPostId
                        );

        if (existingOptional.isEmpty() || existingOptional.get().getRelationType() != relationType) {
            log.info("Telegram channel post relation deletion skipped: relation was not found. sourcePostId={}, targetPostId={}, relationType={}", sourcePostId, targetPostId, relationType);

            return new TelegramChannelPostRelationResult(
                    null,
                    sourcePostId,
                    targetPostId,
                    relationType,
                    false,
                    false,
                    null,
                    null
            );
        }

        TelegramChannelPostRelation relation = existingOptional.get();

        TelegramChannelPostRelationResult result =
                toResult(
                        relation,
                        false,
                        true
                );

        relationRepository.delete(relation);

        log.info("Telegram channel post relation deleted. relationId={}, sourcePostId={}, targetPostId={}, relationType={}", relation.getId(), sourcePostId, targetPostId, relationType);

        return result;
    }

    private void clearAutomaticClassification(TelegramChannelPostRelation relation) {
        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.NOT_REQUIRED);
        relation.setClassificationConfidence(null);
        relation.setProposedRelationType(null);
        relation.setProposedReason(null);
        relation.setClassificationProvider(null);
        relation.setClassificationModel(null);
        relation.setClassifierVersion(null);
        relation.setClassificationRawResponse(null);
        relation.setClassificationError(null);
        relation.setClassifiedAt(null);
        relation.setClassificationAttemptCount(0);
        relation.setClassificationNextAttemptAt(null);
        relation.setProcessingStartedAt(null);
        relation.setClassificationInputHash(null);
    }

    private TelegramChannelPost findPost(long postId) {
        return postRepository.findById(postId)
                .orElseThrow(
                        () -> new TelegramChannelPostNotFoundException(postId)
                );
    }

    private void validateDifferentPosts(
            long sourcePostId,
            long targetPostId
    ) {
        if (sourcePostId == targetPostId) {
            throw new IllegalArgumentException("Telegram-пост нельзя связать с самим собой");
        }
    }

    private TelegramChannelPostRelationType requireConfirmedRelationType(TelegramChannelPostRelationType type) {
        if (type == null || type == TelegramChannelPostRelationType.UNCLASSIFIED) {
            throw new IllegalArgumentException("Для ручной связи необходимо указать подтверждённый тип");
        }

        return type;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Необходимо указать причину связи постов");
        }

        return reason.strip();
    }

    private TelegramChannelPostRelationResult toResult(
            TelegramChannelPostRelation relation,
            boolean linked,
            boolean changed
    ) {
        return new TelegramChannelPostRelationResult(
                relation.getId(),
                relation.getSourcePost().getId(),
                relation.getTargetPost().getId(),
                relation.getRelationType(),
                linked,
                changed,
                relation.getReason(),
                relation.getCreatedAt()
        );
    }
}