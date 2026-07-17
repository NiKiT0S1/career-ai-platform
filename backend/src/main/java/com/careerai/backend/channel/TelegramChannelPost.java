package com.careerai.backend.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Сущность Telegram-поста из канала ЦКиТа.
 *
 * Хранит исходный текст поста, идентификаторы Telegram, дату публикации,
 * дату редактирования и сырой JSON update. Эти данные позже будут использоваться
 * для поиска, архивации, классификации вакансий и RAG.
 */

@Entity
@Table(
        name = "telegram_channel_posts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_telegram_channel_post",
                        columnNames = {"telegram_chat_id", "telegram_message_id"}
                )
        }
)
@Getter
@Setter
public class TelegramChannelPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_chat_id", nullable = false)
    private Long telegramChatId;

    @Column(name = "telegram_message_id", nullable = false)
    private Long telegramMessageId;

    @Column(name = "channel_title")
    private String channelTitle;

    @Column(name = "channel_username")
    private String channelUsername;

    @Column(name = "text", columnDefinition = "TEXT")
    private String text;

    @Column(name = "raw_update_json", columnDefinition = "TEXT")
    private String rawUpdateJson;

    @Column(name = "posted_at")
    private OffsetDateTime postedAt;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    /**
     * Текущее состояние актуальности публикации.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "freshness_status", nullable = false)
    private TelegramChannelPostFreshnessStatus freshnessStatus = TelegramChannelPostFreshnessStatus.UNKNOWN;

    /**
     * Момент, после которого публикация считается истёкшей.
     *
     * Пока может быть null, если срок не удалось определить.
     */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    /**
     * Объяснение установленного freshness-статуса.
     *
     * Например: "дедлайн уже прошёл" или
     * "невозможно распознать дату 33 июня".
     */
    @Column(name = "freshness_reason", columnDefinition = "TEXT")
    private String freshnessReason;

    /**
     * Время последней проверки актуальности.
     */
    @Column(name = "freshness_checked_at")
    private OffsetDateTime freshnessCheckedAt;

    /**
     * Показывает, исключил ли администратор публикацию
     * из обычных ответов бота.
     */
    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    /**
     * Время ручного архивирования.
     */
    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    /**
     * Причина ручного архивирования.
     */
    @Column(name = "archive_reason", columnDefinition = "TEXT")
    private String archivedReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();

        if (freshnessStatus == null) {
            freshnessStatus = TelegramChannelPostFreshnessStatus.UNKNOWN;
        }

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
