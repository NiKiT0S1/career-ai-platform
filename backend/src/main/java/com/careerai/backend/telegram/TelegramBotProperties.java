package com.careerai.backend.telegram;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class TelegramBotProperties {

    @Value("${telegram.bot.token}")
    private String token;
}
