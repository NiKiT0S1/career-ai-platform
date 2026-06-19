package com.careerai.backend.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class TelegramBotService {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);
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
        sendPlainMessage(chatId, text);
    }

    public void sendHtmlMessage(long chatId, String htmlText) {
        try {
            sendMessageInternal(chatId, htmlText, "HTML");
        }
        catch (HttpClientErrorException.BadRequest e) {
            log.warn("Telegram rejected HTML message. Sending plain text fallback. Reason: {}", e.getMessage());

            String plainText = stripHtmlTags(htmlText);
            sendPlainMessage(chatId, plainText);
        }
    }

    public void sendPlainMessage(long chatId, String text) {
        sendMessageInternal(chatId, text, null);
    }

    private void sendMessageInternal(long chatId, String text, String parseMode) {
        String url = "https://api.telegram.org/bot%s/sendMessage"
                .formatted(properties.getToken());

        TelegramSendMessageRequest request = new TelegramSendMessageRequest(chatId, text, parseMode);

        restClient.post()
                .uri(url)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private String stripHtmlTags(String html) {
        return html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)<p>", "")
                .replaceAll("<[^>]*>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
