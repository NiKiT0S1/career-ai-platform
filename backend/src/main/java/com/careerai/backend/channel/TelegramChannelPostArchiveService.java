package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Управляет ручным архивированием
 * и восстановлением Telegram-постов.
 */

@Service
public class TelegramChannelPostArchiveService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostArchiveService.class);

    private final TelegramChannelPostRepository postRepository;
    private final Clock clock;

    public TelegramChannelPostArchiveService(TelegramChannelPostRepository postRepository, Clock clock) {
        this.postRepository = postRepository;
        this.clock = clock;
    }

    /**
     * Исключает публикацию из обычных ответов бота.
     */
    @Transactional
    public TelegramChannelPostArchiveResult archive(
            long postId,
            String reason
    ) {
        String normalizedReason = normalizeArchiveReason(reason);

        TelegramChannelPost post = findPost(postId);

        boolean changed = post.archive(
                OffsetDateTime.now(clock),
                normalizedReason
        );

        if (changed) {
            postRepository.save(post);

            log.info("Telegram channel post archived. postId={}, telegramMessageId={}, archivedAt={}, reason={}", post.getId(), post.getTelegramMessageId(), post.getArchivedAt(), post.getArchiveReason());
        }
        else {
            log.info("Telegram channel post archive skipped: post is already archived. postId={}, telegramMessageId={}", post.getId(), post.getTelegramMessageId());
        }

        return toResult(post, changed);
    }

    /**
     * Возвращает публикацию из ручного архива.
     */
    @Transactional
    public TelegramChannelPostArchiveResult restore(long postId) {
        TelegramChannelPost post = findPost(postId);

        boolean changed = post.restoreFromArchive();

        if (changed) {
            postRepository.save(post);

            log.info("Telegram channel post restored. postId={}, telegramMessageId={}", post.getId(), post.getTelegramMessageId());
        }
        else {
            log.info("Telegram channel post restore skipped: post is not archived. postId={}, telegramMessageId={}", post.getId(), post.getTelegramMessageId());
        }

        return toResult(post, changed);
    }

    private TelegramChannelPost findPost(long postId) {
        return postRepository.findById(postId)
                .orElseThrow(
                        () ->
                                new TelegramChannelPostNotFoundException(postId)
                );
    }

    private String normalizeArchiveReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Необходимо указать причину архивирования");
        }

        return reason.strip();
    }

    private TelegramChannelPostArchiveResult toResult(TelegramChannelPost post, boolean changed) {
        return new TelegramChannelPostArchiveResult(
                post.getId(),
                post.isArchived(),
                changed,
                post.getArchivedAt(),
                post.getArchiveReason()
        );
    }
}
