package com.careerai.backend.semantic;

import com.careerai.backend.channel.TelegramChannelPost;

/**
 * Telegram-пост и степень его смысловой близости
 * к вопросу пользователя.
 */

public record ChannelPostSemanticMatch(
        TelegramChannelPost post,
        double similarity
) {
}
