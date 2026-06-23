package com.careerai.backend.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * DTO для запроса к Telegram API методом sendChatAction.
 *
 * CareerAI использует этот объект, чтобы показать пользователю статус "печатает...",
 * пока backend ожидает ответ от AI-модели.
 */

@Getter
public class TelegramChatActionRequest {

    @JsonProperty("chat_id")
    private final long chatId;

    private final String action;

    public TelegramChatActionRequest(long chatId, String action) {
        this.chatId = chatId;
        this.action = action;
    }
}
