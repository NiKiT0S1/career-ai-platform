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

    /**
     * Исходный Telegram-пост, к которому относится эта metadata-запись.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private TelegramChannelPost post;

    /**
     * Тип поста: вакансия, практика, мероприятие, дедлайн или другое объявление.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false)
    private TelegramChannelPostType postType = TelegramChannelPostType.OTHER;

    /**
     * Короткое название объявления или позиции.
     */
    @Column(name = "title", length = 500)
    private String title;

    /**
     * Название компании, если оно указано в посте.
     */
    @Column(name = "company", length = 500)
    private String company;

    /**
     * Технологии через запятую.
     *
     * Например: Java, Spring Boot, PostgreSQL.
     */
    @Column(name = "technologies", columnDefinition = "TEXT")
    private String technologies;

    /**
     * Уровень позиции, если он указан.
     *
     * Например: Intern, Junior, Middle, Senior.
     */
    @Column(name = "level_text")
    private String levelText;

    /**
     * Формат работы или участия.
     *
     * Например: удалёнка, офис, гибрид, стажировка.
     */
    @Column(name = "format_text")
    private String formatText;

    /**
     * Текстовое описание дедлайна.
     *
     * Пока храним именно текст, потому что в постах даты могут быть неполными:
     * "30 июня", "не скоро", "до конца недели".
     */
    @Column(name = "deadline_text", length = 500)
    private String deadlineText;

    /**
     * Текстовая дата начала производственной практики.
     */
    @Column(name = "practice_start_text")
    private String practiceStartText;

    /**
     * Текстовая дата окончания производственной практики.
     */
    @Column(name = "practice_end_text")
    private String practiceEndText;

    /**
     * Краткое содержание поста.
     */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /**
     * Показывает, связан ли пост с производственной практикой.
     */
    @Column(name = "is_relevant_for_practice", nullable = false)
    private Boolean relevantForPractice = false;

    /**
     * Статус извлечения структуры из поста.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false)
    private TelegramChannelPostExtractionStatus extractionStatus =
            TelegramChannelPostExtractionStatus.PENDING;

    /**
     * Текст ошибки, если извлечение структуры не удалось.
     */
    @Column(name = "extraction_error", columnDefinition = "TEXT")
    private String extractionError;

    /**
     * Время успешного извлечения структуры.
     */
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
