package com.careerai.backend.channel;

import org.springframework.stereotype.Component;

/**
 * Централизованные правила принятия
 * результата автоматической классификации.
 */

@Component
public class TelegramRelationClassificationPolicy {

    /**
     * Это не языковой хардкод,
     * а порог доверия бизнес-операции.
     */
    private static final double AUTO_ACCEPT_CONFIDENCE = 0.85;

    private static final int MAX_REASON_LENGTH = 3000;

    private static final int MAX_RAW_RESPONSE_LENGTH = 20000;

    public TelegramChannelPostRelationClassificationStatus determineStatus(
            TelegramChannelPostRelationType relationType,
            String reason,
            Double confidence
    ) {
        if (relationType == null || relationType == TelegramChannelPostRelationType.UNCLASSIFIED) {
            return TelegramChannelPostRelationClassificationStatus.REVIEW_REQUIRED;
        }

        if (reason == null || reason.isBlank()) {
            return TelegramChannelPostRelationClassificationStatus.REVIEW_REQUIRED;
        }

        if (confidence == null || confidence < AUTO_ACCEPT_CONFIDENCE) {
            return TelegramChannelPostRelationClassificationStatus.REVIEW_REQUIRED;
        }

        return TelegramChannelPostRelationClassificationStatus.CLASSIFIED;
    }

    public String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }

        String normalized = reason.strip();

        if (normalized.isBlank()) {
            return null;
        }

        return truncate(normalized, MAX_REASON_LENGTH);
    }

    public String normalizeRawResponse(String rawResponse) {
        if (rawResponse == null) {
            return null;
        }

        return truncate(rawResponse, MAX_RAW_RESPONSE_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }
}