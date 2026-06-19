package com.careerai.backend.telegram;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class TelegramBotService {

    private final TelegramBotProperties properties;
    private final RestClient restClient;

    public TelegramBotService(TelegramBotProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    public String getUpdates(long offset) {
        String url = "https://api.telegram.org/bot%s/getUpdates?offset=%d"
                .formatted(properties.getToken(), offset);

        return restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
    }

    public void sendMessage(long chatId, String text) {
        String url = "https://api.telegram.org/bot%s/sendMessage"
                .formatted(properties.getToken());

        TelegramSendMessageRequest request = new TelegramSendMessageRequest(chatId, text);

        restClient.post()
                .uri(url)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }
}
