package com.careerai.backend.runtime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class BotRuntimeStateService {

    private final BotRuntimeStateRepository botRuntimeStateRepository;

    public BotRuntimeStateService(BotRuntimeStateRepository botRuntimeStateRepository) {
        this.botRuntimeStateRepository = botRuntimeStateRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> getValue(String key) {
        return botRuntimeStateRepository.findByStateKey(key)
                .map(BotRuntimeState::getStateValue);
    }

    @Transactional(readOnly = true)
    public Optional<Long> getLongValue(String key) {
        return getValue(key).map(Long::parseLong);
    }

    @Transactional
    public void setValue(String key, String value) {
        BotRuntimeState state = botRuntimeStateRepository.findByStateKey(key)
                .orElseGet(() -> createState(key));

        state.setStateValue(value);
        state.setUpdatedAt(LocalDateTime.now());

        botRuntimeStateRepository.save(state);
    }

    private BotRuntimeState createState(String key) {
        BotRuntimeState state = new BotRuntimeState();
        state.setStateKey(key);
        state.setUpdatedAt(LocalDateTime.now());
        return state;
    }
}
