package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Основная точка входа для генерации AI-ответов.
 *
 * Если активный провайдер — Gemini, backend сначала пробует Gemini.
 * Если Gemini не смогла ответить из-за лимитов, 503, timeout или сетевой ошибки,
 * backend автоматически пробует fallback через Groq.
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
    public LlmResponse generateAnswer(String userMessage) {
        return switch (llmProperties.getProvider()) {
            case GEMINI -> generateWithGeminiAndGroqFallback(userMessage);
            case GROQ -> generateWithGroqOnly(userMessage);
        };
    }

    private LlmResponse generateWithGeminiAndGroqFallback(String userMessage) {
        log.info("Active LLM provider: Gemini");

        LlmResponse geminiResponse = geminiLlmProvider.generateAnswer(userMessage);

        if (geminiResponse.success()) {
            return geminiResponse;
        }

        log.warn(
                "Primary Gemini provider failed. Trying Groq fallback. Gemini model: {}. Error type: {}. Elapsed: {} ms",
                geminiResponse.model(),
                geminiResponse.errorType(),
                geminiResponse.elapsedMillis()
        );

        LlmResponse groqResponse = groqLlmProvider.generateAnswer(userMessage);

        if (groqResponse.success()) {
            log.info(
                    "Groq fallback succeeded. Model: {}. Elapsed: {} ms",
                    groqResponse.model(),
                    groqResponse.elapsedMillis()
            );

            return groqResponse;
        }

        log.error(
                "Both LLM providers failed. Gemini error: {}. Groq error: {}",
                geminiResponse.errorType(),
                groqResponse.errorType()
        );

        return LlmResponse.failure(
                """
                Сейчас AI-ассистент временно недоступен.
                
                Основная модель и резервная модель не смогли обработать запрос.
                Попробуй повторить вопрос чуть позже.
                """,
                "ActiveLlmProvider",
                "Gemini -> Groq",
                groqResponse.errorType(),
                geminiResponse.elapsedMillis() + groqResponse.elapsedMillis()
        );
    }

    private LlmResponse generateWithGroqOnly(String userMessage) {
        log.info("Active LLM provider: Groq");

        return groqLlmProvider.generateAnswer(userMessage);
    }
}
