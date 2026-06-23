package com.careerai.backend.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BotRuntimeStateRepository extends JpaRepository<BotRuntimeState, Long> {

    Optional<BotRuntimeState> findByStateKey(String stateKey);
}
