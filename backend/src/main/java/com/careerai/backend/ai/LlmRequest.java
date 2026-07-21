package com.careerai.backend.ai;

import java.util.Objects;

/**
 * Конфигурация одной AI-задачи.
 */
public record LlmRequest(
        LlmTaskType taskType,
        String systemPrompt,
        String userPrompt,
        double temperature,
        double topP,
        int maxOutputTokens,
        LlmResponseFormat responseFormat,
        LlmTimeoutProfile timeoutProfile,
        LlmProviderStrategy providerStrategy
) {

    public LlmRequest {
        taskType = Objects.requireNonNull(taskType, "Тип LLM-задачи не может быть null");
        userPrompt = Objects.requireNonNull(userPrompt, "Пользовательский prompt не может быть null");
        responseFormat = Objects.requireNonNull(responseFormat, "Формат ответа не может быть null");
        timeoutProfile = Objects.requireNonNull(timeoutProfile, "Профиль таймаута не может быть null");
        providerStrategy = Objects.requireNonNull(providerStrategy, "Стратегия провайдера не может быть null");

        systemPrompt = systemPrompt == null ? "" : systemPrompt;

        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("Температура должна находиться между 0.0 и 2.0");
        }

        if (topP <= 0.0 || topP > 1.0) {
            throw new IllegalArgumentException("topP должен находиться между 0.0 и 1.0");
        }

        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens должен быть положительным");
        }
    }

    /**
     * Сохраняет текущее поведение обычного ответа CareerAI.
     */
    public static LlmRequest userAnswer(String userMessage) {
        return new LlmRequest(
                LlmTaskType.USER_ANSWER,
                PromptTemplate.SYSTEM_PROMPT,
                userMessage,
                0.55,
                0.9,
                900,
                LlmResponseFormat.TEXT,
                LlmTimeoutProfile.STANDARD,
                LlmProviderStrategy.ACTIVE_WITH_FALLBACK
        );
    }
}