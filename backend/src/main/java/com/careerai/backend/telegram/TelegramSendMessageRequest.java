package com.careerai.backend.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import java.util.Map;

/**
 * DTO для запроса к Telegram API методом sendMessage.
 *
 * Поддерживает необязательный parse mode, чтобы бот мог отправлять HTML-разметку,
 * а при необходимости — обычный текст без форматирования.
 */

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelegramSendMessageRequest {

    @JsonProperty("chat_id")
    private final long chatId;

    private final String text;

    @JsonProperty("parse_mode")
    private final String parseMode;

    @JsonProperty("reply_markup")
    private final Map<String, Object> replyMarkup;

    public TelegramSendMessageRequest(long chatId, String text, String parseMode) {
        this(chatId, text, parseMode, null);
    }

    public TelegramSendMessageRequest(long chatId, String text, String parseMode, Map<String, Object> replyMarkup) {
        this.chatId = chatId;
        this.text = text;
        this.parseMode = parseMode;
        this.replyMarkup = replyMarkup;
    }
}
