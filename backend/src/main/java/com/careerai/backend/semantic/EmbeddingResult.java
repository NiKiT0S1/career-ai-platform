package com.careerai.backend.semantic;

/**
 * Результат генерации embedding-вектора.
 *
 * При ошибке вместо исключения возвращается неуспешный результат,
 * чтобы Semantic RAG мог безопасно переключиться на старую логику поиска.
 */

public record EmbeddingResult(
        boolean success,
        double[] values,
        String model,
        long elapsedMillis,
        String errorMessage
) {

    public static EmbeddingResult success(
            double[] values,
            String model,
            long elapsedMillis
    ) {
        return new EmbeddingResult(
                true,
                values,
                model,
                elapsedMillis,
                null
        );
    }

    public static EmbeddingResult failure(
            String model,
            long elapsedMillis,
            String errorMessage
    ) {
        return new EmbeddingResult(
                false,
                new double[0],
                model,
                elapsedMillis,
                errorMessage
        );
    }

    public boolean failed() {
        return !success;
    }
}
