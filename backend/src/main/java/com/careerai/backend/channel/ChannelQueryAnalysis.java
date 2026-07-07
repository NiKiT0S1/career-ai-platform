package com.careerai.backend.channel;

/**
 * Результат анализа пользовательского запроса.
 *
 * Этот объект возвращается LLM-router'ом и помогает backend понять,
 * нужно ли искать актуальные посты Telegram-канала.
 */

public record ChannelQueryAnalysis(
        ChannelSearchIntent intent,
        String topic,
        boolean needsChannelPosts,
        boolean needsFaq,
        boolean needsDeadlines
) {

    public static ChannelQueryAnalysis unknown() {
        return new ChannelQueryAnalysis(
                ChannelSearchIntent.UNKNOWN,
                null,
                false,
                false,
                false
        );
    }

    public boolean requiresChannelPosts() {
        return needsChannelPosts;
    }
}
