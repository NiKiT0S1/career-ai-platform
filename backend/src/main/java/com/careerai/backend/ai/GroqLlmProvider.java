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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реализация LlmProvider через Groq API.
 */

@Service
public class GroqLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqLlmProvider.class);
    private static final int GPT_OSS_REASONING_TOKEN_ALLOWANCE = 1024;
    private static final int GPT_OSS_MAX_COMPLETION_TOKENS = 65536;

    private final GroqProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient fastRestClient;
    private final RestClient standardRestClient;

    public GroqLlmProvider(
            GroqProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        this.fastRestClient = createRestClient(Duration.ofSeconds(3), Duration.ofSeconds(4));

        this.standardRestClient = createRestClient(
                Duration.ofSeconds(properties.getConnectTimeoutSeconds()),
                Duration.ofSeconds(properties.getReadTimeoutSeconds())
        );
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        long startedAt = System.nanoTime();

        if (properties.getKey() == null || properties.getKey().isBlank()) {
            log.warn("Groq API key is not configured. taskType={}", request.taskType());

            return LlmResponse.failure(
                    "Groq API key is not configured",
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.UNEXPECTED_ERROR,
                    elapsedMillis(startedAt)
            );
        }

        try {
            String answer = callGroq(request);

            log.info("Groq request succeeded. taskType={}, model={}, elapsedMs={}, promptLength={}, answerLength={}", request.taskType(), properties.getModel(), elapsedMillis(startedAt), safeLength(request.userPrompt()), safeLength(answer));

            return LlmResponse.success(
                    answer,
                    "Groq",
                    properties.getModel(),
                    elapsedMillis(startedAt)
            );
        }
        catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Groq request got 429. taskType={}, model={}, elapsedMs={}", request.taskType(), properties.getModel(), elapsedMillis(startedAt));

            return LlmResponse.failure(
                    "Groq rate limit",
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.RATE_LIMIT,
                    elapsedMillis(startedAt)
            );
        }
        catch (HttpClientErrorException.NotFound e) {
            boolean modelNotFound = isModelNotFound(e);
            if (modelNotFound) {
                log.warn("Groq model was not found or is inaccessible. Check GROQ_API_MODEL. taskType={}, model={}, elapsedMs={}",
                        request.taskType(), properties.getModel(), elapsedMillis(startedAt));
            } else {
                log.warn("Groq request returned 404. Check Groq API base URL. taskType={}, model={}, elapsedMs={}",
                        request.taskType(), properties.getModel(), elapsedMillis(startedAt));
            }

            return LlmResponse.failure(
                    modelNotFound ? "Groq model was not found or is inaccessible" : "Groq request returned 404",
                    "Groq",
                    properties.getModel(),
                    modelNotFound ? LlmErrorType.MODEL_NOT_FOUND : LlmErrorType.UNEXPECTED_ERROR,
                    elapsedMillis(startedAt)
            );
        }
        catch (TruncatedCompletionException e) {
            log.warn("Groq completion reached its token limit. taskType={}, model={}, elapsedMs={}",
                    request.taskType(), properties.getModel(), elapsedMillis(startedAt));

            return LlmResponse.failure(
                    "Groq completion reached its token limit",
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.UNEXPECTED_ERROR,
                    elapsedMillis(startedAt)
            );
        }
        catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("Groq timeout or network error. taskType={}, model={}, elapsedMs={}", request.taskType(), properties.getModel(), elapsedMillis(startedAt));

            return LlmResponse.failure(
                    "Groq timeout or network error",
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.TIMEOUT,
                    elapsedMillis(startedAt)
            );
        }
        catch (Exception e) {
            log.error("Unexpected Groq error. taskType={}, model={}, elapsedMs={}", request.taskType(), properties.getModel(), elapsedMillis(startedAt), e);

            return LlmResponse.failure(
                    "Unexpected Groq error",
                    "Groq",
                    properties.getModel(),
                    LlmErrorType.UNEXPECTED_ERROR,
                    elapsedMillis(startedAt)
            );
        }
    }

    private String callGroq(LlmRequest request) throws Exception {
        String url = properties.getBaseUrl() + "/chat/completions";

        List<Map<String, String>> messages;

        if (request.systemPrompt().isBlank()) {
            messages = List.of(Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    request.userPrompt()
                            )
            );
        }
        else {
            messages = List.of(
                            Map.of(
                                    "role",
                                    "system",
                                    "content",
                                    request.systemPrompt()
                            ),
                            Map.of(
                                    "role",
                                    "user",
                                    "content",
                                    request.userPrompt()
                            )
                    );
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();

        requestBody.put(
                "model",
                properties.getModel()
        );

        requestBody.put(
                "messages",
                messages
        );

        requestBody.put(
                "temperature",
                request.temperature()
        );

        requestBody.put(
                "top_p",
                request.topP()
        );

        if (isGptOssModel()) {
            // GPT-OSS counts hidden reasoning and visible text within the same completion limit.
            requestBody.put("max_completion_tokens", Math.min(GPT_OSS_MAX_COMPLETION_TOKENS,
                    (long) request.maxOutputTokens() + GPT_OSS_REASONING_TOKEN_ALLOWANCE));
            requestBody.put("reasoning_effort", "low");
            requestBody.put("include_reasoning", false);
        } else {
            requestBody.put("max_tokens", request.maxOutputTokens());
        }

        if (request.responseFormat() == LlmResponseFormat.JSON) {
            requestBody.put(
                    "response_format",
                    Map.of(
                            "type",
                            "json_object"
                    )
            );
        }

        RestClient selectedRestClient = selectRestClient(request.timeoutProfile());

        String responseJson =
                selectedRestClient.post()
                        .uri(url)
                        .header(
                                "Authorization",
                                "Bearer "
                                        + properties.getKey()
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
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
        JsonNode choice = root.path("choices").path(0);

        if ("length".equals(choice.path("finish_reason").asText())) {
            throw new TruncatedCompletionException();
        }

        JsonNode contentNode =
                choice.path("message")
                        .path("content");

        if (contentNode.isMissingNode() || contentNode.isNull() || contentNode.asText().isBlank()) {
            throw new IllegalStateException("Groq response does not contain text");
        }

        return contentNode.asText();
    }

    private boolean isGptOssModel() {
        return "openai/gpt-oss-20b".equals(properties.getModel())
                || "openai/gpt-oss-120b".equals(properties.getModel());
    }

    private boolean isModelNotFound(HttpClientErrorException.NotFound exception) {
        try {
            JsonNode error = objectMapper.readTree(exception.getResponseBodyAsString());
            return "model_not_found".equals(error.path("error").path("code").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class TruncatedCompletionException extends IllegalStateException {
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
