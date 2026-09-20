package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StandaloneRelationClassifierTest {
    private final StandaloneRelationClassifier classifier = new StandaloneRelationClassifier(mock(LlmProvider.class), new ObjectMapper());
    private final StandaloneRelationClassifier.Input input = new StandaloneRelationClassifier.Input(1, 20, 10, "hash", "new", "old", "today", "yesterday", 1);

    @Test void rejectsHallucinatedIdsAndMalformedResults() {
        assertFalse(parse("{\"sourcePostId\":20,\"targetPostId\":999,\"related\":true}").success());
        assertFalse(parse("not JSON").success());
        assertFalse(parse(json("\"NaN\"", "true")).success());
        assertFalse(parse(json("1.2", "true")).success());
        assertFalse(parse(json("0.99", "\"true\"")).success());
        assertFalse(parse(json("0.99", "true") + " {}").success());
    }

    @Test void preservesPrecisePartialCancellationAndUncertainty() {
        var result = parse(json("0.99", "true"));
        assertTrue(result.success()); assertTrue(result.related());
        assertEquals(TelegramChannelPostRelationType.CANCELLATION, result.type());
        assertEquals("Отменена только очная часть", result.reason());
        assertEquals(.99, result.confidence());
    }

    @Test void usesTaskRequestAndTreatsPostsAsUntrustedData() {
        LlmRequest request = classifier.createRequest(input);
        assertEquals(LlmTaskType.CHANNEL_RELATION_CLASSIFICATION, request.taskType());
        assertEquals(LlmResponseFormat.JSON, request.responseFormat());
        assertTrue(request.systemPrompt().contains("untrusted data"));
        assertTrue(request.userPrompt().contains("id=10"));
    }

    private StandaloneRelationClassifier.Result parse(String json) {
        return classifier.parse(input, LlmResponse.success(json, "test", "test", 1));
    }
    private String json(String confidence, String related) {
        return "{\"sourcePostId\":20,\"targetPostId\":10,\"related\":" + related
                + ",\"relationType\":\"CANCELLATION\",\"reason\":\"Отменена только очная часть\",\"confidence\":" + confidence + ",\"explicitChange\":true}";
    }
}
