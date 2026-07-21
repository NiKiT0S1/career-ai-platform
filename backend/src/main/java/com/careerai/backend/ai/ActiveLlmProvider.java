package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Выбирает AI-провайдера и выполняет fallback.
 */

@Primary
@Service
public class ActiveLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveLlmProvider.class);

    private final LlmProperties llmProperties;
    private final GeminiLlmProvider geminiLlmProvider;
    private final GroqLlmProvider groqLlmProvider;

    public ActiveLlmProvider(
            LlmProperties llmProperties,
            GeminiLlmProvider geminiLlmProvider,
            GroqLlmProvider groqLlmProvider
    ) {
        this.llmProperties = llmProperties;
        this.geminiLlmProvider = geminiLlmProvider;
        this.groqLlmProvider = groqLlmProvider;
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        if (request.providerStrategy() == LlmProviderStrategy.GROQ_FIRST) {
            return executeGroqFirst(request);
        }

        return switch (llmProperties.getProvider()) {
            case GEMINI -> executeGeminiFirst(request);
            case GROQ -> executeGroqOnly(request);
        };
    }

    private LlmResponse executeGeminiFirst(LlmRequest request) {
        log.info("Active LLM provider selected. provider=Gemini, taskType={}", request.taskType());

        LlmResponse geminiResponse = geminiLlmProvider.execute(request);

        if (geminiResponse.success()) {
            return geminiResponse;
        }

        log.warn("Primary Gemini provider failed. Trying Groq fallback. taskType={}, model={}, errorType={}, elapsedMs={}", request.taskType(), geminiResponse.model(), geminiResponse.errorType(), geminiResponse.elapsedMillis());

        LlmResponse groqResponse = groqLlmProvider.execute(request);

        if (groqResponse.success()) {
            log.info("Groq fallback succeeded. taskType={}, model={}, elapsedMs={}", request.taskType(), groqResponse.model(), groqResponse.elapsedMillis());
            return groqResponse;
        }

        return bothProvidersFailed(request, geminiResponse, groqResponse);
    }

    private LlmResponse executeGroqFirst(LlmRequest request) {
        log.info("Fast LLM provider selected. provider=Groq, taskType={}", request.taskType());

        LlmResponse groqResponse = groqLlmProvider.execute(request);

        if (groqResponse.success()) {
            return groqResponse;
        }

        log.warn("Primary Groq provider failed. Trying Gemini fallback. taskType={}, model={}, errorType={}, elapsedMs={}", request.taskType(), groqResponse.model(), groqResponse.errorType(), groqResponse.elapsedMillis());

        LlmResponse geminiResponse = geminiLlmProvider.execute(request);

        if (geminiResponse.success()) {
            log.info("Gemini fallback succeeded. taskType={}, model={}, elapsedMs={}", request.taskType(), geminiResponse.model(), geminiResponse.elapsedMillis());
            return geminiResponse;
        }

        return bothProvidersFailed(request, groqResponse, geminiResponse);
    }

    private LlmResponse executeGroqOnly(LlmRequest request) {
        log.info("Active LLM provider selected. provider=Groq, taskType={}", request.taskType());
        return groqLlmProvider.execute(request);
    }

    private LlmResponse bothProvidersFailed(
            LlmRequest request,
            LlmResponse firstResponse,
            LlmResponse secondResponse
    ) {
        log.error("Both LLM providers failed. taskType={}, firstError={}, secondError={}", request.taskType(), firstResponse.errorType(), secondResponse.errorType());

        return LlmResponse.failure(
                "Основная и резервная AI-модели не смогли выполнить задачу",
                "ActiveLlmProvider",
                firstResponse.provider() + " -> " + secondResponse.provider(),
                secondResponse.errorType(),
                firstResponse.elapsedMillis() + secondResponse.elapsedMillis()
        );
    }
}