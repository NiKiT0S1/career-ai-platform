package com.careerai.backend.channel;

import java.time.OffsetDateTime;

/**
 * Результат определения актуальности одного Telegram-поста.
 */

public record TelegramChannelPostFreshnessEvaluation(
        TelegramChannelPostFreshnessStatus status,
        OffsetDateTime expiresAt,
        String reason
) {

    public TelegramChannelPostFreshnessEvaluation {
        if (status == null) {
            status = TelegramChannelPostFreshnessStatus.UNKNOWN;
        }
    }
}
