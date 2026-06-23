package com.careerai.backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Хранит общую настройку активного AI-провайдера.
 *
 * По этому значению backend понимает, какой именно сервис использовать для генерации
 * ответов: Gemini или Groq. Это позволяет переключать провайдера через application.properties
 * без изменения Telegram-логики бота.
 */

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private LlmProviderType provider = LlmProviderType.GEMINI;

    public LlmProviderType getProvider() {
        return provider;
    }

    public void setProvider(LlmProviderType provider) {
        this.provider = provider;
    }
}
