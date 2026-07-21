package com.careerai.backend.channel;

/**
 * Результат семантической классификации
 * Telegram Reply-связи.
 */

public record TelegramReplyRelationClassification(
        TelegramChannelPostRelationClassificationStatus status,
        TelegramChannelPostRelationType relationType,
        String reason,
        Double confidence,
        String provider,
        String model,
        String classifierVersion,
        String rawResponse,
        String errorMessage
) {

    public TelegramReplyRelationClassification {
        if (status == null) {
            throw new IllegalArgumentException("Статус классификации не может быть null");
        }

        if (relationType == null) {
            relationType = TelegramChannelPostRelationType.UNCLASSIFIED;
        }

        if (confidence != null && (confidence < 0.0|| confidence > 1.0)) {
            throw new IllegalArgumentException("Уверенность должна находиться между 0.0 и 1.0");
        }
    }

    public static TelegramReplyRelationClassification failed(
            String provider,
            String model,
            String classifierVersion,
            String rawResponse,
            String errorMessage
    ) {
        return new TelegramReplyRelationClassification(
                TelegramChannelPostRelationClassificationStatus.FAILED,
                TelegramChannelPostRelationType.UNCLASSIFIED,
                null,
                null,
                provider,
                model,
                classifierVersion,
                rawResponse,
                errorMessage
        );
    }

    public boolean classified() {
        return status == TelegramChannelPostRelationClassificationStatus.CLASSIFIED;
    }

    public boolean requiresReview() {
        return status == TelegramChannelPostRelationClassificationStatus.REVIEW_REQUIRED;
    }

    public boolean failed() {
        return status == TelegramChannelPostRelationClassificationStatus .FAILED;
    }
}