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

    @Query("""
            SELECT post
            FROM TelegramChannelPost post
            WHERE post.text IS NOT NULL
              AND LENGTH(TRIM(post.text)) > 0
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPost> findLatestTextPosts(Pageable pageable);
}
