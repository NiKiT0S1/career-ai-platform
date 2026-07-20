package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Управляет явными связями между
 * исходными Telegram-постами и уточнениями.
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

    /**
     * Создаёт связь между новым постом
     * и исходной публикацией.
     */
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

        TelegramChannelPostRelationType safeRelationType =
                requireRelationType(relationType);

        String normalizedReason =
                normalizeReason(reason);

        Optional<TelegramChannelPostRelation> existingRelation =
                relationRepository
                        .findBySourcePostIdAndTargetPostIdAndRelationType(
                                sourcePostId,
                                targetPostId,
                                safeRelationType
                        );

        if (existingRelation.isPresent()) {
            TelegramChannelPostRelation relation =
                    existingRelation.get();

            log.info("Telegram channel post relation creation skipped: relation already exists. relationId={}, sourcePostId={}, targetPostId={}, relationType={}", relation.getId(), sourcePostId, targetPostId, safeRelationType);

            return toResult(
                    relation,
                    true,
                    false
            );
        }

        TelegramChannelPost sourcePost =
                findPost(sourcePostId);

        TelegramChannelPost targetPost =
                findPost(targetPostId);

        TelegramChannelPostRelation relation =
                new TelegramChannelPostRelation();

        relation.setSourcePost(sourcePost);
        relation.setTargetPost(targetPost);
        relation.setRelationType(safeRelationType);
        relation.setReason(normalizedReason);
        relation.setCreatedAt(
                OffsetDateTime.now(clock)
        );

        relationRepository.save(relation);

        log.info("Telegram channel post relation created. relationId={}, sourcePostId={}, targetPostId={}, relationType={}, reason={}", relation.getId(), sourcePostId, targetPostId, safeRelationType, normalizedReason);

        return toResult(
                relation,
                true,
                true
        );
    }

    /**
     * Удаляет существующую связь.
     */
    @Transactional
    public TelegramChannelPostRelationResult unlink(
            long sourcePostId,
            long targetPostId,
            TelegramChannelPostRelationType relationType
    ) {
        TelegramChannelPostRelationType safeRelationType =
                requireRelationType(relationType);

        Optional<TelegramChannelPostRelation> existingRelation =
                relationRepository
                        .findBySourcePostIdAndTargetPostIdAndRelationType(
                                sourcePostId,
                                targetPostId,
                                safeRelationType
                        );

        if (existingRelation.isEmpty()) {
            log.info("Telegram channel post relation deletion skipped: relation was not found. sourcePostId={}, targetPostId={}, relationType={}", sourcePostId, targetPostId, safeRelationType);

            return new TelegramChannelPostRelationResult(
                    null,
                    sourcePostId,
                    targetPostId,
                    safeRelationType,
                    false,
                    false,
                    null,
                    null
            );
        }

        TelegramChannelPostRelation relation =
                existingRelation.get();

        TelegramChannelPostRelationResult result =
                toResult(
                        relation,
                        false,
                        true
                );

        relationRepository.delete(relation);

        log.info("Telegram channel post relation deleted. relationId={}, sourcePostId={}, targetPostId={}, relationType={}", relation.getId(), sourcePostId, targetPostId, safeRelationType);

        return result;
    }

    private TelegramChannelPost findPost(long postId) {
        return postRepository.findById(postId)
                .orElseThrow(
                        () ->
                                new TelegramChannelPostNotFoundException(
                                        postId
                                )
                );
    }

    private void validateDifferentPosts(
            long sourcePostId,
            long targetPostId
    ) {
        if (sourcePostId == targetPostId) {
            throw new IllegalArgumentException(
                    "Telegram-пост нельзя связать с самим собой"
            );
        }
    }

    private TelegramChannelPostRelationType requireRelationType(
            TelegramChannelPostRelationType relationType
    ) {
        if (relationType == null) {
            throw new IllegalArgumentException(
                    "Необходимо указать тип связи постов"
            );
        }

        return relationType;
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Необходимо указать причину связи постов"
            );
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