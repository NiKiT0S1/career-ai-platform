package com.careerai.backend.telegram;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.message.ChatMessageService;
import com.careerai.backend.runtime.BotRuntimeStateService;
import com.careerai.backend.user.TelegramUser;
import com.careerai.backend.user.TelegramUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Главный сервис для получения сообщений из Telegram через polling.
 *
 * Он забирает новые updates из Telegram, восстанавливает и сохраняет offset,
 * обрабатывает команды /start, /help и /about, сохраняет историю переписки в БД,
 * показывает статус "печатает..." и отправляет обычные вопросы пользователя в AI-провайдер.
 */

@Service
public class TelegramPollingService {

    private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

    private static final String TELEGRAM_UPDATE_OFFSET_KEY = "telegram_update_offset";

    private final TelegramBotService telegramBotService;
    private final ObjectMapper objectMapper;
    private final LlmProvider llmProvider;
    private final TelegramTypingService telegramTypingService;
    private final TelegramUserService telegramUserService;
    private final ChatMessageService chatMessageService;
    private final BotRuntimeStateService botRuntimeStateService;

    private long offset = 0;
    private boolean offsetInitialized = false;

    public TelegramPollingService(TelegramBotService telegramBotService,
                                  ObjectMapper objectMapper,
                                  LlmProvider llmProvider,
                                  TelegramTypingService  telegramTypingService,
                                  TelegramUserService telegramUserService,
                                  ChatMessageService chatMessageService,
                                  BotRuntimeStateService botRuntimeStateService) {
        this.telegramBotService = telegramBotService;
        this.objectMapper = objectMapper;
        this.llmProvider = llmProvider;
        this.telegramTypingService = telegramTypingService;
        this.telegramUserService = telegramUserService;
        this.chatMessageService = chatMessageService;
        this.botRuntimeStateService = botRuntimeStateService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeOffset() {
        offset = botRuntimeStateService.getLongValue(TELEGRAM_UPDATE_OFFSET_KEY)
                .orElse(0L);

        offsetInitialized = true;

        log.info("Telegram polling offset initialized with value={}", offset);
    }

    @Scheduled(fixedDelay = 1000)
    public void pollUpdates() {
        if (!offsetInitialized) {
            return;
        }

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
                long updateStartedAt = System.nanoTime();

                try {
                    if (update.has("message")) {
                        processMessage(update.get("message"));
                    }
                }
                catch (Exception e) {
                    log.error("Error while processing Telegram updateId={}", updateId, e);
                }
                finally {
                    log.info(
                            "Telegram updateId={} finished in {} ms",
                            updateId,
                            elapsedMillis(updateStartedAt)
                    );

                    offset = updateId + 1;
                    botRuntimeStateService.setValue(TELEGRAM_UPDATE_OFFSET_KEY, String.valueOf(offset));
                }
            }
        }
        catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("Temporary network error while polling Telegram updates: {}", e.getClass().getSimpleName());
        }
        catch (Exception e) {
//            log.error("Error while polling Telegram updates", e);
            log.error("Error while polling Telegram updates: {}", e.getClass().getSimpleName());
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

        Long telegramMessageId = message.has("message_id")
                ? message.get("message_id").asLong()
                : null;

        String text = message.has("text") ? message.get("text").asText() : "";
        String normalizedText = text.trim();

        TelegramUser telegramUser = telegramUserService.findOrCreateFromMessage(message);

        if (normalizedText.isBlank()) {
            String response = "Пока я умею обрабатывать только текстовые сообщения.";
            sendAndSavePlainMessage(telegramUser, chatId, response);
            return;
        }

        chatMessageService.saveUserMessage(telegramUser, chatId, normalizedText, telegramMessageId);

        log.info("Received Telegram message from chatId={}: {}", chatId, normalizedText);

        if (isStartCommand(normalizedText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, TelegramMessageTemplates.startMessage());
            return;
        }

        if (isHelpCommand(normalizedText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, TelegramMessageTemplates.helpMessage());
            return;
        }

        if (isAboutCommand(normalizedText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, TelegramMessageTemplates.aboutMessage());
            return;
        }

        TypingActionHandle typingActionHandle = telegramTypingService.startTyping(chatId);

        try {
            String response = llmProvider.generateAnswer(normalizedText);
            sendAndSaveHtmlMessage(telegramUser, chatId, response);
        }
        finally {
            typingActionHandle.stop();
        }
    }

    private void sendAndSaveHtmlMessage(TelegramUser telegramUser, long chatId, String text) {
        telegramBotService.sendHtmlMessage(chatId, text);
        chatMessageService.saveAssistantMessage(telegramUser, chatId, text);
    }

    private void sendAndSavePlainMessage(TelegramUser telegramUser, long chatId, String text) {
        telegramBotService.sendMessage(chatId, text);
        chatMessageService.saveAssistantMessage(telegramUser, chatId, text);
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

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
