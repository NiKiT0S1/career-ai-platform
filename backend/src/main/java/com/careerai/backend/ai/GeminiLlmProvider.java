package com.careerai.backend.ai;

import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class GeminiLlmProvider implements LlmProvider {

    private static final String SYSTEM_PROMPT = """
            Ты CareerAI — AI-ассистент Центра карьеры и трудоустройства AITU.

            Отвечай только по темам:
            - производственная практика;
            - стажировки;
            - вакансии;
            - дуальное обучение;
            - карьерные мероприятия;
            - мастер-классы;
            - CV и карьерные рекомендации.

            Если вопрос не относится к Центру карьеры и трудоустройства, вежливо скажи,
            что ты помогаешь только по карьерным вопросам, практике, стажировкам,
            вакансиям и CV.

            Не выдумывай конкретные дедлайны, вакансии и официальные требования.
            Если точных данных нет, честно скажи, что информации пока нет в базе.
            
            Не используй Markdown-разметку.
            Не используй символы **, #, таблицы и сложное форматирование.
            Отвечай обычным текстом, который хорошо читается в Telegram.
            Для списков используй обычные маркеры: • или дефис.
            """;

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GeminiLlmProvider(GeminiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    @Override
    public String generateAnswer(String userMessage) {
        try {
            String url = "%s/%s:generateContent"
                    .formatted(properties.getBaseUrl(), properties.getModel());

            Map<String, Object> requestBody = Map.of(
                    "system_instruction", Map.of(
                            "parts", List.of(
                                    Map.of("text", SYSTEM_PROMPT)
                            )
                    ),
                    "contents", List.of(
                            Map.of(
                                    "parts", List.of(
                                            Map.of("text", userMessage)
                                    )
                            )
                    )
            );

            String responseJson = restClient.post()
                    .uri(url)
                    .header("x-goog-api-key", properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            return extractText(responseJson);
        }
        catch (HttpServerErrorException.ServiceUnavailable e) {
            System.out.println("Gemini API is temporarily unavailable: " + e.getStatusCode());

            return "Сейчас AI-модель временно перегружена. Попробуй повторить вопрос через минуту.";
        }
        catch (Exception e) {
            System.out.println("Unexpected error while calling Gemini API: " + e.getMessage());

            return "Сейчас я не могу обработать запрос через AI. Попробуй чуть позже.";
        }
    }

    private String extractText(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);

        JsonNode textNode = root
                .path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text");

        if (textNode.isMissingNode() || textNode.asText().isBlank()) {
            return "Я получил ответ от AI, но не смог корректно извлечь текст.";
        }

        return textNode.asText();
    }
}
