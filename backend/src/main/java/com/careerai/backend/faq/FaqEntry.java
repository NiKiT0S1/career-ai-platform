package com.careerai.backend.faq;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Сущность FAQ-записи Центра карьеры и трудоустройства.
 *
 * Хранит вопрос, короткий ответ для команды /faq,
 * полный ответ для точного пользовательского вопроса,
 * ключевые слова и служебные поля для управления актуальностью записи.
 */

@Entity
@Table(
        name = "faq_entries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_faq_entries_slug",
                        columnNames = "slug"
                )
        }
)
@Getter
@Setter
public class FaqEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category", nullable = false, length = 100)
    private String category;

    @Column(name = "slug", nullable = false, length = 150)
    private String slug;

    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "short_answer", nullable = false, columnDefinition = "TEXT")
    private String shortAnswer;

    @Column(name = "full_answer", nullable = false, columnDefinition = "TEXT")
    private String fullAnswer;

    @Column(name = "keywords", columnDefinition = "TEXT")
    private String keywords;

    @Column(name = "priority", nullable = false)
    private Integer priority = 100;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
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

        if (priority == null) {
            priority = 100;
        }

        if (active == null) {
            active = true;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
