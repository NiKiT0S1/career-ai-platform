package com.careerai.backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * Сервис для синхронизации пользователя Telegram с базой данных.
 *
 * Когда приходит сообщение из Telegram, сервис ищет существующего пользователя
 * или создаёт нового. Также он обновляет поля профиля, потому что username,
 * имя или язык пользователя могут измениться в Telegram.
 */

@Service
public class TelegramUserService {

    private final TelegramUserRepository telegramUserRepository;

    public TelegramUserService(TelegramUserRepository telegramUserRepository) {
        this.telegramUserRepository = telegramUserRepository;
    }

    @Transactional
    public TelegramUser findOrCreateFromMessage(JsonNode message) {
        if (!message.has("from")) {
            return null;
        }

        JsonNode from = message.get("from");

        if (!from.has("id")) {
            return null;
        }

        Long telegramUserId = from.get("id").asLong();

        return telegramUserRepository.findByTelegramUserId(telegramUserId)
                .map(existingUser -> updateExistingUser(existingUser, from))
                .orElseGet(() -> createNewUser(from, telegramUserId));
    }

    private TelegramUser updateExistingUser(TelegramUser user, JsonNode from) {
        user.setUsername(getNullableText(from, "username"));
        user.setFirstName(getNullableText(from, "first_name"));
        user.setLastName(getNullableText(from, "last_name"));
        user.setLanguageCode(getNullableText(from, "language_code"));
        user.setBot(from.path("is_bot").asBoolean(false));
        user.setUpdatedAt(LocalDateTime.now());

        return telegramUserRepository.save(user);
    }

    private TelegramUser createNewUser(JsonNode from, Long telegramUserId) {
        LocalDateTime now = LocalDateTime.now();

        TelegramUser user = new TelegramUser();
        user.setTelegramUserId(telegramUserId);
        user.setUsername(getNullableText(from, "username"));
        user.setFirstName(getNullableText(from, "first_name"));
        user.setLastName(getNullableText(from, "last_name"));
        user.setLanguageCode(getNullableText(from, "language_code"));
        user.setBot(from.path("is_bot").asBoolean(false));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        return telegramUserRepository.save(user);
    }

    private String getNullableText(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }

        return node.get(fieldName).asText();
    }
}
