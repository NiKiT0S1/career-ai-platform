package com.careerai.backend.runtime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity-класс для небольших технических значений, которые нужны работающему боту.
 *
 * Например, здесь хранится Telegram polling offset в переменной {@code stateKey}, чтобы после перезапуска backend
 * продолжал читать updates с правильного места и не обрабатывал старые сообщения повторно.
 */

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bot_runtime_state")
public class BotRuntimeState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_key", nullable = false, unique = true)
    private String stateKey;

    @Column(name = "state_value", nullable = false)
    private String stateValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
