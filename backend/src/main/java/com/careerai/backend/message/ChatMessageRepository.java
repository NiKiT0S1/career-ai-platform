package com.careerai.backend.message;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Репозиторий для сохранения и чтения сообщений Telegram-чата.
 *
 * Используется для хранения входящих сообщений студентов и ответов CareerAI.
 * В будущем эту историю можно будет использовать для памяти диалога и аналитики.
 */

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop20ByTelegramChatIdOrderByCreatedAtDesc(Long telegramChatId);
}
