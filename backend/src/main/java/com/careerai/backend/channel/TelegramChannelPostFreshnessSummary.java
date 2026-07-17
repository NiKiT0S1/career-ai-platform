package com.careerai.backend.channel;

/**
 * Итог массового пересчёта актуальности Telegram-постов.
 */

public record TelegramChannelPostFreshnessSummary(
        int total,
        int active,
        int unknown,
        int expired,
        int invalid
) {
}