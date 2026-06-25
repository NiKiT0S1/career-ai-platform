package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Реализация {@link LlmProvider} через Gemini API.
 *
 * Этот класс формирует системный prompt CareerAI, отправляет сообщение пользователя
 * в Gemini, обрабатывает ошибки провайдера и возвращает готовый ответ обратно
 * в Telegram-логику бота.
 */

@Service
public class GeminiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmProvider.class);

    private static final int MAX_GEMINI_ATTEMPTS = 2;

    private static final Duration GEMINI_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GEMINI_READ_TIMEOUT = Duration.ofSeconds(45);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final GeminiKeyPool geminiKeyPool;

    public GeminiLlmProvider(GeminiProperties properties, ObjectMapper objectMapper, GeminiKeyPool geminiKeyPool) {
        this.properties = properties;
        this.objectMapper = objectMapper;
//        this.restClient = RestClient.create();
        this.geminiKeyPool = geminiKeyPool;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(GEMINI_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(GEMINI_READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public LlmResponse generateAnswer(String userMessage) {
        long requestStartedAt = System.nanoTime();

        for (int attempt = 1; attempt <= MAX_GEMINI_ATTEMPTS; attempt++) {
            var optionalApiKey = geminiKeyPool.getAvailableKey();

            if (optionalApiKey.isEmpty()) {
                log.warn("No available Gemini API keys. Gemini request failed after {} ms", elapsedMillis(requestStartedAt));

                return LlmResponse.failure("""
                        Сейчас все Gemini-ключи временно недоступны или упёрлись в лимиты.
                        
                        Попробуй повторить вопрос чуть позже.
                        """,
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.NO_AVAILABLE_KEYS,
                        elapsedMillis(requestStartedAt)
                );
            }

            String apiKey = optionalApiKey.get();
            long attemptStartedAt = System.nanoTime();

            try {
                String answer = callGemini(userMessage, apiKey);

                log.info(
                        "Gemini request succeeded in {} ms. Attempt {}/{}. Model: {}. User message length: {}. Answer length: {}",
                        elapsedMillis(requestStartedAt),
                        attempt,
                        MAX_GEMINI_ATTEMPTS,
                        properties.getModel(),
                        safeLength(userMessage),
                        safeLength(answer)
                );

                return LlmResponse.success(
                        answer,
                        "Gemini",
                        properties.getModel(),
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (HttpClientErrorException.NotFound e) {
                log.error(
                        "Gemini model was not found. Check gemini.api.model value. Model: {}",
                        properties.getModel(),
                        e
                );

                return LlmResponse.failure("""
                        Сейчас Gemini-модель настроена некорректно.
                        
                        Проверь значение gemini.api.model в application.properties.
                        """,
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.MODEL_NOT_FOUND,
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (HttpClientErrorException.TooManyRequests e) {
                geminiKeyPool.blockKey(apiKey, Duration.ofSeconds(60), "429 Too Many Requests");

                log.warn(
                        "Gemini request got 429 after {} ms. Trying another key. Attempt {}/{}",
                        elapsedMillis(attemptStartedAt),
                        attempt,
                        MAX_GEMINI_ATTEMPTS
                );
            }
            catch (HttpServerErrorException.ServiceUnavailable e) {
                geminiKeyPool.blockKey(apiKey, Duration.ofSeconds(30), "503 Service Unavailable");

                log.warn(
                        "Gemini request got 503 after {} ms. Trying another key. Attempt {}/{}",
                        elapsedMillis(attemptStartedAt),
                        attempt,
                        MAX_GEMINI_ATTEMPTS
                );
            }
            catch (org.springframework.web.client.ResourceAccessException e) {
                if (isTimeoutException(e)) {
                    log.warn(
                            "Gemini request timed out after {} ms. Model: {}. Attempt {}/{}",
                            elapsedMillis(requestStartedAt),
                            properties.getModel(),
                            attempt,
                            MAX_GEMINI_ATTEMPTS
                    );

                    return LlmResponse.failure("""
                            Gemini-модель сейчас отвечает слишком долго.
                            
                            Попробуй повторить вопрос чуть позже.
                            """,
                            "Gemini",
                            properties.getModel(),
                            LlmErrorType.TIMEOUT,
                            elapsedMillis(requestStartedAt)
                    );
                }

                log.error(
                        "Gemini network error after {} ms. Model: {}",
                        elapsedMillis(requestStartedAt),
                        properties.getModel(),
                        e
                );

                return LlmResponse.failure("""
                        Сейчас возникла сетевая ошибка при обращении к Gemini-модели.
                        
                        Попробуй повторить вопрос чуть позже.
                        """,
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.NETWORK_ERROR,
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (Exception e) {
                log.error(
                        "Unexpected error while calling Gemini API after {} ms. Model: {}",
                        elapsedMillis(requestStartedAt),
                        properties.getModel(),
                        e
                );

                return LlmResponse.failure("""
                        Сейчас я не могу обработать запрос через Gemini.
                        
                        Попробуй чуть позже.
                        """,
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.UNEXPECTED_ERROR,
                        elapsedMillis(requestStartedAt)
                );
            }
        }

        log.warn(
                "Gemini request failed after {} ms because all attempts were exhausted. Model: {}",
                elapsedMillis(requestStartedAt),
                properties.getModel()
        );

        return LlmResponse.failure("""
                Сейчас AI-модель временно недоступна или лимиты ключей исчерпаны.
                
                Попробуй повторить вопрос чуть позже.
                """,
                "Gemini",
                properties.getModel(),
                LlmErrorType.SERVICE_UNAVAILABLE,
                elapsedMillis(requestStartedAt)
        );
    }

    private String callGemini(String userMessage, String apiKey) throws Exception {
        String url = "%s/%s:generateContent"
                .formatted(properties.getBaseUrl(), properties.getModel());

        Map<String, Object> requestBody = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(
                                Map.of("text", PromptTemplate.SYSTEM_PROMPT)
                        )
                ),
                "contents", List.of(
                        Map.of(
                                "parts", List.of(
                                        Map.of("text", userMessage)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.55,
                        "topP", 0.9,
                        "maxOutputTokens", 900
                )
        );

        String responseJson = restClient.post()
                .uri(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractText(responseJson);
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);

        JsonNode textNode = root
                .path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text");

        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            log.warn("Gemini response did not contain expected text content: {}", responseJson);

            return """
                    Я получил ответ от AI, но не смог корректно извлечь текст.
                    
                    Попробуй переформулировать вопрос.
                    """;
        }

        return textNode.asText();
    }

    private boolean isTimeoutException(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
