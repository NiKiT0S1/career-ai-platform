package com.careerai.backend.semantic;

import com.careerai.backend.answer.AnswerCacheProperties;
import com.careerai.backend.answer.BoundedQueryCache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;

/**
 * Создаёт единый embedding пользовательского запроса.
 *
 * Полученный вектор может одновременно использоваться
 * для поиска по FAQ и Telegram-постам.
 */

@Service
public class SemanticQueryEmbeddingService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    SemanticQueryEmbeddingService.class
            );

    private final SemanticSearchProperties properties;
    private final EmbeddingProvider embeddingProvider;
    private final BoundedQueryCache<EmbeddingResult> cache;

    public SemanticQueryEmbeddingService(
            SemanticSearchProperties properties,
            EmbeddingProvider embeddingProvider,
            AnswerCacheProperties cacheProperties,
            Clock clock
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
        this.cache = new BoundedQueryCache<>(cacheProperties.embeddingSize(), cacheProperties.embeddingTtlSeconds(), clock);
    }

    /**
     * Возвращает пустой результат, если Semantic Search отключён
     * или embedding не удалось создать.
     */
    public Optional<EmbeddingResult> createQueryEmbedding(
            String userMessage
    ) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }

        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        String namespace = "embedding-v1|" + properties.getEmbeddingModel() + '|' + properties.getOutputDimensions();
        return Optional.ofNullable(cache.get(namespace, userMessage, () -> embedUncached(userMessage)))
                .map(this::copy);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    private EmbeddingResult embedUncached(String userMessage) {
        EmbeddingResult result;
        try {
            result = embeddingProvider.embedQuery(userMessage);
        } catch (RuntimeException exception) {
            log.warn("Query embedding unavailable. type={}", exception.getClass().getSimpleName());
            return null;
        }

        if (result == null || result.failed()) {
            log.warn(
                    "Shared query embedding failed. error={}",
                    result == null ? "empty provider response" : result.errorMessage()
            );

            return null;
        }

        if (!Objects.equals(result.model(), properties.getEmbeddingModel())
                || result.values() == null
                || result.values().length
                != properties.getOutputDimensions()
                || result.values().length == 0
                || Arrays.stream(result.values()).anyMatch(value -> !Double.isFinite(value))
                || Arrays.stream(result.values()).allMatch(value -> value == 0.0)) {

            log.warn(
                    "Shared query embedding has invalid model, dimensions or vector. expectedDimensions={}, actualDimensions={}",
                    properties.getOutputDimensions(),
                    result.values() == null
                            ? 0
                            : result.values().length
            );

            return null;
        }

        log.info(
                "Shared query embedding prepared. model={}, dimensions={}, elapsedMs={}",
                result.model(),
                result.values().length,
                result.elapsedMillis()
        );

        return copy(result);
    }

    private EmbeddingResult copy(EmbeddingResult result) {
        return EmbeddingResult.success(result.values().clone(), result.model(), result.elapsedMillis());
    }
}
