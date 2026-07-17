package com.careerai.backend.channel;

import java.time.OffsetDateTime;

/**
 * Результат ручного архивирования
 * или восстановления Telegram-поста.
 */

public record TelegramChannelPostArchiveResult(
        Long postId,
        boolean archived,
        boolean changed,
        OffsetDateTime archivedAt,
        String archiveReason
) {
}
