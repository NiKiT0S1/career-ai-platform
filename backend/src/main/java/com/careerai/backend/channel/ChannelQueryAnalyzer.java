package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
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
                "Channel query analysis completed. intent={}, topic={}, contentScopes={}, resultMode={}, needsChannelPosts={}, needsFaq={}, needsDeadlines={}",
                analysis.intent(),
                analysis.topic(),
                analysis.contentScopes(),
                analysis.resultMode(),
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
                
                Поле contentScopes:
                
                - верни JSON-массив категорий;
                - допустимые значения:
                  NONE, ALL_UPDATES, VACANCIES, EVENTS, PRACTICE, DEADLINES;
                - порядок элементов должен соответствовать порядку запроса пользователя;
                - если пользователь просит сначала вакансии, а потом мероприятия,
                  верни ["VACANCIES", "EVENTS"];
                - если нужна одна категория, всё равно верни массив из одного элемента;
                - ALL_UPDATES используй только для общего вопроса обо всех новостях;
                - если needsChannelPosts=false, верни ["NONE"].
                
                Поле resultMode:
                
                - RELEVANT — нужны только наиболее подходящие публикации;
                - ALL_MATCHING — пользователь явно просит все записи или полный список;
                - слова "все", "полный список", "перечисли всё" обычно означают ALL_MATCHING;
                - обычный вопрос "есть ли вакансии Java?" означает RELEVANT.

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
                - intent всегда содержит только одно значение, даже если запрос составной;
                - contentScopes показывает все категории Telegram-публикаций, необходимые для ответа;
                - contentScopes всегда является JSON-массивом;
                - порядок элементов contentScopes должен соответствовать порядку категорий в сообщении пользователя;
                - needsChannelPosts, needsFaq и needsDeadlines являются независимыми;
                - несколько полей needsChannelPosts, needsFaq и needsDeadlines одновременно могут иметь значение true;
                - intent=VACANCY НЕ означает, что needsFaq обязательно false;
                - intent=FAQ НЕ означает, что needsChannelPosts обязательно false;
                - needsDeadlines=true НЕ означает, что contentScopes обязательно должен содержать DEADLINES;
                - например, запрос о вакансиях до определённой даты должен иметь contentScopes=["VACANCIES"] и needsDeadlines=true;
                - выбирай все источники, которые нужны для полного ответа;
                - не ограничивайся только одним источником, если вопрос составной;
                - если пользователь спрашивает только о мероприятиях, используй contentScopes=["EVENTS"];
                - если пользователь спрашивает только о вакансиях, используй contentScopes=["VACANCIES"];
                - если пользователь спрашивает только о практике, используй contentScopes=["PRACTICE"];
                - если пользователь спрашивает только о дедлайнах разных категорий, используй contentScopes=["DEADLINES"];
                - если пользователь спрашивает о конкретной категории, не используй ALL_UPDATES;
                - составной запрос может содержать несколько contentScopes;
                - если пользователь, например, просит сначала вакансии, а затем мероприятия, верни ["VACANCIES", "EVENTS"] именно в таком порядке;
                - не заменяй ["VACANCIES", "EVENTS"] значением ["ALL_UPDATES"];
                - ALL_UPDATES означает общий обзор публикаций разных категорий;
                - ALL_UPDATES должно использоваться отдельно и не должно находиться в одном массиве с VACANCIES, EVENTS, PRACTICE или DEADLINES;
                - NONE должно использоваться отдельно и не должно находиться в одном массиве с другими значениями;
                - если needsChannelPosts=false, используй contentScopes=["NONE"];
                - resultMode=RELEVANT означает, что достаточно нескольких наиболее подходящих публикаций;
                - resultMode=ALL_MATCHING означает, что пользователь явно запросил все найденные записи внутри выбранных contentScopes;
                - ALL_MATCHING не означает, что нужно возвращать вообще все публикации канала вне запрошенных категорий;
                - слова "все", "весь список", "полный список", "перечисли всё" обычно означают ALL_MATCHING;
                - обычные вопросы "есть ли вакансии Java?" или "какие мероприятия намечаются?" обычно означают RELEVANT;
                - вопросы о больничных справках, военной кафедре, отчислении и других вопросах вне компетенции ЦКиТ не требуют FAQ и Telegram-постов;
                - для вопросов вне компетенции ЦКиТ используй contentScopes=["NONE"], needsChannelPosts=false и needsFaq=false.

                Пример 1.
                
                 Сообщение:
                 "Я недавно выпустился и не знаю, где найти работу.
                 Центр может мне помочь и какие сейчас есть вакансии?"
                
                 Ответ:
                 {
                   "intent": "VACANCY",
                   "topic": "graduate_job_support",
                   "contentScopes": ["VACANCIES"],
                   "resultMode": "RELEVANT",
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
                   "contentScopes": ["VACANCIES"],
                   "resultMode": "RELEVANT",
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
                   "contentScopes": ["NONE"],
                   "resultMode": "RELEVANT",
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
                   "contentScopes": ["VACANCIES"],
                   "resultMode": "RELEVANT",
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
                   "contentScopes": ["PRACTICE"],
                   "resultMode": "RELEVANT",
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
                   "contentScopes": ["NONE"],
                   "resultMode": "RELEVANT",
                   "needsChannelPosts": false,
                   "needsFaq": false,
                   "needsDeadlines": false
                 }
                
                
                 Пример 7.
                
                 Сообщение:
                 "Какие мероприятия планируются в университете в этом году?"
                
                 Ответ:
                 {
                   "intent": "GENERAL_UPDATES",
                   "topic": "upcoming_events",
                   "contentScopes": ["EVENTS"],
                   "resultMode": "RELEVANT",
                   "needsChannelPosts": true,
                   "needsFaq": false,
                   "needsDeadlines": true
                 }
                
                
                 Пример 8.
                
                 Сообщение:
                 "Что нового публиковали в Telegram-канале?"
                
                 Ответ:
                 {
                   "intent": "GENERAL_UPDATES",
                   "topic": "general_updates",
                   "contentScopes": ["ALL_UPDATES"],
                   "resultMode": "RELEVANT",
                   "needsChannelPosts": true,
                   "needsFaq": false,
                   "needsDeadlines": false
                 }
                 
                Пример 9.
                
                Сообщение:
                "Скинь мне списком все вакансии, которые есть на канале."
                
                Ответ:
                {
                  "intent": "VACANCY",
                  "topic": "list_all_vacancies",
                  "contentScopes": ["VACANCIES"],
                  "resultMode": "ALL_MATCHING",
                  "needsChannelPosts": true,
                  "needsFaq": false,
                  "needsDeadlines": false
                }
                
                
                Пример 10.
                
                Сообщение:
                "Скинь мне сначала все вакансии,
                а потом все мероприятия, планируемые в университете."
                
                Ответ:
                {
                  "intent": "GENERAL_UPDATES",
                  "topic": "list_vacancies_and_events",
                  "contentScopes": ["VACANCIES", "EVENTS"],
                  "resultMode": "ALL_MATCHING",
                  "needsChannelPosts": true,
                  "needsFaq": false,
                  "needsDeadlines": false
                }
                
                
                Пример 11.
                
                Сообщение:
                "Какие сейчас есть актуальные дедлайны?"
                
                Ответ:
                {
                  "intent": "DEADLINE",
                  "topic": "current_deadlines",
                  "contentScopes": ["DEADLINES"],
                  "resultMode": "RELEVANT",
                  "needsChannelPosts": true,
                  "needsFaq": false,
                  "needsDeadlines": true
                }
                
                
                Пример 12.
                
                Сообщение:
                "Где забрать документы после отчисления?"
                
                Ответ:
                {
                  "intent": "GENERAL_CHAT",
                  "topic": "documents_after_expulsion",
                  "contentScopes": ["NONE"],
                  "resultMode": "RELEVANT",
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

            ChannelSearchIntent intent =
                    parseIntent(readText(root, "intent"));

            String topic =
                    readNullableText(root, "topic");

            boolean needsChannelPosts =
                    readBoolean(
                            root,
                            "needsChannelPosts",
                            false
                    );

            boolean needsFaq =
                    readBoolean(
                            root,
                            "needsFaq",
                            false
                    );

            boolean needsDeadlines =
                    readBoolean(
                            root,
                            "needsDeadlines",
                            false
                    );

            List<ChannelContentScope> contentScopes =
                    parseContentScopes(root, intent);

            ChannelResultMode resultMode =
                    parseResultMode(
                            readText(root, "resultMode")
                    );

            if (!needsChannelPosts) {
                contentScopes = List.of(
                        ChannelContentScope.NONE
                );
            }

            return new ChannelQueryAnalysis(
                    intent,
                    topic,
                    contentScopes,
                    resultMode,
                    needsChannelPosts,
                    needsFaq,
                    needsDeadlines
            );
        }
        catch (Exception exception) {
            log.warn(
                    "Failed to parse channel query analysis. Raw LLM text: {}",
                    llmText,
                    exception
            );

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

    private ChannelContentScope parseContentScope(String value, ChannelSearchIntent intent) {
        if (value == null || value.isBlank()) {
            return defaultContentScope(intent);
        }

        try {
            return ChannelContentScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return defaultContentScope(intent);
        }
    }

    private ChannelContentScope defaultContentScope(ChannelSearchIntent intent) {
        if (intent == null) {
            return ChannelContentScope.NONE;
        }

        return switch (intent) {
            case VACANCY -> ChannelContentScope.VACANCIES;
            case PRACTICE ->  ChannelContentScope.PRACTICE;
            case DEADLINE ->  ChannelContentScope.DEADLINES;
            case GENERAL_UPDATES -> ChannelContentScope.ALL_UPDATES;
            case FAQ, GENERAL_CHAT, UNKNOWN -> ChannelContentScope.NONE;
        };
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

    private List<ChannelContentScope> parseContentScopes(
            JsonNode root,
            ChannelSearchIntent intent
    ) {
        JsonNode scopesNode =
                root.get("contentScopes");

        /*
         * Временная совместимость со старым JSON-контрактом.
         */
        if (scopesNode == null || scopesNode.isNull()) {
            scopesNode = root.get("contentScope");
        }

        LinkedHashSet<ChannelContentScope> scopes =
                new LinkedHashSet<>();

        if (scopesNode != null && !scopesNode.isNull()) {
            if (scopesNode.isArray()) {
                for (JsonNode item : scopesNode) {
                    ChannelContentScope scope =
                            parseContentScopeValue(
                                    item.asText()
                            );

                    if (scope != null) {
                        scopes.add(scope);
                    }
                }
            }
            else {
                ChannelContentScope scope =
                        parseContentScopeValue(
                                scopesNode.asText()
                        );

                if (scope != null) {
                    scopes.add(scope);
                }
            }
        }

        if (scopes.isEmpty()) {
            scopes.addAll(
                    defaultContentScopes(intent)
            );
        }

        return List.copyOf(scopes);
    }

    private ChannelContentScope parseContentScopeValue(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return ChannelContentScope.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<ChannelContentScope> defaultContentScopes(
            ChannelSearchIntent intent
    ) {
        if (intent == null) {
            return List.of(ChannelContentScope.NONE);
        }

        return switch (intent) {
            case VACANCY ->
                    List.of(ChannelContentScope.VACANCIES);

            case PRACTICE ->
                    List.of(ChannelContentScope.PRACTICE);

            case DEADLINE ->
                    List.of(ChannelContentScope.DEADLINES);

            case GENERAL_UPDATES ->
                    List.of(ChannelContentScope.ALL_UPDATES);

            case FAQ, GENERAL_CHAT, UNKNOWN ->
                    List.of(ChannelContentScope.NONE);
        };
    }

    private ChannelResultMode parseResultMode(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return ChannelResultMode.RELEVANT;
        }

        try {
            return ChannelResultMode.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        }
        catch (IllegalArgumentException exception) {
            return ChannelResultMode.RELEVANT;
        }
    }
}
