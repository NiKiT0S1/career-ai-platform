package com.careerai.backend.telegram;

import com.careerai.backend.ai.LlmProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TelegramPollingService {

    private final TelegramBotService telegramBotService;
    private final ObjectMapper objectMapper;
    private final LlmProvider llmProvider;

    private long offset = 0;

    public TelegramPollingService(TelegramBotService telegramBotService,
                                  ObjectMapper objectMapper,
                                  LlmProvider llmProvider) {
        this.telegramBotService = telegramBotService;
        this.objectMapper = objectMapper;
        this.llmProvider = llmProvider;
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

                if (update.has("message")) {
                    processMessage(update.get("message"));
                }

                offset = updateId + 1;
            }
        }
        catch (Exception e) {
            System.out.println("Error while polling Telegram updates:");
            e.printStackTrace();
        }
    }

    private void processMessage(JsonNode message) {
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

        if (normalizedText.startsWith("/start")) {
            telegramBotService.sendMessage(chatId, getStartMessage());
            return;
        }

        String response = llmProvider.generateAnswer(normalizedText);

        telegramBotService.sendMessage(chatId, response);
    }

    private String getStartMessage() {
        return """
                Привет! Я CareerAI — AI-ассистент Центра карьеры и трудоустройства AITU.
                
                Я помогу с вопросами по:
                • производственной практике;
                • стажировкам;
                • вакансиям;
                • дуальному обучению;
                • дедлайнам;
                • CV и карьерным рекомендациям.
                
                Пока я работаю в тестовом режиме. Напиши мне любой вопрос.
                """;
    }
}
