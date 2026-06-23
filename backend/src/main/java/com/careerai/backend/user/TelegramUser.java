package com.careerai.backend.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity-класс пользователя Telegram, который взаимодействовал с CareerAI.
 *
 * Здесь хранятся базовые данные профиля Telegram: ID пользователя, username,
 * имя, фамилия и язык. Это позволяет связывать сообщения и будущие функции
 * персонализации с конкретным студентом.
 */

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "telegram_users")
public class TelegramUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "telegram_user_id", nullable = false, unique = true)
    private Long telegramUserId;

    @Column(name = "username")
    private String username;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "language_code")
    private String languageCode;

    @Column(name = "is_bot", nullable = false)
    private boolean bot;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
