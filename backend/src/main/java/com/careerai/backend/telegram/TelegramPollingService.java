package com.careerai.backend.telegram;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import com.careerai.backend.channel.TelegramChannelPostAnswerService;
import com.careerai.backend.channel.TelegramChannelPostService;
import com.careerai.backend.faq.FaqEntryService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Главный сервис для получения сообщений из Telegram через polling.
 *
 * Он забирает новые updates из Telegram, восстанавливает и сохраняет offset,
 * обрабатывает команды /start, /help, /about и /faq, сохраняет историю переписки в БД,
 * показывает статус "печатает..." и отправляет обычные вопросы пользователя в AI-провайдер.
 */

@Service
@ConditionalOnProperty(name = "telegram.bot.polling-enabled", havingValue = "true", matchIfMissing = true)
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
    private final TelegramHtmlSanitizer telegramHtmlSanitizer;
    private final TelegramChannelPostService telegramChannelPostService;
    private final TelegramChannelPostAnswerService telegramChannelPostAnswerService;
    private final FaqEntryService faqEntryService;
    private final TelegramAdminLaunchService adminLaunchService;
    private final TelegramAdminMenuService adminMenuService;

    private long offset = 0;
    private boolean offsetInitialized = false;

    public TelegramPollingService(TelegramBotService telegramBotService,
                                  ObjectMapper objectMapper,
                                  LlmProvider llmProvider,
                                  TelegramTypingService  telegramTypingService,
                                  TelegramUserService telegramUserService,
                                  ChatMessageService chatMessageService,
                                  BotRuntimeStateService botRuntimeStateService,
                                  TelegramHtmlSanitizer telegramHtmlSanitizer,
                                  TelegramChannelPostService telegramChannelPostService,
                                  TelegramChannelPostAnswerService telegramChannelPostAnswerService,
                                  FaqEntryService faqEntryService,
                                  TelegramAdminLaunchService adminLaunchService,
                                  TelegramAdminMenuService adminMenuService) {
        this.telegramBotService = telegramBotService;
        this.objectMapper = objectMapper;
        this.llmProvider = llmProvider;
        this.telegramTypingService = telegramTypingService;
        this.telegramUserService = telegramUserService;
        this.chatMessageService = chatMessageService;
        this.botRuntimeStateService = botRuntimeStateService;
        this.telegramHtmlSanitizer = telegramHtmlSanitizer;
        this.telegramChannelPostService = telegramChannelPostService;
        this.telegramChannelPostAnswerService = telegramChannelPostAnswerService;
        this.faqEntryService = faqEntryService;
        this.adminLaunchService = adminLaunchService;
        this.adminMenuService = adminMenuService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeOffset() {
        offset = botRuntimeStateService.getLongValue(TELEGRAM_UPDATE_OFFSET_KEY)
                .orElse(0L);

        offsetInitialized = true;

        log.info("Telegram polling offset initialized with value={}", offset);
        adminMenuService.initialize();
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

                    if (update.has("channel_post")) {
                        telegramChannelPostService.saveOrUpdateChannelPost(
                                update.get("channel_post"),
                                update.toString(),
                                false
                        );
                    }

                    if (update.has("edited_channel_post")) {
                        telegramChannelPostService.saveOrUpdateChannelPost(
                                update.get("edited_channel_post"),
                                update.toString(),
                                true
                        );
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
        String chatType = message.path("chat").path("type").asText();
        JsonNode sender = message.path("from").path("id");
        long senderId = sender.isIntegralNumber() && sender.canConvertToLong() ? sender.asLong() : 0;
        adminMenuService.onInteraction(chatType, chatId, senderId);
        Map<String, Object> replyMarkup = adminMenuService.replyMarkup(chatType, chatId, senderId);
        String commandText = "private".equals(chatType)
                ? TelegramAdminMenuService.commandForButton(normalizedText) : normalizedText;

        TelegramUser telegramUser = telegramUserService.findOrCreateFromMessage(message);

        if (normalizedText.isBlank()) {
            String response = "Пока я умею обрабатывать только текстовые сообщения.";
            sendAndSavePlainMessage(telegramUser, chatId, response, replyMarkup);
            return;
        }

        chatMessageService.saveUserMessage(telegramUser, chatId, normalizedText, telegramMessageId);

        log.info("Received Telegram message from chatId={}: {}", chatId, normalizedText);

        if (isStartCommand(commandText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, TelegramMessageTemplates.startMessage(), replyMarkup);
            return;
        }

        if (isHelpCommand(commandText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, TelegramMessageTemplates.helpMessage(), replyMarkup);
            return;
        }

        if (isAboutCommand(commandText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, TelegramMessageTemplates.aboutMessage(), replyMarkup);
            return;
        }

        if (isFaqCommand(commandText)) {
            sendAndSaveHtmlMessage(telegramUser, chatId, faqEntryService.buildFaqListMessage(), replyMarkup);
            return;
        }

        if (isCommand(commandText, "myid")) {
            if (!"private".equals(message.path("chat").path("type").asText())) {
                sendAndSavePlainMessage(telegramUser, chatId, "Напиши мне /myid в личном чате.", replyMarkup);
                return;
            }
            JsonNode userId = message.path("from").path("id");
            String response = userId.isIntegralNumber()
                    ? "Твой Telegram ID: " + userId.asLong()
                    : "Не удалось определить ID отправителя этого сообщения.";
            sendAndSavePlainMessage(telegramUser, chatId, response, replyMarkup);
            return;
        }

        if (isCommand(commandText, "admin")) {
            var launch = adminLaunchService.prepare(message.path("chat").path("type").asText(), senderId);
            if (launch.allowed()) {
                telegramBotService.sendWebAppMessage(chatId, launch.message(), launch.webAppUrl());
                chatMessageService.saveAssistantMessage(telegramUser, chatId, launch.message());
            } else {
                sendAndSavePlainMessage(telegramUser, chatId, launch.message(), replyMarkup);
            }
            return;
        }

        TypingActionHandle typingActionHandle = telegramTypingService.startTyping(chatId);

        try {
            var channelPostAnswer = telegramChannelPostAnswerService.buildAnswerIfRelevant(normalizedText);

            if (channelPostAnswer.isPresent()) {
                sendAndSaveHtmlMessage(telegramUser, chatId, channelPostAnswer.get(), replyMarkup);
                return;
            }

            LlmResponse response = llmProvider.generateAnswer(normalizedText);
            sendAndSaveHtmlMessage(telegramUser, chatId, response.text(), replyMarkup);
        }
        finally {
            typingActionHandle.stop();
        }
    }

    private void sendAndSaveHtmlMessage(TelegramUser telegramUser, long chatId, String text, Map<String, Object> replyMarkup) {
        String sanitizedText = telegramHtmlSanitizer.sanitizeHtml(text);

        telegramBotService.sendHtmlMessage(chatId, sanitizedText, replyMarkup);
        chatMessageService.saveAssistantMessage(telegramUser, chatId, sanitizedText);
    }

    private void sendAndSavePlainMessage(TelegramUser telegramUser, long chatId, String text, Map<String, Object> replyMarkup) {
        telegramBotService.sendMessage(chatId, text, replyMarkup);
        chatMessageService.saveAssistantMessage(telegramUser, chatId, text);
    }

    private boolean isBotMessage(JsonNode message) {
        return message.has("from")
                && message.get("from").path("is_bot").asBoolean(false);
    }

    private boolean isStartCommand(String text) {
        return isCommand(text, "start");
    }

    private boolean isHelpCommand(String text) {
        return isCommand(text, "help");
    }

    private boolean isAboutCommand(String text) {
        return isCommand(text, "about");
    }

    private boolean isFaqCommand(String text) {
        return isCommand(text, "faq");
    }

    static boolean isCommand(String text, String command) {
        return text != null && text.matches("(?i)^/" + java.util.regex.Pattern.quote(command)
                + "(?:@[A-Za-z0-9_]+)?(?:\\s.*)?$");
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
