package com.careerai.backend.channel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий для Telegram-постов канала.
 *
 * Используется для сохранения новых постов и обновления уже существующих,
 * если Telegram прислал edited_channel_post.
 */

public interface TelegramChannelPostRepository extends JpaRepository<TelegramChannelPost, Long> {

    Optional<TelegramChannelPost> findByTelegramChatIdAndTelegramMessageId(Long telegramChatId, Long telegramMessageId);
}
