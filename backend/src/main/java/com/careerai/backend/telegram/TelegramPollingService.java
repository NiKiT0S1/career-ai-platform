package com.careerai.backend.telegram;

import com.careerai.backend.ai.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private final TelegramBotService telegramBotService;
    private final ObjectMapper objectMapper;
    private final LlmProvider llmProvider;
    private final TelegramTypingService telegramTypingService;

    private long offset = 0;

    public TelegramPollingService(TelegramBotService telegramBotService,
                                  ObjectMapper objectMapper,
                                  LlmProvider llmProvider,
                                  TelegramTypingService  telegramTypingService) {
        this.telegramBotService = telegramBotService;
        this.objectMapper = objectMapper;
        this.llmProvider = llmProvider;
        this.telegramTypingService = telegramTypingService;
    }

    @Scheduled(fixedDelay = 1000)
    public void pollUpdates() {
        try {
            String updatesJson = telegramBotService.getUpdates(offset);
            // System.out.println(updatesJson);

            JsonNode root = objectMapper.readTree(updatesJson);
            JsonNode results = root.get("result");

            if (results == null || !results.isArray() || results.isEmpty()) {
                return;
            }

            for (JsonNode update : results) {
                long updateId = update.get("update_id").asLong();

                try {
                    if (update.has("message")) {
                        processMessage(update.get("message"));
                    }
                }
                catch (Exception e) {
                    log.error("Error while processing Telegram updateId={}", updateId, e);
                }
                finally {
                    offset = updateId + 1;
                }
            }
        }
        catch (Exception e) {
            log.error("Error while polling Telegram updates", e);
        }
    }

    private void processMessage(JsonNode message) {
        if (isBotMessage(message)) {
            return;
        }

        if (!message.has("chat")) {
            return;
        }

        long chatId = message.get("chat").get("id").asLong();
        String text = message.has("text") ? message.get("text").asText() : "";
        String normalizedText = text.trim();

        if (normalizedText.isBlank()) {
            telegramBotService.sendMessage(chatId, "Пока я умею обрабатывать только текстовые сообщения.");
            return;
        }

        log.info("Received Telegram message from chatId={}: {}", chatId, normalizedText);

        if (isStartCommand(normalizedText)) {
            telegramBotService.sendHtmlMessage(chatId, TelegramMessageTemplates.startMessage());
            return;
        }

        if (isHelpCommand(normalizedText)) {
            telegramBotService.sendHtmlMessage(chatId, TelegramMessageTemplates.helpMessage());
            return;
        }

        if (isAboutCommand(normalizedText)) {
            telegramBotService.sendHtmlMessage(chatId, TelegramMessageTemplates.aboutMessage());
            return;
        }

        TypingActionHandle typingActionHandle = telegramTypingService.startTyping(chatId);

        try {
            String response = llmProvider.generateAnswer(normalizedText);
            telegramBotService.sendHtmlMessage(chatId, response);
        }
        finally {
            typingActionHandle.stop();
        }
    }

    private boolean isBotMessage(JsonNode message) {
        return message.has("from")
                && message.get("from").path("is_bot").asBoolean(false);
    }

    private boolean isStartCommand(String text) {
        return text.startsWith("/start");
    }

    private boolean isHelpCommand(String text) {
        return text.startsWith("/help");
    }

    private boolean isAboutCommand(String text) {
        return text.startsWith("/about");
    }
}
