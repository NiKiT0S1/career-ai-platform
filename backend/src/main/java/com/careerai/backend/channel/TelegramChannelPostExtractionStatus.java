package com.careerai.backend.channel;

/**
 * Статус извлечения структурированной информации из Telegram-поста.
 */

public enum TelegramChannelPostExtractionStatus {
    PENDING, // Пост ожидает анализа
    SUCCESS, // Пост успешно проанализирован
    FAILED // При анализе поста произошла ошибка
}
