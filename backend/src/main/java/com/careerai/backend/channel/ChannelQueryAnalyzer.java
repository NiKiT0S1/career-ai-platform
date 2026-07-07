package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Анализирует вопрос пользователя через LLM и возвращает структурированный JSON.
 *
 * Этот сервис не отвечает пользователю напрямую. Его задача — понять:
 * о чём вопрос, нужна ли база постов Telegram-канала, FAQ или дедлайны.
 */

@Service
public class ChannelQueryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ChannelQueryAnalyzer.class);

    private final LlmProvider llmProvider;
    private final ObjectMapper objectMapper;

    public ChannelQueryAnalyzer (LlmProvider llmProvider, ObjectMapper objectMapper) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    public ChannelQueryAnalysis analyze(String userMessage) {
        if (userMessage == null ||  userMessage.isBlank()) {
            return ChannelQueryAnalysis.unknown();
        }

        String prompt = buildAnalysisPrompt(userMessage);

        LlmResponse response = llmProvider.generateAnswer(prompt);

        if (response.failed()) {
            log.warn(
                    "Channel query analysis failed. provider={}, model={}, errorType={}",
                    response.provider(),
                    response.model(),
                    response.errorType()
            );

            return ChannelQueryAnalysis.unknown();
        }

//        return parseAnalysis(response.text());

        ChannelQueryAnalysis analysis = parseAnalysis(response.text());

        log.info(
                "Channel query analysis completed. intent={}, topic={}, needsChannelPosts={}, needsFaq={}, needsDeadlines={}",
                analysis.intent(),
                analysis.topic(),
                analysis.needsChannelPosts(),
                analysis.needsFaq(),
                analysis.needsDeadlines()
        );

        return analysis;
    }

    private String buildAnalysisPrompt(String userMessage) {
        return """
                Ты внутренний анализатор запросов для Telegram-бота Центра карьеры и Трудоустройства AITU.

                Твоя задача — НЕ отвечать пользователю, а классифицировать его сообщение.

                Верни только JSON без Markdown, без ```json, без пояснений и без HTML.

                Возможные intent:
                - GENERAL_CHAT — обычное общение, приветствие, вопрос не про ЦКиТ
                - VACANCY — вакансии, работа, позиции, стажировки, трудоустройство
                - PRACTICE — производственная практика, документы, договор, паспорт, оформление практики
                - DEADLINE — дедлайны, сроки, даты сдачи, временные рамки
                - GENERAL_UPDATES — свежие объявления, новости канала, "что нового"
                - UNKNOWN — если непонятно
                - FAQ — типовой вопрос, который лучше искать в базе FAQ

                Поле topic:
                - коротко укажи тему запроса;
                - например: "Java", "practice_documents", "practice_dates", "vacancy_deadlines";
                - если темы нет, используй null.

                Поле needsChannelPosts:
                - true, если для ответа нужны свежие посты Telegram-канала;
                - false, если можно отвечать без постов канала.

                Поле needsFaq:
                - true, если вопрос похож на типовой FAQ;
                - false, если FAQ не нужен.

                Поле needsDeadlines:
                - true, если пользователь спрашивает про сроки, дедлайны или даты;
                - false, если нет.

                Формат ответа строго такой:
                {
                  "intent": "VACANCY",
                  "topic": "Java",
                  "needsChannelPosts": true,
                  "needsFaq": false,
                  "needsDeadlines": false
                }

                Сообщение пользователя:
                "%s"
                """.formatted(userMessage);
    }

    private ChannelQueryAnalysis parseAnalysis(String llmText) {
        try {
            String json = extractJson(llmText);
            JsonNode root = objectMapper.readTree(json);

            ChannelSearchIntent intent = parseIntent(readText(root, "intent"));
            String topic = readNullableText(root, "topic");

            boolean needsChannelPosts = readBoolean(root, "needsChannelPosts", false);
            boolean needsFaq = readBoolean(root, "needsFaq", false);
            boolean needsDeadlines = readBoolean(root, "needsDeadlines", false);

            return new ChannelQueryAnalysis(
                    intent,
                    topic,
                    needsChannelPosts,
                    needsFaq,
                    needsDeadlines
            );
        }
        catch (Exception e) {
            log.warn("Failed to parse channel query analysis. Raw LLM text: {}", llmText, e);
            return ChannelQueryAnalysis.unknown();
        }
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("LLM analysis response is empty");
        }

        int startIndex = text.indexOf("{");
        int endIndex = text.indexOf("}");

        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("LLM analysis response does not contain JSON object");
        }

        return text.substring(startIndex, endIndex + 1);
    }

    private ChannelSearchIntent parseIntent(String value) {
        if (value == null || value.isBlank()) {
            return ChannelSearchIntent.UNKNOWN;
        }

        try {
            return ChannelSearchIntent.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return ChannelSearchIntent.UNKNOWN;
        }
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);

        if (value == null || value.isNull()) {
            return null;
        }

        return value.asText();
    }

    private String readNullableText(JsonNode root, String fieldName) {
        String value = readText(root, fieldName);

        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }

        return value.trim();
    }

    private boolean readBoolean(JsonNode root, String fieldName, boolean defaultValue) {
        JsonNode value = root.get(fieldName);

        if (value == null || value.isNull()) {
            return defaultValue;
        }

        return value.asBoolean(defaultValue);
    }
}
