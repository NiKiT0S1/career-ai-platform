package com.careerai.backend.semantic;

/**
 * Embedding-вектор, прочитанный из PostgreSQL.
 *
 * sourceId указывает на исходную FAQ-запись или Telegram-пост.
 */

public record SemanticEmbeddingVector(
        long sourceId,
        double[] values
) {
}
