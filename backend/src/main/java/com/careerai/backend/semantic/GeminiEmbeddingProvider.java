package com.careerai.backend.semantic;

import com.careerai.backend.ai.GeminiKeyPool;
import com.careerai.backend.ai.GeminiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Создаёт embedding-векторы через Gemini Embedding API.
 *
 * Провайдер используется только при включённом feature flag
 * semantic-search.enabled.
 */

@Service
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingProvider.class);

    private static final int MAX_ATTEMPTS = 2;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(25);

    private final SemanticSearchProperties semanticProperties;
    private final GeminiProperties geminiProperties;
    private final GeminiKeyPool geminiKeyPool;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GeminiEmbeddingProvider(
            SemanticSearchProperties semanticProperties,
            GeminiProperties geminiProperties,
            GeminiKeyPool geminiKeyPool,
            ObjectMapper objectMapper
    ) {
        this.semanticProperties = semanticProperties;
        this.geminiProperties = geminiProperties;
        this.geminiKeyPool = geminiKeyPool;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public EmbeddingResult embedQuery(String text) {
        if (text == null || text.isBlank()) {
            return failureImmediately("Embedding query text is empty");
        }

        String preparedText = """
                task: question answering | query: %s
                """.formatted(text.trim());

        return generateEmbedding(preparedText);
    }

    @Override
    public EmbeddingResult embedDocument(String title, String text) {
        if (text == null || text.isBlank()) {
            return failureImmediately("Embedding document text is empty");
        }

        String preparedTitle = title == null || title.isBlank()
                ? "none"
                : normalizeTitle(title);

        String preparedText = """
                title: %s | text: %s
                """.formatted(preparedTitle, text.trim());

        return generateEmbedding(preparedText);
    }

    private EmbeddingResult generateEmbedding(String preparedText) {
        long requestStartedAt = System.nanoTime();

        if (!semanticProperties.isEnabled()) {
            return EmbeddingResult.failure(
                    semanticProperties.getEmbeddingModel(),
                    elapsedMillis(requestStartedAt),
                    "Semantic search is disabled"
            );
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            var optionalApiKey = geminiKeyPool.getAvailableKey();

            if (optionalApiKey.isEmpty()) {
                log.warn("No available Gemini keys for embedding request");

                return EmbeddingResult.failure(
                        semanticProperties.getEmbeddingModel(),
                        elapsedMillis(requestStartedAt),
                        "No available Gemini API keys"
                );
            }

            String apiKey = optionalApiKey.get();

            try {
                double[] embedding = callGeminiEmbedding(
                        preparedText,
                        apiKey
                );

                log.info(
                        "Gemini embedding succeeded in {} ms. Attempt {}/{}. Model: {}. Dimensions: {}",
                        elapsedMillis(requestStartedAt),
                        attempt,
                        MAX_ATTEMPTS,
                        semanticProperties.getEmbeddingModel(),
                        embedding.length
                );

                return EmbeddingResult.success(
                        embedding,
                        semanticProperties.getEmbeddingModel(),
                        elapsedMillis(requestStartedAt)
                );
            }
            catch (HttpClientErrorException.NotFound exception) {
                log.error(
                        "Gemini embedding model was not found. Model: {}",
                        semanticProperties.getEmbeddingModel(),
                        exception
                );

                return EmbeddingResult.failure(
                        semanticProperties.getEmbeddingModel(),
                        elapsedMillis(requestStartedAt),
                        "Embedding model was not found"
                );
            }
            catch (HttpClientErrorException.TooManyRequests exception) {
                log.warn(
                        "Gemini embedding request got 429. Attempt {}/{}",
                        attempt,
                        MAX_ATTEMPTS
                );
            }
            catch (HttpServerErrorException.ServiceUnavailable exception) {
                log.warn(
                        "Gemini embedding service is unavailable. Attempt {}/{}",
                        attempt,
                        MAX_ATTEMPTS
                );
            }
            catch (ResourceAccessException exception) {
                String errorMessage = isTimeoutException(exception)
                        ? "Embedding request timed out"
                        : "Embedding network error";

                log.warn(
                        "{} after {} ms",
                        errorMessage,
                        elapsedMillis(requestStartedAt)
                );

                return EmbeddingResult.failure(
                        semanticProperties.getEmbeddingModel(),
                        elapsedMillis(requestStartedAt),
                        errorMessage
                );
            }
            catch (Exception exception) {
                log.error(
                        "Unexpected Gemini embedding error after {} ms",
                        elapsedMillis(requestStartedAt),
                        exception
                );

                return EmbeddingResult.failure(
                        semanticProperties.getEmbeddingModel(),
                        elapsedMillis(requestStartedAt),
                        "Unexpected embedding error"
                );
            }
        }

        return EmbeddingResult.failure(
                semanticProperties.getEmbeddingModel(),
                elapsedMillis(requestStartedAt),
                "All embedding attempts were exhausted"
        );
    }

    private double[] callGeminiEmbedding(String preparedText, String apiKey) throws Exception {
        String model = semanticProperties.getEmbeddingModel();

        String url = "%s/%s:embedContent"
                .formatted(
                        geminiProperties.getBaseUrl(),
                        model
                );

        Map<String, Object> requestBody = Map.of(
                "model", "models/" + model,
                "content", Map.of(
                        "parts", List.of(
                                Map.of("text", preparedText)
                        )
                ),
                "output_dimensionality",
                semanticProperties.getOutputDimensions()
        );

        String responseJson = restClient.post()
                .uri(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return extractEmbedding(responseJson);
    }

    private double[] extractEmbedding(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);

        JsonNode valuesNode = root
                .path("embedding")
                .path("values");

        if (!valuesNode.isArray() || valuesNode.isEmpty()) {
            throw new IllegalStateException("Gemini embedding response does not contain embedding values");
        }

        double[] values = new double[valuesNode.size()];

        for (int i = 0; i < valuesNode.size(); i++) {
            values[i] = valuesNode.get(i).asDouble();
        }

        return values;
    }

    private EmbeddingResult failureImmediately(String errorMessage) {
        return EmbeddingResult.failure(
                semanticProperties.getEmbeddingModel(),
                0,
                errorMessage
        );
    }

    private String normalizeTitle(String title) {
        return title
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
}
