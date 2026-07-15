package com.careerai.backend.semantic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

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

    public SemanticQueryEmbeddingService(
            SemanticSearchProperties properties,
            EmbeddingProvider embeddingProvider
    ) {
        this.properties = properties;
        this.embeddingProvider = embeddingProvider;
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

        EmbeddingResult result =
                embeddingProvider.embedQuery(userMessage);

        if (result.failed()) {
            log.warn(
                    "Shared query embedding failed. error={}",
                    result.errorMessage()
            );

            return Optional.empty();
        }

        if (result.values() == null
                || result.values().length
                != properties.getOutputDimensions()) {

            log.warn(
                    "Shared query embedding has unexpected dimensions. expected={}, actual={}",
                    properties.getOutputDimensions(),
                    result.values() == null
                            ? 0
                            : result.values().length
            );

            return Optional.empty();
        }

        log.info(
                "Shared query embedding prepared. model={}, dimensions={}, elapsedMs={}",
                result.model(),
                result.values().length,
                result.elapsedMillis()
        );

        return Optional.of(result);
    }
}