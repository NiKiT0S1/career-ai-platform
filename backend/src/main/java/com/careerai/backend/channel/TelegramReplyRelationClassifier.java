package com.careerai.backend.channel;

/**
 * Определяет семантический тип
 * подтверждённой Telegram Reply-связи.
 */
public interface TelegramReplyRelationClassifier {

    TelegramReplyRelationClassification classify(TelegramReplyRelationClassificationContext context);

    String classifierVersion();
}