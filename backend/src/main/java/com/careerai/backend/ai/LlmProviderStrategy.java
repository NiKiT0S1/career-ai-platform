package com.careerai.backend.ai;

/**
 * Определяет порядок обращения к AI-провайдерам.
 */

public enum LlmProviderStrategy {

    ACTIVE_WITH_FALLBACK,  // Использовать выбранного в настройках провайдера с существующей fallback-логикой

    GROQ_FIRST  // Сначала использовать быстрый Groq, а Gemini оставить резервным провайдером
}