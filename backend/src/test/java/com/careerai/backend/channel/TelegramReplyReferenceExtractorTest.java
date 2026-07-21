package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TelegramReplyReferenceExtractorTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    private final TelegramReplyReferenceExtractor extractor =
            new TelegramReplyReferenceExtractor();

    @Test
    void extractsReplyMessageId() throws Exception {
        JsonNode message =
                objectMapper.readTree(
                        """
                        {
                          "message_id": 18,
                          "reply_to_message": {
                            "message_id": 16
                          }
                        }
                        """
                );

        Long result =
                extractor.extractReplyToMessageId(message);

        assertEquals(16L, result);
    }

    @Test
    void returnsNullWithoutReply() throws Exception {
        JsonNode message =
                objectMapper.readTree(
                        """
                        {
                          "message_id": 18,
                          "text": "Обычная публикация"
                        }
                        """
                );

        Long result =
                extractor.extractReplyToMessageId(message);

        assertNull(result);
    }

    @Test
    void returnsNullForInvalidReplyObject() throws Exception {
        JsonNode message =
                objectMapper.readTree(
                        """
                        {
                          "message_id": 18,
                          "reply_to_message": {
                            "message_id": "unknown"
                          }
                        }
                        """
                );

        Long result =
                extractor.extractReplyToMessageId(message);

        assertNull(result);
    }
}