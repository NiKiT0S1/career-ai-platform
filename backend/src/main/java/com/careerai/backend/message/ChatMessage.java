package com.careerai.backend.message;

import com.careerai.backend.user.TelegramUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity-класс одного сообщения в Telegram-чате.
 *
 * CareerAI сохраняет как сообщения студентов, так и собственные ответы.
 * Эта история нужна для будущей кратковременной памяти диалога, анализа взаимодействий
 * и возможных функций поддержки.
 */

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "telegram_user_id")
    private TelegramUser telegramUser;

    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private MessageRole role;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "telegram_message_id")
    private Long telegramMessageId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
