package com.careerai.backend.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

@Getter
public class TelegramSendMessageRequest {

    @JsonProperty("chat_id")
    private final long chatId;
    private final String text;

    public TelegramSendMessageRequest(long chatId, String text) {
        this.chatId = chatId;
        this.text = text;
    }
}
