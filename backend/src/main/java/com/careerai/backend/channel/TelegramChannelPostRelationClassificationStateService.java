package com.careerai.backend.channel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Управляет захватом, завершением,
 * retry и восстановлением классификации.
 */

@Service
public class TelegramChannelPostRelationClassificationStateService {

    private static final int MAX_ATTEMPTS = 5;

    private static final Duration BASE_RETRY_DELAY = Duration.ofMinutes(5);

    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(2);

    private static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(20);

    private static final int MAX_ERROR_LENGTH = 4000;

    private final TelegramChannelPostRelationRepository relationRepository;
    private final TelegramRelationClassificationInputHashService inputHashService;
    private final Clock clock;

    public TelegramChannelPostRelationClassificationStateService(
            TelegramChannelPostRelationRepository relationRepository,
            TelegramRelationClassificationInputHashService inputHashService,
            Clock clock
    ) {
        this.relationRepository = relationRepository;
        this.inputHashService = inputHashService;
        this.clock = clock;
    }

    @Transactional
    public Optional<TelegramRelationClassificationWorkItem> claim(Long relationId) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        int claimed = relationRepository.claimForClassification(
                                relationId,
                                now,
                                MAX_ATTEMPTS
                        );

        if (claimed == 0) {
            return Optional.empty();
        }

        TelegramChannelPostRelation relation = relationRepository.findByIdWithPosts(relationId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Захваченная relation не найдена. relationId=" + relationId
                                        )
                        );

        TelegramReplyRelationClassificationContext context = createContext(relation);

        String inputHash = relation.getClassificationInputHash();

        if (inputHash == null || inputHash.isBlank()) {
            inputHash = inputHashService.calculate(
                            context,
                            TelegramRelationClassifierVersion.CURRENT
                    );

            relation.setClassificationInputHash(inputHash);

            relation.setClassifierVersion(TelegramRelationClassifierVersion.CURRENT);

            relationRepository.save(relation);
        }

        return Optional.of(
                new TelegramRelationClassificationWorkItem(
                        relationId,
                        inputHash,
                        context
                )
        );
    }

    @Transactional
    public void complete(
            TelegramRelationClassificationWorkItem workItem,
            TelegramReplyRelationClassification result
    ) {
        TelegramChannelPostRelation relation = relationRepository.findByIdWithPosts(workItem.relationId())
                        .orElse(null);

        if (relation == null) {
            return;
        }

        if (relation.getClassificationStatus() != TelegramChannelPostRelationClassificationStatus.PROCESSING) {
            return;
        }

        if (!Objects.equals(relation.getClassificationInputHash(), workItem.inputHash())) {
            relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.PENDING);
            relation.setProcessingStartedAt(null);
            relation.setClassificationNextAttemptAt(null);
            relation.setClassificationError("Входные данные изменились во время классификации");
            relation.setUpdatedAt(OffsetDateTime.now(clock));

            relationRepository.save(relation);

            return;
        }

        OffsetDateTime now = OffsetDateTime.now(clock);

        applyAudit(
                relation,
                result,
                now
        );

        if (result.classified() && result.relationType() != TelegramChannelPostRelationType.UNCLASSIFIED) {
            applyClassifiedResult(
                    relation,
                    result
            );
        }
        else if (result.requiresReview()) {
            applyReviewResult(
                    relation,
                    result
            );
        }
        else {
            applyFailedResult(
                    relation,
                    result,
                    now
            );
        }

        relation.setProcessingStartedAt(null);
        relation.setUpdatedAt(now);

        relationRepository.save(relation);
    }

    @Transactional(readOnly = true)
    public List<Long> findReadyRelationIds(int limit) {
        return relationRepository.findReadyRelationIds(
                        List.of(
                                TelegramChannelPostRelationClassificationStatus.PENDING,
                                TelegramChannelPostRelationClassificationStatus.FAILED
                        ),
                        MAX_ATTEMPTS,
                        OffsetDateTime.now(clock),
                        PageRequest.of(0,Math.max(1, limit))
                );
    }

    @Transactional
    public int recoverStaleProcessing() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        return relationRepository.resetStaleProcessing(
                        now.minus(STALE_PROCESSING_TIMEOUT),
                        now,
                        now,
                        "Предыдущая обработка не завершилась за допустимое время"
                );
    }

    private void applyAudit(
            TelegramChannelPostRelation relation,
            TelegramReplyRelationClassification result,
            OffsetDateTime now
    ) {
        relation.setClassificationConfidence(result.confidence());
        relation.setClassificationProvider(result.provider());
        relation.setClassificationModel(result.model());
        relation.setClassifierVersion(result.classifierVersion());
        relation.setClassificationRawResponse(result.rawResponse());
        relation.setClassificationError(truncateError(result.errorMessage()));
        relation.setClassifiedAt(now);
    }

    private void applyClassifiedResult(
            TelegramChannelPostRelation relation,
            TelegramReplyRelationClassification result
    ) {
        relation.setRelationType(result.relationType());
        relation.setReason(result.reason());
        relation.setProposedRelationType(null);
        relation.setProposedReason(null);
        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.CLASSIFIED);
        relation.setClassificationNextAttemptAt(null);
    }

    private void applyReviewResult(
            TelegramChannelPostRelation relation,
            TelegramReplyRelationClassification result
    ) {
        relation.setRelationType(TelegramChannelPostRelationType.UNCLASSIFIED);
        relation.setReason(null);
        relation.setProposedRelationType(result.relationType());
        relation.setProposedReason(result.reason());
        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.REVIEW_REQUIRED);
        relation.setClassificationNextAttemptAt(null);
    }

    private void applyFailedResult(
            TelegramChannelPostRelation relation,
            TelegramReplyRelationClassification result,
            OffsetDateTime now
    ) {
        relation.setRelationType(TelegramChannelPostRelationType.UNCLASSIFIED);
        relation.setReason(null);
        relation.setProposedRelationType(null);
        relation.setProposedReason(null);

        if (relation.getClassificationAttemptCount() >= MAX_ATTEMPTS) {
            relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.REVIEW_REQUIRED);
            relation.setClassificationNextAttemptAt(null);

            if (relation.getClassificationError() == null || relation.getClassificationError().isBlank()) {
                relation.setClassificationError("Автоматическая классификация исчерпала допустимое количество попыток");
            }

            return;
        }

        relation.setClassificationStatus(TelegramChannelPostRelationClassificationStatus.FAILED);

        relation.setClassificationNextAttemptAt(now.plus(retryDelay(relation.getClassificationAttemptCount())));
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 5);

        long multiplier = 1L << exponent;

        Duration calculated = BASE_RETRY_DELAY.multipliedBy(multiplier);

        if (calculated.compareTo(MAX_RETRY_DELAY) > 0) {
            return MAX_RETRY_DELAY;
        }

        return calculated;
    }

    private TelegramReplyRelationClassificationContext createContext(TelegramChannelPostRelation relation) {
        TelegramChannelPost sourcePost = relation.getSourcePost();

        TelegramChannelPost targetPost = relation.getTargetPost();

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

    private String truncateError(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }

        if (errorMessage.length() <= MAX_ERROR_LENGTH) {
            return errorMessage;
        }

        return errorMessage.substring(0, MAX_ERROR_LENGTH);
    }
}