package com.careerai.backend.channel;

import java.time.OffsetDateTime;

/**
 * Результат создания или удаления
 * связи между Telegram-постами.
 */

public record TelegramChannelPostRelationResult(
        Long relationId,
        Long sourcePostId,
        Long targetPostId,
        TelegramChannelPostRelationType relationType,
        boolean linked,
        boolean changed,
        String reason,
        OffsetDateTime createdAt
) {
}