package com.careerai.backend.channel;

/**
 * Состояние автоматической классификации
 * связи между Telegram-постами.
 */

public enum TelegramChannelPostRelationClassificationStatus {
    NOT_REQUIRED,  // Для ручной связи автоматическая классификация не требовалась

    PENDING,  // Связь создана, но ещё не обработана

    PROCESSING,  // Связь занята одним из обработчиков

    CLASSIFIED,  // Автоматическая классификация завершена

    REVIEW_REQUIRED,  // Результат получен, но требует проверки администратором

    FAILED  // Во время классификации произошла ошибка
}