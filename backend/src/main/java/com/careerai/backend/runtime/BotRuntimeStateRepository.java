package com.careerai.backend.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий для чтения и сохранения технического состояния бота.
 *
 * Состояние хранится в формате ключ-значение. Это удобно для offset Telegram,
 * будущих импортов, фоновых задач и других небольших runtime-настроек.
 */

public interface BotRuntimeStateRepository extends JpaRepository<BotRuntimeState, Long> {

    Optional<BotRuntimeState> findByStateKey(String stateKey);
}
