package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Пересчитывает и сохраняет freshness-статусы
 * Telegram-постов.
 */

@Service
public class TelegramChannelPostFreshnessService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelegramChannelPostFreshnessService.class
            );

    private final TelegramChannelPostRepository postRepository;
    private final TelegramChannelPostMetadataRepository metadataRepository;
    private final TelegramChannelPostFreshnessEvaluator evaluator;
    private final Clock clock;

    public TelegramChannelPostFreshnessService(
            TelegramChannelPostRepository postRepository,
            TelegramChannelPostMetadataRepository metadataRepository,
            TelegramChannelPostFreshnessEvaluator evaluator,
            Clock clock
    ) {
        this.postRepository = postRepository;
        this.metadataRepository = metadataRepository;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    /**
     * Пересчитывает актуальность всех сохранённых постов.
     */
    @Transactional
    public TelegramChannelPostFreshnessSummary recalculateAll() {
        List<TelegramChannelPost> posts =
                postRepository.findAll();

        Map<Long, TelegramChannelPostMetadata> metadataByPostId =
                metadataRepository.findAllWithPosts()
                        .stream()
                        .filter(metadata ->
                                metadata.getPost() != null
                                        && metadata.getPost().getId() != null
                        )
                        .collect(Collectors.toMap(
                                metadata ->
                                        metadata.getPost().getId(),
                                Function.identity(),
                                (first, second) -> first
                        ));

        int active = 0;
        int unknown = 0;
        int expired = 0;
        int invalid = 0;

        OffsetDateTime checkedAt =
                OffsetDateTime.now(clock);

        log.info(
                "Channel post freshness recalculation started. posts={}",
                posts.size()
        );

        for (TelegramChannelPost post : posts) {
            TelegramChannelPostMetadata metadata =
                    metadataByPostId.get(post.getId());

            TelegramChannelPostFreshnessEvaluation evaluation =
                    evaluator.evaluate(post, metadata);

            boolean changed = applyEvaluation(post, evaluation, checkedAt);

            if (changed) {
                log.info(
                        "Channel post freshness changed. "
                                + "postId={}, telegramMessageId={}, "
                                + "status={}, expiresAt={}, reason={}",
                        post.getId(),
                        post.getTelegramMessageId(),
                        evaluation.status(),
                        evaluation.expiresAt(),
                        evaluation.reason()
                );
            }

            switch (evaluation.status()) {
                case ACTIVE -> active++;
                case UNKNOWN -> unknown++;
                case EXPIRED -> expired++;
                case INVALID -> invalid++;
            }
        }

        postRepository.saveAll(posts);

        TelegramChannelPostFreshnessSummary summary =
                new TelegramChannelPostFreshnessSummary(
                        posts.size(),
                        active,
                        unknown,
                        expired,
                        invalid
                );

        log.info(
                "Channel post freshness recalculation finished. "
                        + "total={}, active={}, unknown={}, "
                        + "expired={}, invalid={}",
                summary.total(),
                summary.active(),
                summary.unknown(),
                summary.expired(),
                summary.invalid()
        );

        return summary;
    }

    /**
     * Пересчитывает freshness одной публикации.
     *
     * Используется сразу после успешного или неуспешного
     * извлечения новой metadata.
     */
    @Transactional
    public TelegramChannelPostFreshnessEvaluation recalculateOne(long postId) {
        TelegramChannelPost post = postRepository.findById(postId)
                .orElseThrow(() -> new TelegramChannelPostNotFoundException(postId));

        TelegramChannelPostMetadata metadata = metadataRepository.findByPostId(postId)
                .orElse(null);

        TelegramChannelPostFreshnessEvaluation evaluation = evaluator.evaluate(post, metadata);
        OffsetDateTime checkedAt = OffsetDateTime.now(clock);
        boolean changed = applyEvaluation(post, evaluation, checkedAt);

        postRepository.save(post);

        log.info(
                "Channel post freshness recalculated. postId={}, telegramMessageId={}, changed={}, status={}, expiresAt={}, reason={}",
                post.getId(),
                post.getTelegramMessageId(),
                changed,
                evaluation.status(),
                evaluation.expiresAt(),
                evaluation.reason()
        );

        return evaluation;
    }

    private boolean hasChanged(
            TelegramChannelPost post,
            TelegramChannelPostFreshnessEvaluation evaluation
    ) {
        return post.getFreshnessStatus()
                != evaluation.status()
                || !representsSameInstant(
                post.getExpiresAt(),
                evaluation.expiresAt()
        )
                || !Objects.equals(
                post.getFreshnessReason(),
                evaluation.reason()
        );
    }

    /**
     * Сравнивает два значения времени по реальному моменту,
     * не учитывая различия часового смещения.
     */
    private boolean representsSameInstant(
            OffsetDateTime first,
            OffsetDateTime second
    ) {
        if (first == null || second == null) {
            return first == second;
        }

        return first.isEqual(second);
    }

    private boolean applyEvaluation(
            TelegramChannelPost post,
            TelegramChannelPostFreshnessEvaluation evaluation,
            OffsetDateTime checkedAt
    ) {
        boolean changed = hasChanged(post, evaluation);

        post.setFreshnessStatus(evaluation.status());
        post.setExpiresAt(evaluation.expiresAt());
        post.setFreshnessReason(evaluation.reason());
        post.setFreshnessCheckedAt(checkedAt);

        return changed;
    }
}