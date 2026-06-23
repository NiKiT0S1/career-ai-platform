package com.careerai.backend.ai;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Хранит настройки, необходимые для обращения к Gemini API.
 *
 * Значения загружаются из {@code application.properties} и переменных окружения.
 * API-ключи хранятся одной строкой через запятую, чтобы MVP мог переключаться
 * между несколькими ключами при временных лимитах.
 */

@Getter
@Component
public class GeminiProperties {

    @Value("${gemini.api.keys}")
    private String apiKeys;

    @Value("${gemini.api.model}")
    private String model;

    @Value("${gemini.api.base-url}")
    private String baseUrl;
}
