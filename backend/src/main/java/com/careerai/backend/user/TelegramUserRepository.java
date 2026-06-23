package com.careerai.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий для чтения и сохранения пользователей Telegram.
 *
 * Используется, чтобы найти пользователя по Telegram ID или создать новую запись,
 * если студент впервые написал боту.
 */

public interface TelegramUserRepository extends JpaRepository<TelegramUser, Long> {

    Optional<TelegramUser> findByTelegramUserId(Long telegramUserId);
}
