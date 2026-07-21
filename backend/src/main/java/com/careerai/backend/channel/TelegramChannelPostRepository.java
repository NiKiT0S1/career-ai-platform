package com.careerai.backend.channel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для Telegram-постов канала.
 *
 * Используется для сохранения новых постов, обновления отредактированных постов
 * и поиска по сохранённым объявлениям.
 */

public interface TelegramChannelPostRepository extends JpaRepository<TelegramChannelPost, Long> {

    Optional<TelegramChannelPost> findByTelegramChatIdAndTelegramMessageId(Long telegramChatId, Long telegramMessageId);

    /**
     * Возвращает последние текстовые посты независимо
     * от freshness и архивного состояния.
     *
     * Используется служебной индексацией.
     */
    @Query("""
            SELECT post
            FROM TelegramChannelPost post
            WHERE post.text IS NOT NULL
              AND LENGTH(TRIM(post.text)) > 0
            ORDER BY COALESCE(
                post.editedAt,
                post.postedAt,
                post.createdAt
            ) DESC
            """)
    List<TelegramChannelPost> findLatestTextPosts(
            Pageable pageable
    );

    /**
     * Возвращает только публикации, которые разрешено
     * использовать в обычных ответах бота.
     */
    @Query("""
            SELECT post
            FROM TelegramChannelPost post
            WHERE post.text IS NOT NULL
              AND LENGTH(TRIM(post.text)) > 0
              AND post.archived = false
              AND post.freshnessStatus IN (
                  'ACTIVE',
                  'UNKNOWN'
              )
            ORDER BY COALESCE(
                post.editedAt,
                post.postedAt,
                post.createdAt
            ) DESC
            """)
    List<TelegramChannelPost> findLatestSearchableTextPosts(
            Pageable pageable
    );

    /**
     * Находит reply-посты, для которых
     * ещё не создана ни одна relation.
     */
    @Query("""
        SELECT post.id
        FROM TelegramChannelPost post
        WHERE post.replyToTelegramMessageId IS NOT NULL
          AND NOT EXISTS (
              SELECT relation.id
              FROM TelegramChannelPostRelation relation
              WHERE relation.sourcePost.id = post.id
          )
        ORDER BY post.createdAt ASC
        """)
    List<Long> findReplyPostIdsWithoutRelation(Pageable pageable);
}
