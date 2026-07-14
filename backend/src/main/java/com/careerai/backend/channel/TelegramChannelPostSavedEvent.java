package com.careerai.backend.channel;

/**
 * Событие успешного сохранения нового или отредактированного
 * Telegram-поста в базе данных.
 */

public record TelegramChannelPostSavedEvent(
        long postId
) {
}
