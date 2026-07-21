package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmRequest;
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
    private final ChannelQueryAnalysisRequestFactory requestFactory;

    public ChannelQueryAnalyzer (LlmProvider llmProvider, ObjectMapper objectMapper, ChannelQueryAnalysisRequestFactory requestFactory) {
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
        this.requestFactory = requestFactory;
    }

    public ChannelQueryAnalysis analyze(String userMessage) {
        if (userMessage == null ||  userMessage.isBlank()) {
            return ChannelQueryAnalysis.unknown();
        }

        LlmRequest request = requestFactory.create(userMessage);

        LlmResponse response = llmProvider.execute(request);

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
