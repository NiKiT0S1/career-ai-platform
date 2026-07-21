package com.careerai.backend.channel;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Неизменяемые входные данные
 * классификации Telegram Reply-связи.
 */

public record TelegramReplyRelationClassificationContext(
        Long sourcePostId,
        String sourceText,
        OffsetDateTime sourceDate,
        Long targetPostId,
        String targetText,
        OffsetDateTime targetDate
) {

    public TelegramReplyRelationClassificationContext {
        sourcePostId =
                Objects.requireNonNull(
                        sourcePostId,
                        "sourcePostId не может быть null"
                );

        targetPostId =
                Objects.requireNonNull(
                        targetPostId,
                        "targetPostId не может быть null"
                );

        sourceText =
                sourceText == null
                        ? ""
                        : sourceText;

        targetText =
                targetText == null
                        ? ""
                        : targetText;
    }
}