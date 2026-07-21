package com.careerai.backend.channel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий связей Telegram-публикаций.
 */

public interface TelegramChannelPostRelationRepository extends JpaRepository<TelegramChannelPostRelation, Long> {

    Optional<TelegramChannelPostRelation> findBySourcePostIdAndTargetPostId(Long sourcePostId, Long targetPostId);

    /**
     * Оставляем для совместимости
     * со старым кодом и тестами.
     */
    Optional<TelegramChannelPostRelation> findBySourcePostIdAndTargetPostIdAndRelationType(
            Long sourcePostId,
            Long targetPostId,
            TelegramChannelPostRelationType relationType
    );

    @Query("""
            SELECT relation
            FROM TelegramChannelPostRelation relation
            JOIN FETCH relation.sourcePost sourcePost
            JOIN FETCH relation.targetPost targetPost
            WHERE sourcePost.id IN :postIds
               OR targetPost.id IN :postIds
            ORDER BY relation.createdAt ASC
            """)
    List<TelegramChannelPostRelation> findConnectedToPostIds(
            @Param("postIds")
            Collection<Long> postIds
    );

    @Query("""
            SELECT relation
            FROM TelegramChannelPostRelation relation
            JOIN FETCH relation.targetPost targetPost
            WHERE relation.sourcePost.id = :sourcePostId
            ORDER BY relation.createdAt ASC
            """)
    List<TelegramChannelPostRelation> findAllBySourcePostIdWithTarget(
            @Param("sourcePostId")
            Long sourcePostId
    );

    @Query("""
            SELECT relation
            FROM TelegramChannelPostRelation relation
            JOIN FETCH relation.sourcePost sourcePost
            JOIN FETCH relation.targetPost targetPost
            WHERE sourcePost.id = :sourcePostId
              AND relation.relationOrigin IN :origins
            ORDER BY relation.createdAt ASC
            """)
    List<TelegramChannelPostRelation> findAutomaticBySourcePostId(
            @Param("sourcePostId")
            Long sourcePostId,
            @Param("origins")
            Collection<TelegramChannelPostRelationOrigin> origins
    );

    @Query("""
            SELECT relation
            FROM TelegramChannelPostRelation relation
            JOIN FETCH relation.sourcePost sourcePost
            JOIN FETCH relation.targetPost targetPost
            WHERE relation.id = :relationId
            """)
    Optional<TelegramChannelPostRelation> findByIdWithPosts(
            @Param("relationId")
            Long relationId
    );

    @Query("""
            SELECT relation.id
            FROM TelegramChannelPostRelation relation
            WHERE relation.classificationStatus IN :statuses
              AND relation.classificationAttemptCount < :maxAttempts
              AND (
                    relation.classificationNextAttemptAt IS NULL
                 OR relation.classificationNextAttemptAt <= :now
              )
            ORDER BY relation.createdAt ASC
            """)
    List<Long> findReadyRelationIds(
            @Param("statuses")
            Collection<TelegramChannelPostRelationClassificationStatus> statuses,
            @Param("maxAttempts")
            int maxAttempts,
            @Param("now")
            OffsetDateTime now,
            Pageable pageable
    );

    @Query("""
            SELECT relation.sourcePost.id
            FROM TelegramChannelPostRelation relation
            WHERE relation.relationOrigin IN :origins
            ORDER BY relation.updatedAt ASC
            """)
    List<Long> findAutomaticSourcePostIds(
            @Param("origins")
            Collection<TelegramChannelPostRelationOrigin> origins,
            Pageable pageable
    );

    /**
     * Атомарно захватывает связь.
     *
     * Только один поток сможет получить результат 1.
     */
    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query(
            value = """
                    UPDATE telegram_channel_post_relations
                    SET
                        classification_status = 'PROCESSING',
                        classification_attempt_count =
                            classification_attempt_count + 1,
                        processing_started_at = :now,
                        classification_next_attempt_at = NULL,
                        classification_error = NULL,
                        updated_at = :now
                    WHERE id = :relationId
                      AND classification_status IN (
                          'PENDING',
                          'FAILED'
                      )
                      AND classification_attempt_count
                          < :maxAttempts
                      AND (
                            classification_next_attempt_at IS NULL
                         OR classification_next_attempt_at <= :now
                      )
                    """,
            nativeQuery = true
    )
    int claimForClassification(
            @Param("relationId")
            Long relationId,
            @Param("now")
            OffsetDateTime now,
            @Param("maxAttempts")
            int maxAttempts
    );

    @Modifying(
            clearAutomatically = true,
            flushAutomatically = true
    )
    @Query(
            value = """
                    UPDATE telegram_channel_post_relations
                    SET
                        classification_status = 'FAILED',
                        processing_started_at = NULL,
                        classification_next_attempt_at =
                            :nextAttemptAt,
                        classification_error = :errorMessage,
                        updated_at = :now
                    WHERE classification_status = 'PROCESSING'
                      AND processing_started_at < :staleBefore
                    """,
            nativeQuery = true
    )
    int resetStaleProcessing(
            @Param("staleBefore")
            OffsetDateTime staleBefore,
            @Param("nextAttemptAt")
            OffsetDateTime nextAttemptAt,
            @Param("now")
            OffsetDateTime now,
            @Param("errorMessage")
            String errorMessage
    );
}