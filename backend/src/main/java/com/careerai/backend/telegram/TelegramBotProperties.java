package com.careerai.backend.telegram;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Хранит настройки Telegram-бота.
 *
 * Токен бота загружается из переменных окружения через application.properties,
 * чтобы секретные данные не попадали в исходный код и Git.
 */

@Component
@Getter
public class TelegramBotProperties {

    @Value("${telegram.bot.token}")
    private String token;
}
