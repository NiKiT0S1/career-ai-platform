package com.careerai.backend.telegram;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TelegramSendMessageRequest {

    @JsonProperty("chat_id")
    private final long chatId;

    private final String text;

    @JsonProperty("parse_mode")
    private final String parseMode;

    public TelegramSendMessageRequest(long chatId, String text, String parseMode) {
        this.chatId = chatId;
        this.text = text;
        this.parseMode = parseMode;
    }
}
