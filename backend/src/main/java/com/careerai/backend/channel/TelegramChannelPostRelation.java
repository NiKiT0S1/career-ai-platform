package com.careerai.backend.channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Явная связь между более новым постом
 * и исходной Telegram-публикацией.
 */

@Entity
@Table(
        name = "telegram_channel_post_relations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_channel_post_relation_pair",
                        columnNames = {
                                "source_post_id",
                                "target_post_id"
                        }
                )
        }
)
@Getter
@Setter
public class TelegramChannelPostRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Более новый пост:
     * дополнение, исправление или отмена.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "source_post_id",
            nullable = false
    )
    private TelegramChannelPost sourcePost;

    /**
     * Исходный пост, к которому относится
     * новое сообщение.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "target_post_id",
            nullable = false
    )
    private TelegramChannelPost targetPost;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "relation_type",
            nullable = false
    )
    private TelegramChannelPostRelationType relationType = TelegramChannelPostRelationType.UNCLASSIFIED;

    /**
     * Итоговое описание изменения.
     *
     * Может быть null, пока автоматическая
     * классификация ещё не завершена.
     */
    @Column(
            name = "reason",
            columnDefinition = "TEXT"
    )
    private String reason;

    /**
     * Источник возникновения связи.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "relation_origin", nullable = false)
    private TelegramChannelPostRelationOrigin relationOrigin = TelegramChannelPostRelationOrigin.MANUAL;

    /**
     * Уверенность автоматического классификатора.
     *
     * Для ручных связей может быть null.
     */
    @Column(name = "classification_confidence")
    private Double classificationConfidence;

    /**
     * Текущее состояние автоматической классификации.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "classification_status",
            nullable = false
    )
    private TelegramChannelPostRelationClassificationStatus
            classificationStatus =
            TelegramChannelPostRelationClassificationStatus.NOT_REQUIRED;

    /**
     * Предложенный моделью тип.
     *
     * Используется, когда результат требует review.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "proposed_relation_type")
    private TelegramChannelPostRelationType
            proposedRelationType;

    /**
     * Предложенное моделью объяснение.
     */
    @Column(
            name = "proposed_reason",
            columnDefinition = "TEXT"
    )
    private String proposedReason;

    /**
     * Название AI-провайдера,
     * выполнившего классификацию.
     */
    @Column(name = "classification_provider")
    private String classificationProvider;

    /**
     * Название конкретной AI-модели.
     */
    @Column(name = "classification_model")
    private String classificationModel;

    /**
     * Версия нашей схемы классификации и prompt.
     *
     * Позволяет позднее повторно обработать связи,
     * созданные старой версией классификатора.
     */
    @Column(name = "classifier_version")
    private String classifierVersion;

    /**
     * Необработанный ответ классификатора.
     *
     * Хранится для аудита, диагностики
     * и последующей повторной обработки.
     */
    @Column(
            name = "classification_raw_response",
            columnDefinition = "TEXT"
    )
    private String classificationRawResponse;

    /**
     * Техническая ошибка классификации.
     */
    @Column(
            name = "classification_error",
            columnDefinition = "TEXT"
    )
    private String classificationError;

    /**
     * Время последней завершённой
     * попытки классификации.
     */
    @Column(name = "classified_at")
    private OffsetDateTime classifiedAt;

    /**
     * Количество начатых попыток классификации.
     */
    @Column(
            name = "classification_attempt_count",
            nullable = false
    )
    private int classificationAttemptCount;

    /**
     * Не обрабатывать связь повторно раньше
     * указанного момента.
     */
    @Column(name = "classification_next_attempt_at")
    private OffsetDateTime classificationNextAttemptAt;

    /**
     * Время захвата связи обработчиком.
     */
    @Column(name = "processing_started_at")
    private OffsetDateTime processingStartedAt;

    /**
     * Хеш текстов source и target,
     * использованных при классификации.
     */
    @Column(name = "classification_input_hash")
    private String classificationInputHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Защищает запись от незаметной
     * конкурентной перезаписи.
     */
    @Version
    @Column(
            name = "entity_version",
            nullable = false
    )
    private Long entityVersion = 0L;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (relationOrigin == null) {
            relationOrigin = TelegramChannelPostRelationOrigin.MANUAL;
        }

        if (relationType == null) {
            relationType = TelegramChannelPostRelationType.UNCLASSIFIED;
        }

        if (classificationStatus == null) {
            classificationStatus = TelegramChannelPostRelationClassificationStatus.NOT_REQUIRED;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}