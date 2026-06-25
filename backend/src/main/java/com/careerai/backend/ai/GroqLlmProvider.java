package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Реализация {@link LlmProvider} через Groq API.
 *
 * Groq используется как быстрый экспериментальный AI-провайдер для сравнения с Gemini.
 * Он принимает запросы в OpenAI-compatible формате: system/user messages, model,
 * temperature и max_tokens. Это позволяет тестировать разные модели без изменения
 * Telegram-логики бота.
 */

@Service
public class GroqLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqLlmProvider.class);

    private final GroqProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GroqLlmProvider(
            GroqProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public LlmResponse generateAnswer(String userMessage) {
        long startedAt = System.nanoTime();

        if (properties.getKey() == null || properties.getKey().isBlank()) {
            log.warn("Groq API key is not configured");

            return LlmResponse.failure("""
                    Groq API key не настроен.
                    
                    Проверь переменную окружения GROQ_API_KEY.
                    """,
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.UNEXPECTED_ERROR,
                    elapsedMillis(startedAt)
            );
        }

        try {
            String answer = callGroq(userMessage);

            log.info(
                    "Groq request succeeded in {} ms. Model: {}. User message length: {}. Answer length: {}",
                    elapsedMillis(startedAt),
                    properties.getModel(),
                    safeLength(userMessage),
                    safeLength(answer)
            );

            return LlmResponse.success(
                    answer,
                    "Groq",
                    properties.getModel(),
                    elapsedMillis(startedAt)
            );
        }
        catch (HttpClientErrorException.TooManyRequests e) {
            log.warn(
                    "Groq request got 429 after {} ms. Model: {}",
                    elapsedMillis(startedAt),
                    properties.getModel()
            );

            return LlmResponse.failure("""
                    Groq временно ограничил количество запросов.
                    
                    Попробуй повторить вопрос чуть позже.
                    """,
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.RATE_LIMIT,
                    elapsedMillis(startedAt)
            );
        }
        catch (HttpClientErrorException.NotFound e) {
            log.error(
                    "Groq model was not found. Check groq.api.model value. Model: {}",
                    properties.getModel(),
                    e
            );

            return LlmResponse.failure("""
                    Groq-модель настроена некорректно.
                    
                    Проверь значение groq.api.model в application.properties.
                    """,
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.MODEL_NOT_FOUND,
                    elapsedMillis(startedAt)
            );
        }
        catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn(
                    "Groq request failed by timeout/network error after {} ms. Model: {}",
                    elapsedMillis(startedAt),
                    properties.getModel()
            );

            return LlmResponse.failure("""
                Groq сейчас отвечает слишком долго или временно недоступен.
                
                Попробуй повторить вопрос чуть позже.
                """,
                "Groq",
                properties.getModel(),
                LlmErrorType.TIMEOUT,
                elapsedMillis(startedAt)
            );
        }
        catch (Exception e) {
            log.error(
                    "Unexpected error while calling Groq API after {} ms. Model: {}",
                    elapsedMillis(startedAt),
                    properties.getModel(),
                    e
            );

            return LlmResponse.failure("""
                    Сейчас я не могу обработать запрос через Groq.
                    
                    Попробуй чуть позже.
                    """,
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.UNEXPECTED_ERROR,
                    elapsedMillis(startedAt)
            );
        }
    }

    private String callGroq(String userMessage) throws Exception {
        String url = properties.getBaseUrl() + "/chat/completions";

        Map<String, Object> requestBody = Map.of(
                "model", properties.getModel(),
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", PromptTemplate.SYSTEM_PROMPT
                        ),
                        Map.of(
                                "role", "user",
                                "content", userMessage
                        )
                ),
                "temperature", properties.getTemperature(),
                "max_tokens", properties.getMaxOutputTokens()
        );

        String responseJson = restClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + properties.getKey())
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractText(responseJson);
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);

        JsonNode contentNode = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content");

        if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
            log.warn("Groq response did not contain expected text content: {}", responseJson);

            return """
                    Groq вернул пустой ответ.
                    
                    Попробуй переформулировать вопрос.
                    """;
        }

        return contentNode.asText();
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
