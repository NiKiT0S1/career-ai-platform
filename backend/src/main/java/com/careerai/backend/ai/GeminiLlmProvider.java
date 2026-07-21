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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реализация LlmProvider через Gemini API.
 */
@Service
public class GeminiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmProvider.class);

    private static final int MAX_GEMINI_ATTEMPTS = 2;

    private static final Duration GEMINI_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration GEMINI_READ_TIMEOUT = Duration.ofSeconds(45);

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient fastRestClient;
    private final RestClient standardRestClient;
    private final GeminiKeyPool geminiKeyPool;

    public GeminiLlmProvider(
            GeminiProperties properties,
            ObjectMapper objectMapper,
            GeminiKeyPool geminiKeyPool
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.geminiKeyPool = geminiKeyPool;

        this.fastRestClient = createRestClient(Duration.ofSeconds(2), Duration.ofSeconds(3));
        this.standardRestClient = createRestClient(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        long requestStartedAt = System.nanoTime();

        for (int attempt = 1; attempt <= MAX_GEMINI_ATTEMPTS; attempt++) {

            var optionalApiKey = geminiKeyPool.getAvailableKey();

            if (optionalApiKey.isEmpty()) {
                log.warn("No available Gemini API keys. taskType={}, elapsedMs={}", request.taskType(), elapsedMillis(requestStartedAt));

                return LlmResponse.failure(
                        "Все Gemini-ключи временно недоступны",
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.NO_AVAILABLE_KEYS,
                        elapsedMillis(requestStartedAt)
                );
            }

            String apiKey = optionalApiKey.get();

            long attemptStartedAt = System.nanoTime();

            try {
                String answer = callGemini(request, apiKey);

                log.info("Gemini request succeeded. taskType={}, attempt={}/{}, model={}, elapsedMs={}, promptLength={}, answerLength={}", request.taskType(), attempt, MAX_GEMINI_ATTEMPTS, properties.getModel(), elapsedMillis(requestStartedAt), safeLength(request.userPrompt()), safeLength(answer));

                return LlmResponse.success(
                        answer,
                        "Gemini",
                        properties.getModel(),
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (HttpClientErrorException.NotFound e) {
                log.error("Gemini model was not found. taskType={}, model={}", request.taskType(), properties.getModel(), e);

                return LlmResponse.failure(
                        "Gemini-модель не найдена",
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.MODEL_NOT_FOUND,
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (HttpClientErrorException.TooManyRequests e) {
                geminiKeyPool.blockKey(
                        apiKey,
                        Duration.ofSeconds(60),
                        "429 Too Many Requests"
                );

                log.warn("Gemini request got 429. taskType={}, attempt={}/{}, elapsedMs={}", request.taskType(), attempt, MAX_GEMINI_ATTEMPTS, elapsedMillis(attemptStartedAt));
            }
            catch (HttpServerErrorException.ServiceUnavailable e) {
                geminiKeyPool.blockKey(
                        apiKey,
                        Duration.ofSeconds(30),
                        "503 Service Unavailable"
                );

                log.warn("Gemini request got 503. taskType={}, attempt={}/{}, elapsedMs={}", request.taskType(), attempt, MAX_GEMINI_ATTEMPTS, elapsedMillis(attemptStartedAt));
            }
            catch (org.springframework.web.client.ResourceAccessException e) {
                if (isTimeoutException(e)) {
                    log.warn("Gemini request timed out. taskType={}, model={}, attempt={}/{}, elapsedMs={}", request.taskType(), properties.getModel(), attempt, MAX_GEMINI_ATTEMPTS, elapsedMillis(requestStartedAt));

                    return LlmResponse.failure(
                            "Gemini request timeout",
                            "Gemini",
                            properties.getModel(),
                            LlmErrorType.TIMEOUT,
                            elapsedMillis(requestStartedAt)
                    );
                }

                log.error("Gemini network error. taskType={}, model={}, elapsedMs={}", request.taskType(), properties.getModel(), elapsedMillis(requestStartedAt), e);

                return LlmResponse.failure(
                        "Gemini network error",
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.NETWORK_ERROR,
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (Exception e) {
                log.error("Unexpected Gemini error. taskType={}, model={}, elapsedMs={}", request.taskType(), properties.getModel(), elapsedMillis(requestStartedAt), e);

                return LlmResponse.failure(
                        "Unexpected Gemini error",
                        "Gemini",
                        properties.getModel(),
                        LlmErrorType.UNEXPECTED_ERROR,
                        elapsedMillis(requestStartedAt)
                );
            }
        }

        return LlmResponse.failure(
                "Gemini attempts exhausted",
                "Gemini",
                properties.getModel(),
                LlmErrorType.SERVICE_UNAVAILABLE,
                elapsedMillis(requestStartedAt)
        );
    }

    private String callGemini(LlmRequest request, String apiKey) throws Exception {
        String url =
                "%s/%s:generateContent"
                        .formatted(
                                properties.getBaseUrl(),
                                properties.getModel()
                        );

        Map<String, Object> requestBody = new LinkedHashMap<>();

        if (!request.systemPrompt().isBlank()) {
            requestBody.put(
                    "system_instruction",
                    Map.of(
                            "parts",
                            List.of(
                                    Map.of(
                                            "text",
                                            request.systemPrompt()
                                    )
                            )
                    )
            );
        }

        requestBody.put(
                "contents",
                List.of(
                        Map.of(
                                "parts",
                                List.of(
                                        Map.of(
                                                "text",
                                                request.userPrompt()
                                        )
                                )
                        )
                )
        );

        Map<String, Object> generationConfig = new LinkedHashMap<>();

        generationConfig.put(
                "temperature",
                request.temperature()
        );

        generationConfig.put(
                "topP",
                request.topP()
        );

        generationConfig.put(
                "maxOutputTokens",
                request.maxOutputTokens()
        );

        if (request.responseFormat() == LlmResponseFormat.JSON) {
            generationConfig.put(
                    "responseMimeType",
                    "application/json"
            );
        }

        requestBody.put("generationConfig", generationConfig);

        RestClient selectedRestClient = selectRestClient(request.timeoutProfile());

        String responseJson = selectedRestClient.post()
                .uri(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractText(responseJson);
    }

    private RestClient createRestClient(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private RestClient selectRestClient(LlmTimeoutProfile timeoutProfile) {
        return timeoutProfile == LlmTimeoutProfile.FAST
                ? fastRestClient
                : standardRestClient;
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);

        JsonNode textNode =
                root.path("candidates")
                        .path(0)
                        .path("content")
                        .path("parts")
                        .path(0)
                        .path("text");

        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new IllegalStateException("Gemini response does not contain text");
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
        return value == null
                ? 0
                : value.length();
    }
}