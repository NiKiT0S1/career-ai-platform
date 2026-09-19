package com.careerai.backend.channel;

import java.util.LinkedHashSet;
import java.util.List;
import java.time.LocalDate;

/**
 * Результат анализа пользовательского запроса.
 *
 * Этот объект возвращается LLM-router'ом и помогает backend понять,
 * нужно ли искать актуальные посты Telegram-канала.
 */

public record ChannelQueryAnalysis(
        ChannelSearchIntent intent,
        String topic,
        List<ChannelContentScope> contentScopes,
        ChannelResultMode resultMode,
        boolean needsChannelPosts,
        boolean needsFaq,
        boolean needsDeadlines,
        String directAnswer,
        ChannelTimeScope timeScope,
        ChannelFreshnessScope freshnessScope,
        LocalDate dateFrom,
        LocalDate dateTo
) {

    public ChannelQueryAnalysis {
        timeScope = timeScope == null ? ChannelTimeScope.ANY_TIME : timeScope;
        freshnessScope = freshnessScope == null ? ChannelFreshnessScope.CURRENT : freshnessScope;
        intent = intent == null
                ? ChannelSearchIntent.UNKNOWN
                : intent;

        contentScopes = normalizeScopes(contentScopes);

        resultMode = resultMode == null
                ? ChannelResultMode.RELEVANT
                : resultMode;
    }

    public ChannelQueryAnalysis(ChannelSearchIntent intent, String topic,
                                List<ChannelContentScope> contentScopes, ChannelResultMode resultMode,
                                boolean needsChannelPosts, boolean needsFaq, boolean needsDeadlines) {
        this(intent, topic, contentScopes, resultMode, needsChannelPosts, needsFaq, needsDeadlines, null);
    }

    public ChannelQueryAnalysis(ChannelSearchIntent intent, String topic,
                                List<ChannelContentScope> contentScopes, ChannelResultMode resultMode,
                                boolean needsChannelPosts, boolean needsFaq, boolean needsDeadlines, String directAnswer) {
        this(intent, topic, contentScopes, resultMode, needsChannelPosts, needsFaq, needsDeadlines,
                directAnswer, ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.CURRENT, null, null);
    }

    public boolean requiresTimelineSearch() {
        return timeScope != ChannelTimeScope.ANY_TIME || freshnessScope != ChannelFreshnessScope.CURRENT;
    }

    public static ChannelQueryAnalysis unknown() {
        return new ChannelQueryAnalysis(
                ChannelSearchIntent.UNKNOWN,
                null,
                List.of(ChannelContentScope.NONE),
                ChannelResultMode.RELEVANT,
                false,
                false,
                false
        );
    }

    public boolean requiresChannelPosts() {
        return needsChannelPosts;
    }

    public boolean hasScope(ChannelContentScope scope) {
        return contentScopes.contains(scope);
    }

    private static List<ChannelContentScope> normalizeScopes(
            List<ChannelContentScope> scopes
    ) {
        if (scopes == null || scopes.isEmpty()) {
            return List.of(ChannelContentScope.NONE);
        }

        LinkedHashSet<ChannelContentScope> normalized =
                new LinkedHashSet<>();

        for (ChannelContentScope scope : scopes) {
            if (scope != null) {
                normalized.add(scope);
            }
        }

        if (normalized.isEmpty()) {
            return List.of(ChannelContentScope.NONE);
        }

        if (normalized.size() > 1) {
            normalized.remove(ChannelContentScope.NONE);
        }

        /*
         * ALL_UPDATES уже включает остальные категории.
         */
        if (normalized.contains(
                ChannelContentScope.ALL_UPDATES
        )) {
            return List.of(
                    ChannelContentScope.ALL_UPDATES
            );
        }

        return List.copyOf(normalized);
    }
}
