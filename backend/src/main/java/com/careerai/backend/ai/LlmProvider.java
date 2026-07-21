package com.careerai.backend.ai;

/**
 * Общий интерфейс AI-провайдеров CareerAI.
 */

public interface LlmProvider {

    /**
     * Выполняет явно сконфигурированную AI-задачу.
     */
    LlmResponse execute(LlmRequest request);

    /**
     * Совместимость с существующим кодом.
     *
     * Обычные вызовы продолжают использовать
     * основной системный prompt CareerAI.
     */
    default LlmResponse generateAnswer(
            String userMessage
    ) {
        return execute(
                LlmRequest.userAnswer(
                        userMessage
                )
        );
    }
}