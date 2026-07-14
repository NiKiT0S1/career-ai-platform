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

                Твоя задача — НЕ отвечать пользователю, а определить тему запроса и необходимые источники информации.

                Верни только один JSON-объект:
                - без Markdown;
                - без блока ```json;
                - без HTML;
                - без пояснений до или после JSON.
                
                Возможные intent:
                - GENERAL_CHAT — обычное общение, приветствие, вопрос не про ЦКиТ;
                - VACANCY — вакансии, работа, позиции, стажировки, поиск работы и трудоустройство;
                - PRACTICE — производственная практика, документы, договоры и оформление практики;
                - DEADLINE — дедлайны, сроки, даты сдачи, временные рамки;
                - GENERAL_UPDATES — свежие объявления и новости канала;
                - UNKNOWN — тему не удалось определить;
                - FAQ — типовой вопрос об услугах, правилах, документах и работе Центра Карьеры, который лучше искать в базе FAQ.

                Поле topic:
                - коротко укажи тему запроса;
                - используй понятное значение в snake_case;
                - примеры: "java_vacancies", "graduate_job_support", "practice_documents", "practice_dates", "vacancy_deadlines";
                - если темы нет, используй null.

                Поле needsChannelPosts:
                - true, если для ответа нужны нужны актуальные публикации Telegram-канала;
                - актуальные публикации нужны для текущих вакансий, стажировок, предложений по практике, мероприятий, новых объявлений, изменений и действующих дедлайнов;
                - если пользователь спрашивает, какие вакансии доступны сейчас, где сейчас можно найти работу или какие есть актуальные предложения, используй true;
                - false, если можно отвечать без постов канала и достаточно стабильной информации из FAQ, или это обычное общение.

                Поле needsFaq:
                - true, если это типовой вопрос FAQ, и для ответа нужна стабильная информация об услугах, правилах, документах, процедурах, контактах или помощи ЦКиТ;
                - true для вопросов о том, помогает ли Центр студентам или выпускникам;
                - true для вопросов о поддержке после выпуска, поиске работы, карьерных консультациях, резюме, собеседованиях и контактах Центра;
                - true для вопросов о документах по практике, месте прохождения практики, дуальном обучении и итоговой отчётности;
                - false, если FAQ не нужен и пользователь спрашивает исключительно об актуальном объявлении, конкретной текущей вакансии или просто общается.

                Поле needsDeadlines:
                - true, если вопрос затрагивает сроки, даты, дедлайны, период проведения или дату сдачи;
                - false, если временные рамки для ответа не нужны.
                
                Критически важные правила:
                
                - intent показывает основную тему запроса;
                - needsChannelPosts, needsFaq и needsDeadlines являются независимыми;
                - несколько полей одновременно могут иметь значение true;
                - intent=VACANCY НЕ означает, что needsFaq обязательно false;
                - intent=FAQ НЕ означает, что needsChannelPosts обязательно false;
                - выбирай все источники, которые нужны для полного ответа;
                - не ограничивайся только одним источником, если вопрос составной.

                Пример 1.

                Сообщение:
                "Я недавно выпустился и не знаю, где найти работу.
                Центр может мне помочь и какие сейчас есть вакансии?"
    
                Ответ:
                {
                  "intent": "VACANCY",
                  "topic": "graduate_job_support",
                  "needsChannelPosts": true,
                  "needsFaq": true,
                  "needsDeadlines": false
                }
    
                Пример 2.
    
                Сообщение:
                "Я недавно выпустился, мне нужно найти работу до 1 сентября.
                Где искать работу и может ли Центр помочь?"
    
                Ответ:
                {
                  "intent": "VACANCY",
                  "topic": "graduate_job_search",
                  "needsChannelPosts": true,
                  "needsFaq": true,
                  "needsDeadlines": true
                }
    
                Пример 3.
    
                Сообщение:
                "Центр помогает выпускникам после окончания обучения?"
    
                Ответ:
                {
                  "intent": "FAQ",
                  "topic": "career_support_after_graduation",
                  "needsChannelPosts": false,
                  "needsFaq": true,
                  "needsDeadlines": false
                }
    
                Пример 4.
    
                Сообщение:
                "Какие сейчас есть вакансии для Java-разработчика?"
    
                Ответ:
                {
                  "intent": "VACANCY",
                  "topic": "java_vacancies",
                  "needsChannelPosts": true,
                  "needsFaq": false,
                  "needsDeadlines": false
                }
    
                Пример 5.
    
                Сообщение:
                "Какие документы нужны для практики
                и до какого числа их нужно сдать?"
    
                Ответ:
                {
                  "intent": "PRACTICE",
                  "topic": "practice_documents_and_deadlines",
                  "needsChannelPosts": true,
                  "needsFaq": true,
                  "needsDeadlines": true
                }
    
                Пример 6.
    
                Сообщение:
                "Привет, как дела?"
    
                Ответ:
                {
                  "intent": "GENERAL_CHAT",
                  "topic": null,
                  "needsChannelPosts": false,
                  "needsFaq": false,
                  "needsDeadlines": false
                }
    
                Проанализируй следующее сообщение пользователя:
    
                <USER_MESSAGE>
                %s
                </USER_MESSAGE>
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
