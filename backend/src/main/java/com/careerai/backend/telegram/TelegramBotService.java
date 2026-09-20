package com.careerai.backend.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;
import java.time.Duration;

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

    @Autowired
    public TelegramBotService(TelegramBotProperties properties) {
        this(properties, createRestClient());
    }

    TelegramBotService(TelegramBotProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient createRestClient() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
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

    public void sendMessage(long chatId, String text, Map<String, Object> replyMarkup) {
        sendPlainMessage(chatId, text, replyMarkup);
    }

    public void setCommandMenu(Long chatId) {
        setMenuButton(chatId, Map.of("type", "commands"));
    }

    public void setAdminMenu(long chatId, String webAppUrl) {
        if (!TelegramAdminLaunchService.isSecureWebAppUrl(webAppUrl)) {
            throw new IllegalArgumentException("Telegram Web App requires an HTTPS URL");
        }
        setMenuButton(chatId, Map.of("type", "web_app", "text", "Админ-панель",
                "web_app", Map.of("url", webAppUrl)));
    }

    public void setDefaultCommands() {
        callBooleanMethod("setMyCommands", Map.of("scope", Map.of("type", "default"),
                "commands", List.of(
                        Map.of("command", "start", "description", "Начать работу"),
                        Map.of("command", "help", "description", "Помощь и команды"),
                        Map.of("command", "about", "description", "О CareerAI"),
                        Map.of("command", "faq", "description", "Частые вопросы ЦКиТ"),
                        Map.of("command", "myid", "description", "Мой Telegram ID"))));
    }

    private void setMenuButton(Long chatId, Map<String, Object> menuButton) {
        Map<String, Object> request = chatId == null
                ? Map.of("menu_button", menuButton)
                : Map.of("chat_id", chatId, "menu_button", menuButton);
        callBooleanMethod("setChatMenuButton", request);
    }

    private void callBooleanMethod(String method, Map<String, Object> request) {
        Map<?, ?> response = restClient.post()
                .uri("https://api.telegram.org/bot%s/%s".formatted(properties.getToken(), method))
                .body(request).retrieve().body(Map.class);
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))
                || !Boolean.TRUE.equals(response.get("result"))) {
            // Do not include API response or URL: either can contain operational details or a token.
            throw new IllegalStateException("Telegram did not confirm menu configuration");
        }
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
        sendHtmlMessage(chatId, htmlText, null);
    }

    public void sendHtmlMessage(long chatId, String htmlText, Map<String, Object> replyMarkup) {
        if (htmlText == null || htmlText.isBlank()) return;
        if (htmlText.length() > TelegramMessageChunks.LIMIT) {
            sendPlainMessage(chatId, TelegramMessageChunks.plain(htmlText), replyMarkup);
            return;
        }
        try {
            sendMessageInternal(chatId, htmlText, "HTML", replyMarkup);
        }
        catch (HttpClientErrorException.BadRequest e) {
            log.warn("Telegram rejected HTML message; sending plain text fallback, chatId={}", chatId);

            String plainText = TelegramMessageChunks.plain(htmlText);
            sendPlainMessage(chatId, plainText, replyMarkup);
        }
    }

    public void sendPlainMessage(long chatId, String text) {
        sendPlainMessage(chatId, text, null);
    }

    public void sendPlainMessage(long chatId, String text, Map<String, Object> replyMarkup) {
        var chunks = TelegramMessageChunks.split(text);
        for (int i = 0; i < chunks.size(); i++) {
            sendMessageInternal(chatId, chunks.get(i), null, i == chunks.size() - 1 ? replyMarkup : null);
        }
    }

    private void sendMessageInternal(long chatId, String text, String parseMode, Map<String, Object> replyMarkup) {
        String url = "https://api.telegram.org/bot%s/sendMessage"
                .formatted(properties.getToken());

        TelegramSendMessageRequest request = new TelegramSendMessageRequest(chatId, text, parseMode, replyMarkup);

        restClient.post()
                .uri(url)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

}
