package com.careerai.backend.channel;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для Telegram-постов канала.
 *
 * Используется для сохранения новых постов и обновления уже существующих,
 * если Telegram прислал edited_channel_post.
 */

public interface TelegramChannelPostRepository extends JpaRepository<TelegramChannelPost, Long> {

    Optional<TelegramChannelPost> findByTelegramChatIdAndTelegramMessageId(Long telegramChatId, Long telegramMessageId);

    @Query("""
            SELECT post
            FROM TelegramChannelPost post
            WHERE post.text IS NOT NULL
              AND (
                    LOWER(post.text) LIKE '%ваканс%'
                 OR LOWER(post.text) LIKE '%стажир%'
                 OR LOWER(post.text) LIKE '%intern%'
                 OR LOWER(post.text) LIKE '%junior%'
                 OR LOWER(post.text) LIKE '%middle%'
                 OR LOWER(post.text) LIKE '%senior%'
                 OR LOWER(post.text) LIKE '%работ%'
                 OR LOWER(post.text) LIKE '%позици%'
                 OR LOWER(post.text) LIKE '%дедлайн%'
              )
            ORDER BY COALESCE(post.editedAt, post.postedAt, post.createdAt) DESC
            """)
    List<TelegramChannelPost> findLatestOpportunityPosts(Pageable pageable);
}
