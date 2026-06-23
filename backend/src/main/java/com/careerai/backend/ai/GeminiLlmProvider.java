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

    private static final int MAX_GEMINI_ATTEMPTS = 5;

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
    public String generateAnswer(String userMessage) {
        long requestStartedAt = System.nanoTime();

        for (int attempt = 1; attempt <= MAX_GEMINI_ATTEMPTS; attempt++) {
            var optionalApiKey = geminiKeyPool.getAvailableKey();

            if (optionalApiKey.isEmpty()) {
                log.warn("No available Gemini API keys. Gemini request failed after {} ms", elapsedMillis(requestStartedAt));

                return """
                        Сейчас все AI-ключи временно недоступны или упёрлись в лимиты.
                        
                        Попробуй повторить вопрос чуть позже.
                        """;
            }

            String apiKey = optionalApiKey.get();
            long attemptStartedAt = System.nanoTime();

            try {
                String answer = callGemini(userMessage, apiKey);

                log.info(
                        "Gemini request succeeded in {} ms. Attempt {}/{}. User message length: {}. Answer length: {}",
                        elapsedMillis(requestStartedAt),
                        attempt,
                        MAX_GEMINI_ATTEMPTS,
                        safeLength(userMessage),
                        safeLength(answer)
                );

                return answer;
            }
            catch (HttpServerErrorException.ServiceUnavailable e) {
                geminiKeyPool.blockKey(apiKey, Duration.ofSeconds(30), "503 Service Unavailable");

                log.warn(
                        "Gemini API is temporarily unavailable. Trying another key. Attempt {}/{}",
                        elapsedMillis(attemptStartedAt),
                        attempt,
                        MAX_GEMINI_ATTEMPTS
                );
            }
            catch (HttpClientErrorException.TooManyRequests e) {
                geminiKeyPool.blockKey(apiKey, Duration.ofSeconds(60), "429 Too Many Requests");

                log.warn(
                        "Gemini API quota/rate limit exceeded. Trying another key. Attempt {}/{}",
                        elapsedMillis(attemptStartedAt),
                        attempt,
                        MAX_GEMINI_ATTEMPTS
                );
            }
            catch (org.springframework.web.client.ResourceAccessException e) {
                log.error(
                        "Gemini request failed by timeout/network error after {} ms",
                        elapsedMillis(requestStartedAt),
                        e
                );

                return """
                    Сейчас AI-модель слишком долго отвечает или временно недоступна.
                    
                    Попробуй повторить вопрос чуть позже.
                    """;
            }
            catch (Exception e) {
                log.error("Unexpected error while calling Gemini API after {} ms", elapsedMillis(requestStartedAt), e);

                return "Сейчас я не могу обработать запрос через AI. Попробуй чуть позже.";
            }
        }

        log.warn(
                "Gemini request failed after {} ms because all attempts were exhausted",
                elapsedMillis(requestStartedAt)
        );

        return """
                Сейчас AI-модель временно недоступна или лимиты ключей исчерпаны.
                
                Попробуй повторить вопрос чуть позже.
                """;
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
            return "Я получил ответ от AI, но не смог корректно извлечь текст.";
        }

        return textNode.asText();
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
