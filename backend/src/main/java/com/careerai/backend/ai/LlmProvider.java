package com.careerai.backend.ai;

/**
 * Общий интерфейс для AI-провайдеров, которые используются в CareerAI.
 *
 * Остальная часть backend'а работает именно с этим интерфейсом, а не с конкретной
 * моделью Gemini. Благодаря этому в будущем можно будет заменить Gemini на другой
 * сервис, добавить fallback-провайдеров или подключить локальную LLM без переписывания
 * логики Telegram-бота.
 */

public interface LlmProvider {

    String generateAnswer(String userMessage);
}
