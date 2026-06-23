package com.careerai.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelegramUserRepository extends JpaRepository<TelegramUser, Long> {

    Optional<TelegramUser> findByTelegramUserId(Long telegramUserId);
}
