package com.careerai.backend.ai;

/**
 * Тип внутренней задачи,
 * выполняемой AI-провайдером.
 */

public enum LlmTaskType {
    USER_ANSWER,  // Обычный ответ пользователю CareerAI

    QUERY_ANALYSIS,  // Определение намерения пользователя и нужных источников

    CHANNEL_METADATA_EXTRACTION,  // Извлечение структурированной metadata Telegram-поста

    CHANNEL_RELATION_CLASSIFICATION  // Классификация связи Telegram-постов
}