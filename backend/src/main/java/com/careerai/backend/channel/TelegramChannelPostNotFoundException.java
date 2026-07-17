package com.careerai.backend.channel;

/**
 * Возникает, когда Telegram-пост
 * с указанным идентификатором не найден.
 */

public class TelegramChannelPostNotFoundException extends RuntimeException {
    public TelegramChannelPostNotFoundException(long postId) {
        super("Telegram-пост не найден. postId=" + postId);
    }
}
