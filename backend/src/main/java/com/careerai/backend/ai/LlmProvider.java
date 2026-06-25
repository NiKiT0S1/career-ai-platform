package com.careerai.backend.ai;

/**
 * Общий интерфейс для AI-провайдеров, которые используются в CareerAI.
 *
 * Остальная часть backend'а работает именно с этим интерфейсом, а не с конкретной
 * моделью Gemini или Groq. Благодаря этому можно подключать fallback-провайдеров
 * без переписывания Telegram-логики.
 */

public interface LlmProvider {

    LlmResponse generateAnswer(String userMessage);
}
