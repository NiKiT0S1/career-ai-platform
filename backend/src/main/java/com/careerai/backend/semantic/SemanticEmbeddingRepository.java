package com.careerai.backend.semantic;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Arrays;

/**
 * Выполняет операции с сохранёнными embedding-векторами.
 */

@Repository
public class SemanticEmbeddingRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO semantic_embeddings (
                source_type,
                source_id,
                content_hash,
                embedding_model,
                embedding_dimensions,
                embedding,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (source_type, source_id)
            DO UPDATE SET
                content_hash = EXCLUDED.content_hash,
                embedding_model = EXCLUDED.embedding_model,
                embedding_dimensions = EXCLUDED.embedding_dimensions,
                embedding = EXCLUDED.embedding,
                updated_at = NOW()
            """;

    private final JdbcTemplate jdbcTemplate;

    public SemanticEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Проверяет, существует ли актуальный embedding для исходной записи.
     */
    public boolean isUpToDate(
            SemanticSourceType sourceType,
            long sourceId,
            String contentHash,
            String embeddingModel,
            int embeddingDimensions
    ) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM semantic_embeddings
                    WHERE source_type = ?
                      AND source_id = ?
                      AND content_hash = ?
                      AND embedding_model = ?
                      AND embedding_dimensions = ?
                )
                """,
                Boolean.class,
                sourceType.name(),
                sourceId,
                contentHash,
                embeddingModel,
                embeddingDimensions
        );

        return Boolean.TRUE.equals(exists);
    }

    /**
     * Создаёт новый embedding или обновляет существующий.
     */
    public void saveOrUpdate(
            SemanticSourceType sourceType,
            long sourceId,
            String contentHash,
            String embeddingModel,
            double[] embedding
    ) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("Embedding vector cannot be empty");
        }

        Double[] boxedEmbedding = Arrays.stream(embedding)
                .boxed()
                .toArray(Double[]::new);

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            Array sqlArray = connection.createArrayOf(
                    "float8",
                    boxedEmbedding
            );

            try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                statement.setString(1, sourceType.name());
                statement.setLong(2, sourceId);
                statement.setString(3, contentHash);
                statement.setString(4, embeddingModel);
                statement.setInt(5, embedding.length);
                statement.setArray(6, sqlArray);

                statement.executeUpdate();
            }
            finally {
                sqlArray.free();
            }

            return null;
        });
    }

    public long countBySourceType(SemanticSourceType sourceType) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM semantic_embeddings
                WHERE source_type = ?
                """,
                Long.class,
                sourceType.name()
        );

        return count == null ? 0 : count;
    }
}
