package com.careerai.backend.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Структурированная информация, извлечённая из Telegram-поста канала.
 *
 * Эта сущность хранит не сырой текст поста, а его смысловую структуру:
 * тип поста, название позиции, компанию, технологии, дедлайны, формат
 * и краткое описание. Эти данные позже будут использоваться для более
 * точного поиска и RAG-ответов.
 */

@Entity
@Table(
        name = "telegram_channel_post_metadata",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_telegram_channel_post_metadata_post_id",
                        columnNames = "post_id"
                )
        }
)
@Getter
@Setter
public class TelegramChannelPostMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private TelegramChannelPost post;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false)
    private TelegramChannelPostType postType = TelegramChannelPostType.OTHER;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "company", length = 500)
    private String company;

    @Column(name = "technologies", columnDefinition = "TEXT")
    private String technologies;

    @Column(name = "level_text")
    private String levelText;

    @Column(name = "format_text")
    private String formatText;

    @Column(name = "deadline_text", length = 500)
    private String deadlineText;

    @Column(name = "practice_start_text")
    private String practiceStartText;

    @Column(name = "practice_end_text")
    private String practiceEndText;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "is_relevant_for_practice", nullable = false)
    private Boolean relevantForPractice = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false)
    private TelegramChannelPostExtractionStatus extractionStatus =
            TelegramChannelPostExtractionStatus.PENDING;

    @Column(name = "extraction_error", columnDefinition = "TEXT")
    private String extractionError;

    @Column(name = "extracted_at")
    private OffsetDateTime extractedAt;

    @Column(name = "created_at",  nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();

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
