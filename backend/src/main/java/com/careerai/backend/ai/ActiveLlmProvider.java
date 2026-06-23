package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Основная точка входа для генерации AI-ответов.
 *
 * Этот класс выбирает реального AI-провайдера по настройке {@code llm.provider}.
 * Благодаря этому Telegram-логика зависит только от {@link LlmProvider}, а backend
 * может переключаться между Gemini и Groq через application.properties.
 */

@Primary
@Service
public class ActiveLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveLlmProvider.class);

    private final LlmProperties llmProperties;
    private final GeminiLlmProvider geminiLlmProvider;
    private final GroqLlmProvider groqLlmProvider;

    public ActiveLlmProvider (
            LlmProperties llmProperties,
            GeminiLlmProvider geminiLlmProvider,
            GroqLlmProvider groqLlmProvider
    ) {
        this.llmProperties = llmProperties;
        this.geminiLlmProvider = geminiLlmProvider;
        this.groqLlmProvider = groqLlmProvider;
    }

    @Override
    public String generateAnswer(String userMessage) {
        return switch (llmProperties.getProvider()) {
            case GEMINI -> {
                log.info("Active LLM provider: Gemini");
                yield geminiLlmProvider.generateAnswer(userMessage);
            }
            case GROQ ->  {
                log.info("Active LLM provider: Groq");
                yield groqLlmProvider.generateAnswer(userMessage);
            }
        };
    }
}
