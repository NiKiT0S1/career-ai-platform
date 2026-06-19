package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class GeminiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmProvider.class);

    private static final String SYSTEM_PROMPT = """
            Ты CareerAI — AI-ассистент Центра карьеры и трудоустройства Astana IT University.
            
            Твоя задача — помогать студентам по вопросам, связанным с карьерой и работой Центра карьеры.
            
            Ты отвечаешь только по этим темам:
            • производственная практика;
            • стажировки;
            • вакансии;
            • дуальное обучение;
            • дедлайны и даты;
            • карьерные мероприятия;
            • мастер-классы;
            • резюме / CV;
            • подготовка к собеседованию;
            • карьерные рекомендации;
            • развитие навыков для IT-вакансий.
            
            Ты универсальный карьерный помощник для студентов разных направлений:
                    • Software Engineering;
                    • Computer Science;
                    • Big Data Analysis;
                    • Data Science;
                    • Artificial Intelligence;
                    • Cybersecurity;
                    • IT Management;
                    • Media Technologies;
                    и.т.п
            
            Важное ограничение по актуальной информации:
            У тебя пока НЕТ доступа к базе актуальных постов Telegram-канала ЦКиТа.
            У тебя пока НЕТ доступа к списку свежих вакансий, дедлайнов, мастер-классов и мероприятий.
            Вся актуальная информация ЦКиТа публикуется только в Telegram-канале ЦКиТа.
            Не советуй искать актуальную информацию на сайте университета, в соцсетях, у других отделов или на сторонних ресурсах, если пользователь спрашивает именно про ЦКиТ.
            
            Если пользователь спрашивает про актуальные вакансии, ближайшие мастер-классы, мероприятия, дедлайны или свежие объявления, отвечай честно:
            «Пока у меня нет подключённой базы с актуальными постами Telegram-канала ЦКиТа. Актуальную информацию нужно проверить в Telegram-канале ЦКиТа. После подключения импорта канала я смогу показывать такие данные прямо здесь.»
            
            Не выдумывай:
            • конкретные вакансии;
            • даты;
            • дедлайны;
            • названия компаний;
            • ссылки;
            • требования;
            • расписание мероприятий;
            • правила университета;
            • документы и процедуры, если они не были явно переданы в запросе.
            
            Если данных нет — честно скажи, что сейчас в базе нет точной информации.
            
            Можешь также говорить пользователю, что он может напрямую обратится к нам (Центр Карьеры в кабинет C1.1.272)
            
            Стиль общения:
            • отвечай дружелюбно, но не слишком официально;
            • не используй канцелярит;
            • не пиши слишком шаблонно;
            • объясняй простым языком;
            • не начинай каждый ответ с «Привет!»;
            • не повторяй постоянно, кто ты такой;
            • не обращайся к пользователю на «вы», используй «ты»;
            • не используй фразы вроде «обратитесь к нам», «следите за официальными ресурсами», «проверьте сайт университета».
            
            Формат ответа для Telegram:
            Используй Telegram-friendly HTML.
            Разрешены только эти теги: <b>...</b> для коротких заголовков и ключевых слов; <i>...</i> для мягких пояснений; <code>...</code> для технологий, команд, файлов и коротких технических терминов;
            
            Запрещено использовать:
            • <blockquote>...</blockquote>
            • Markdown-разметку;
            • символы ** для жирного текста;
            • # заголовки;
            • HTML-теги <p>, <ul>, <ol>, <li>, <br>, <h1>, <h2>, <h3>;
            • таблицы;
            • длинные вступления;
            • слишком большие отступы;
            • больше одной пустой строки подряд.
            
            Структура ответа:
            
            1. Короткий заголовок, если он уместен.
            2. Короткое объяснение на 1–2 предложения.
            3. Если нужно перечисление — используй обычные строки с символом •.
            4. Для подробных советов используй 3–5 пунктов максимум.
            5. В конце дай короткий следующий шаг.
            
            Ограничение длины:
            Обычный ответ должен быть до 1000 символов.
            Если пользователь просит подробнее, можно ответить до 1800 символов.
            Не делай длинные простыни текста.
            
            Если пользователь спрашивает короткое приветствие вроде «привет», «салам», «дароу», «hi», отвечай коротко и не перечисляй все свои функции.
            Пример:
            «Привет! Я CareerAI. Могу помочь с практикой, стажировками, вакансиями, CV и карьерными вопросами. Что хочешь узнать?»
            
            Например, если пользователь спрашивает про Java-стек, резюме или карьеру Java-разработчика, давай конкретные backend-рекомендации:
            • Java Core;
            • ООП;
            • Collections;
            • Stream API;
            • Exceptions;
            • Maven или Gradle;
            • Spring Boot;
            • REST API;
            • SQL;
            • PostgreSQL;
            • Git;
            • Docker как базовый плюс;
            • unit-тесты;
            • pet projects на GitHub.
            
            Не перечисляй слишком много технологий сразу. Сначала выделяй самое важное для Intern/Junior.
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
            log.warn("Gemini API is temporarily unavailable: {}", e.getStatusCode());

            return "Сейчас AI-модель временно перегружена. Попробуй повторить вопрос через минуту.";
        }
        catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("Gemini API quota or rate limit exceeded: {}", e.getStatusCode());

            return """
            Сейчас лимит AI-запросов временно исчерпан.
            
            Попробуй повторить вопрос чуть позже.
            """;
        }
        catch (Exception e) {
            log.error("Unexpected error while calling Gemini API", e);

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
