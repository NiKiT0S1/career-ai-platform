package com.careerai.backend.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий явных связей
 * между Telegram-публикациями.
 */

public interface TelegramChannelPostRelationRepository
        extends JpaRepository<TelegramChannelPostRelation, Long> {

    Optional<TelegramChannelPostRelation>
    findBySourcePostIdAndTargetPostIdAndRelationType(
            Long sourcePostId,
            Long targetPostId,
            TelegramChannelPostRelationType relationType
    );

    /**
     * Находит связи, в которых указанные посты
     * являются исходными или уточняющими.
     *
     * Метод понадобится на следующем шаге,
     * когда будем расширять результаты RAG-поиска.
     */
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
            @Param("postIds") Collection<Long> postIds
    );
}