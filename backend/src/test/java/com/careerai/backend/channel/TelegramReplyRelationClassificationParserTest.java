package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class TelegramReplyRelationClassificationParserTest {

    private TelegramReplyRelationClassificationParser
            parser;

    @BeforeEach
    void setUp() {
        parser =
                new TelegramReplyRelationClassificationParser(
                        new ObjectMapper(),
                        new TelegramRelationClassificationPolicy()
                );
    }

    @Test
    void acceptsValidHighConfidenceResult() {
        LlmResponse response =
                LlmResponse.success(
                        """
                        {
                          "relationType": "UPDATE",
                          "reason": "Добавлены время и место проведения",
                          "confidence": 0.94
                        }
                        """,
                        "Gemini",
                        "test-model",
                        100
                );

        TelegramReplyRelationClassification result =
                parser.parse(response);

        assertEquals(
                TelegramChannelPostRelationClassificationStatus
                        .CLASSIFIED,
                result.status()
        );

        assertEquals(
                TelegramChannelPostRelationType.UPDATE,
                result.relationType()
        );

        assertEquals(
                0.94,
                result.confidence()
        );
    }

    @Test
    void sendsLowConfidenceResultToReview() {
        LlmResponse response =
                LlmResponse.success(
                        """
                        {
                          "relationType": "CORRECTION",
                          "reason": "Возможно изменено время",
                          "confidence": 0.61
                        }
                        """,
                        "Gemini",
                        "test-model",
                        100
                );

        TelegramReplyRelationClassification result =
                parser.parse(response);

        assertEquals(
                TelegramChannelPostRelationClassificationStatus
                        .REVIEW_REQUIRED,
                result.status()
        );
    }

    @Test
    void extractsJsonFromMarkdownBlock() {
        LlmResponse response =
                LlmResponse.success(
                        """
                        ```json
                        {
                          "relationType": "CANCELLATION",
                          "reason": "Отменено только отдельное условие",
                          "confidence": 0.97
                        }
                        ```
                        """,
                        "Groq",
                        "test-model",
                        100
                );

        TelegramReplyRelationClassification result =
                parser.parse(response);

        assertEquals(
                TelegramChannelPostRelationType
                        .CANCELLATION,
                result.relationType()
        );

        assertEquals(
                TelegramChannelPostRelationClassificationStatus
                        .CLASSIFIED,
                result.status()
        );
    }

    @Test
    void rejectsInvalidJson() {
        LlmResponse response =
                LlmResponse.success(
                        "Это не JSON",
                        "Gemini",
                        "test-model",
                        100
                );

        TelegramReplyRelationClassification result =
                parser.parse(response);

        assertEquals(
                TelegramChannelPostRelationClassificationStatus
                        .FAILED,
                result.status()
        );

        assertTrue(result.failed());
    }
}