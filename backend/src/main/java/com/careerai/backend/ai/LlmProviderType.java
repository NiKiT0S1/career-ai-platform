package com.careerai.backend.ai;

/**
 * Список AI-провайдеров, которые может использовать CareerAI.
 *
 * GEMINI означает использование Google Gemini API.
 * GROQ означает использование Groq API через OpenAI-compatible формат.
 */

public enum LlmProviderType {
    GEMINI,
    GROQ
}
