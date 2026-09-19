package com.careerai.backend.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

/**
 * Низкоуровневый сервис для обращения к Telegram Bot API.
 *
 * Отвечает за получение updates, отправку обычных сообщений, отправку HTML-сообщений,
 * fallback на plain text при ошибках форматирования и показ typing-индикатора.
 */

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

    public void sendTypingAction(long chatId) {
        String url = "https://api.telegram.org/bot%s/sendChatAction"
                .formatted(properties.getToken());

        TelegramChatActionRequest request = new TelegramChatActionRequest(chatId, "typing");

        try {
            restClient.post()
                    .uri(url)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        }
        catch (Exception e) {
            log.warn("Failed to sent typing action to Telegram chatId={}", chatId);
        }
    }

    public void sendMessage(long chatId, String text) {
        sendPlainMessage(chatId, text);
    }

    public void sendWebAppMessage(long chatId, String text, String webAppUrl) {
        if (!TelegramAdminLaunchService.isSecureWebAppUrl(webAppUrl)) {
            throw new IllegalArgumentException("Telegram Web App requires an HTTPS URL");
        }
        String url = "https://api.telegram.org/bot%s/sendMessage".formatted(properties.getToken());
        Map<String, Object> request = Map.of(
                "chat_id", chatId,
                "text", text,
                "reply_markup", Map.of("inline_keyboard", List.of(List.of(
                        Map.of("text", "Открыть панель", "web_app", Map.of("url", webAppUrl))))));
        restClient.post().uri(url).body(request).retrieve().toBodilessEntity();
    }

    public void sendHtmlMessage(long chatId, String htmlText) {
        if (htmlText == null || htmlText.isBlank()) return;
        if (htmlText.length() > TelegramMessageChunks.LIMIT) {
            sendPlainMessage(chatId, TelegramMessageChunks.plain(htmlText));
            return;
        }
        try {
            sendMessageInternal(chatId, htmlText, "HTML");
        }
        catch (HttpClientErrorException.BadRequest e) {
            log.warn("Telegram rejected HTML message; sending plain text fallback, chatId={}", chatId);

            String plainText = TelegramMessageChunks.plain(htmlText);
            sendPlainMessage(chatId, plainText);
        }
    }

    public void sendPlainMessage(long chatId, String text) {
        for (String chunk : TelegramMessageChunks.split(text)) sendMessageInternal(chatId, chunk, null);
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

}
