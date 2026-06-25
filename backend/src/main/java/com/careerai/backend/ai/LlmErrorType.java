package com.careerai.backend.ai;

/**
 * Тип технической ошибки при обращении к AI-провайдеру.
 *
 * Нужен для fallback-логики: backend должен понимать, почему основной
 * провайдер не смог ответить, и можно ли попробовать запасную модель.
 */

public enum LlmErrorType {
    NONE,
    RATE_LIMIT,
    SERVICE_UNAVAILABLE,
    TIMEOUT,
    MODEL_NOT_FOUND,
    NETWORK_ERROR,
    NO_AVAILABLE_KEYS,
    EMPTY_RESPONSE,
    UNEXPECTED_ERROR,
}
