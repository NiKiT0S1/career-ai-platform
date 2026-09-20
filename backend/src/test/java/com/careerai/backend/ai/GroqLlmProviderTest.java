package com.careerai.backend.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroqLlmProviderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final GroqProperties properties = new GroqProperties();
    private HttpServer server;
    private GroqLlmProvider provider;
    private volatile int responseStatus = 200;
    private volatile String responseBody = """
            {"choices":[{"finish_reason":"stop","message":{
              "content":"{\\\"intent\\\":\\\"GENERAL_CHAT\\\"}",
              "reasoning":"Internal reasoning must never become the answer"
            }}]}
            """;

    @BeforeEach
    void startLocalStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            try (exchange) {
                receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/openai/v1");
        properties.setKey("local-unit-test-key");
        properties.setModel("openai/gpt-oss-20b");
        provider = new GroqLlmProvider(properties, objectMapper);
    }

    @AfterEach
    void stopLocalStub() {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai/gpt-oss-20b", "openai/gpt-oss-120b"})
    void gptOssAddsBoundedReasoningBudgetAndReturnsOnlyVisibleContent(String model) {
        properties.setModel(model);

        LlmResponse response = provider.execute(analysisRequest(450));

        assertTrue(response.success());
        assertEquals("{\"intent\":\"GENERAL_CHAT\"}", response.text());
        JsonNode payload = objectMapper.readTree(receivedBody.get());
        assertEquals(model, payload.path("model").asText());
        assertEquals(1474, payload.path("max_completion_tokens").asInt());
        assertFalse(payload.has("max_tokens"));
        assertEquals("low", payload.path("reasoning_effort").asText());
        assertTrue(payload.path("include_reasoning").isBoolean());
        assertFalse(payload.path("include_reasoning").asBoolean());
        assertFalse(payload.has("reasoning_format"));
        assertEquals("json_object", payload.path("response_format").path("type").asText());
        assertEquals(0.0, payload.path("temperature").asDouble());
        assertEquals(0.8, payload.path("top_p").asDouble());
        assertEquals("system", payload.path("messages").path(0).path("role").asText());
        assertEquals("Return a JSON object.", payload.path("messages").path(0).path("content").asText());
        assertEquals("user", payload.path("messages").path(1).path("role").asText());
        assertEquals("Hello", payload.path("messages").path(1).path("content").asText());
    }

    @Test
    void gptOssCompletionBudgetIsCappedWithoutIntegerOverflow() {
        assertTrue(provider.execute(analysisRequest(Integer.MAX_VALUE)).success());

        JsonNode payload = objectMapper.readTree(receivedBody.get());
        assertEquals(65536, payload.path("max_completion_tokens").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"llama-3.3-70b-versatile", "openai/gpt-oss-20b-custom"})
    void otherModelsKeepTheirExistingPayload(String model) {
        properties.setModel(model);

        assertTrue(provider.execute(analysisRequest(450)).success());

        JsonNode payload = objectMapper.readTree(receivedBody.get());
        assertEquals(model, payload.path("model").asText());
        assertEquals(450, payload.path("max_tokens").asInt());
        assertFalse(payload.has("max_completion_tokens"));
        assertFalse(payload.has("reasoning_effort"));
        assertFalse(payload.has("include_reasoning"));
        assertFalse(payload.has("reasoning_format"));
        assertEquals("json_object", payload.path("response_format").path("type").asText());
    }

    @Test
    void textAnswersRetainTheirFormatWhileUsingTheGptOssBudget() {
        assertTrue(provider.execute(LlmRequest.userAnswer("Hello")).success());

        JsonNode payload = objectMapper.readTree(receivedBody.get());
        assertEquals(1924, payload.path("max_completion_tokens").asInt());
        assertFalse(payload.has("response_format"));
    }

    @Test
    void truncatedContentIsFailureEvenWhenItLooksComplete() {
        responseBody = """
                {"choices":[{"finish_reason":"length","message":{"content":"Apparently complete answer"}}]}
                """;

        LlmResponse response = provider.execute(analysisRequest(450));

        assertTrue(response.failed());
        assertEquals(LlmErrorType.UNEXPECTED_ERROR, response.errorType());
        assertFalse(response.text().contains("Apparently complete answer"));
    }

    @Test
    void truncatedGroqCompletionFallsBackToGemini() {
        responseBody = """
                {"choices":[{"finish_reason":"length","message":{"content":"Partial answer"}}]}
                """;
        GeminiLlmProvider gemini = mock(GeminiLlmProvider.class);
        LlmRequest request = analysisRequest(450);
        LlmResponse fallback = LlmResponse.success("Complete answer", "Gemini", "configured-gemini-model", 1);
        when(gemini.execute(request)).thenReturn(fallback);
        ActiveLlmProvider active = new ActiveLlmProvider(new LlmProperties(), gemini, provider);

        assertSame(fallback, active.execute(request));
        verify(gemini).execute(request);
    }

    @Test
    void reasoningCannotSubstituteForMissingVisibleContent() {
        responseBody = """
                {"choices":[{"finish_reason":"stop","message":{"content":null,"reasoning":"Private reasoning"}}]}
                """;

        LlmResponse response = provider.execute(analysisRequest(450));

        assertTrue(response.failed());
        assertFalse(response.text().contains("Private reasoning"));
    }

    @Test
    void structuredModelNotFoundIsClassifiedAsModelFailure() {
        responseStatus = 404;
        responseBody = """
                {"error":{"code":"model_not_found","message":"The model does not exist or you do not have access."}}
                """;

        LlmResponse response = provider.execute(analysisRequest(450));

        assertTrue(response.failed());
        assertEquals(LlmErrorType.MODEL_NOT_FOUND, response.errorType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":{\"code\":\"route_not_found\",\"message\":\"model_not_found\"}}",
            "{\"error\":{\"message\":\"model_not_found\"}}",
            "<html>Not found</html>"
    })
    void generic404IsNotMisclassifiedAsModelFailure(String body) {
        responseStatus = 404;
        responseBody = body;

        LlmResponse response = provider.execute(analysisRequest(450));

        assertTrue(response.failed());
        assertEquals(LlmErrorType.UNEXPECTED_ERROR, response.errorType());
    }

    private static LlmRequest analysisRequest(int maxOutputTokens) {
        return new LlmRequest(LlmTaskType.QUERY_ANALYSIS, "Return a JSON object.", "Hello", 0.0, 0.8,
                maxOutputTokens, LlmResponseFormat.JSON, LlmTimeoutProfile.FAST, LlmProviderStrategy.GROQ_FIRST);
    }
}
