package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Проверяет и преобразует JSON-ответ
 * AI-классификатора.
 */

@Component
public class TelegramReplyRelationClassificationParser {

    private final ObjectMapper objectMapper;
    private final TelegramRelationClassificationPolicy policy;

    public TelegramReplyRelationClassificationParser(
            ObjectMapper objectMapper,
            TelegramRelationClassificationPolicy policy
    ) {
        this.objectMapper = objectMapper;
        this.policy = policy;
    }

    public TelegramReplyRelationClassification parse(LlmResponse response) {
        if (response == null) {
            return TelegramReplyRelationClassification.failed(
                    null,
                    null,
                    TelegramRelationClassifierVersion.CURRENT,
                    null,
                    "LLM response отсутствует"
            );
        }

        String rawResponse = policy.normalizeRawResponse(response.text());

        if (response.failed()) {
            return TelegramReplyRelationClassification.failed(
                    response.provider(),
                    response.model(),
                    TelegramRelationClassifierVersion.CURRENT,
                    rawResponse,
                    "AI-провайдер завершил запрос с ошибкой: " + response.errorType()
            );
        }

        try {
            String jsonText = extractJsonObject(rawResponse);

            JsonNode root = objectMapper.readTree( jsonText);

            TelegramChannelPostRelationType relationType = parseRelationType(root.get("relationType"));

            String reason = parseReason(root.get("reason"));

            Double confidence = parseConfidence(root.get("confidence"));

            TelegramChannelPostRelationClassificationStatus status =
                    policy.determineStatus(
                            relationType,
                            reason,
                            confidence
                    );

            return new TelegramReplyRelationClassification(
                    status,
                    relationType,
                    reason,
                    confidence,
                    response.provider(),
                    response.model(),
                    TelegramRelationClassifierVersion.CURRENT,
                    rawResponse,
                    null
            );
        }
        catch (Exception exception) {
            return TelegramReplyRelationClassification.failed(
                    response.provider(),
                    response.model(),
                    TelegramRelationClassifierVersion.CURRENT,
                    rawResponse,
                    "Не удалось разобрать ответ классификатора: "
                            + exception.getMessage()
            );
        }
    }

    private TelegramChannelPostRelationType parseRelationType(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return TelegramChannelPostRelationType.UNCLASSIFIED;
        }

        String normalized = node.asText().strip().toUpperCase(Locale.ROOT);

        return TelegramChannelPostRelationType.valueOf(normalized);
    }

    private String parseReason( JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        return policy.normalizeReason(node.asText());
    }

    private Double parseConfidence(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        double confidence;

        if (node.isNumber()) {
            confidence = node.asDouble();
        }
        else {
            confidence = Double.parseDouble(node.asText());
        }

        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence находится вне диапазона 0.0–1.0");
        }

        return confidence;
    }

    private String extractJsonObject(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalArgumentException("Ответ модели пуст");
        }

        int firstBrace = rawResponse.indexOf('{');

        int lastBrace = rawResponse.lastIndexOf('}');

        if (firstBrace < 0 || lastBrace < firstBrace) {
            throw new IllegalArgumentException("JSON-объект не найден");
        }

        return rawResponse.substring(firstBrace, lastBrace + 1);
    }
}