package com.careerai.backend.ai;

/**
 * Результат обращения к AI-провайдеру.
 *
 * В отличие от обычной строки, этот объект хранит не только текст ответа,
 * но и техническую информацию: успешен ли запрос, какой провайдер отвечал,
 * какая модель использовалась и какая ошибка произошла.
 */

public record LlmResponse(
        String text,
        boolean success,
        String provider,
        String model,
        LlmErrorType errorType,
        long elapsedMillis
) {

    public static LlmResponse success(
            String text,
            String provider,
            String model,
            long elapsedMillis
    ) {
        return new LlmResponse(
                text,
                true,
                provider,
                model,
                LlmErrorType.NONE,
                elapsedMillis
        );
    }

    public static LlmResponse failure(
            String text,
            String provider,
            String model,
            LlmErrorType errorType,
            long elapsedMillis
    ) {
        return new LlmResponse(
                text,
                false,
                provider,
                model,
                errorType,
                elapsedMillis
        );
    }

    public boolean failed() {
        return !success;
    }
}
