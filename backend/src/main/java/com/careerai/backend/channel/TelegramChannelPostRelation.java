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
                        name = "uk_channel_post_relation",
                        columnNames = {
                                "source_post_id",
                                "target_post_id",
                                "relation_type"
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
    private TelegramChannelPostRelationType relationType;

    /**
     * Понятное объяснение связи.
     *
     * Например:
     * "Отменена раздача напитков на мероприятии".
     */
    @Column(
            name = "reason",
            nullable = false,
            columnDefinition = "TEXT"
    )
    private String reason;

    @Column(
            name = "created_at",
            nullable = false
    )
    private OffsetDateTime createdAt;
}