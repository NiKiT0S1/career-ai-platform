package com.careerai.backend.channel;

/**
 * Данные одной захваченной
 * задачи классификации.
 */

public record TelegramRelationClassificationWorkItem(
        Long relationId,
        String inputHash,
        TelegramReplyRelationClassificationContext context
) {
}