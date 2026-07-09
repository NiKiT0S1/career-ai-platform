package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Извлекает структурированную информацию из Telegram-поста через LLM.
 *
 * Сервис не отвечает пользователю. Его задача — один раз разобрать сырой текст
 * поста и сохранить удобную для поиска metadata: тип поста, позицию, компанию,
 * технологии, дедлайн, сроки практики и краткое описание.
 */

@Service
public class TelegramChannelPostMetadataExtractorService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostMetadataExtractorService.class);

    private final TelegramChannelPostMetadataRepository metadataRepository;
    private final LlmProvider llmProvider;
    private final ObjectMapper objectMapper;

    public TelegramChannelPostMetadataExtractorService(
            TelegramChannelPostMetadataRepository metadataRepository,
            LlmProvider llmProvider,
            ObjectMapper objectMapper
    ) {
        this.metadataRepository = metadataRepository;
        this.llmProvider = llmProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void extractAndSave(Long metadataId) {
        TelegramChannelPostMetadata metadata = metadataRepository
                .findById(metadataId)
                .orElse(null);

        if (metadata == null) {
            return;
        }

        TelegramChannelPost post = metadata.getPost();

        if (post == null || post.getText() == null || post.getText().isBlank()) {
            markAsSuccessWithEmptyData(metadata);
            return;
        }

        String prompt = buildExtractionPrompt(post.getText());

        LlmResponse response = llmProvider.generateAnswer(prompt);

        if (response.failed()) {
            markAsFailed(
                    metadata,
                    "LLM extraction failed. provider=%s, model=%s, errorType=%s"
                            .formatted(response.provider(), response.model(), response.errorType())
            );
            return;
        }

        try {
            applyExtractionResult(metadata, response.text());

            metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
            metadata.setExtractionError(null);
            metadata.setExtractedAt(OffsetDateTime.now());

            metadataRepository.save(metadata);

            log.info(
                    "Telegram channel post metadata extracted. metadataId={}, postId={}, postType={}, title={}",
                    metadata.getId(),
                    post.getId(),
                    metadata.getPostType(),
                    metadata.getTitle()
            );
        }
        catch (Exception e) {
            markAsFailed(metadata, "Failed to parse extraction JSON: " + e.getMessage());

            log.warn(
                    "Failed to extract Telegram channel post metadata. metadataId={}, rawLlmText={}",
                    metadata.getId(),
                    response.text(),
                    e
            );
        }
    }

    private String buildExtractionPrompt(String postText) {
        return """
                Ты внутренний extractor для Telegram-постов Центра карьеры AITU.

                Твоя задача — НЕ отвечать пользователю, а разобрать пост и вернуть JSON.

                Верни только JSON без Markdown, без ```json, без HTML и без пояснений.

                Возможные postType:
                - VACANCY — вакансия, работа, стажировка, позиция, internship/job
                - PRACTICE — производственная практика, документы, договоры, сроки практики
                - EVENT — мероприятие, мастер-класс, workshop, встреча, ярмарка
                - DEADLINE — пост, где главный смысл это срок или дедлайн
                - ANNOUNCEMENT — общее объявление
                - OTHER — если тип непонятен

                Правила:
                - Не выдумывай данные, которых нет в посте.
                - Если поля нет в посте, ставь null.
                - technologies верни строкой через запятую, например "Java, Spring Boot, PostgreSQL".
                - relevantForPractice = true, если пост связан с производственной практикой, документами практики или сроками практики.
                - summary сделай коротким: 1-2 предложения.

                Формат:
                {
                  "postType": "VACANCY",
                  "title": "Java Intern",
                  "company": "ООО Example",
                  "technologies": "Java, Spring Boot",
                  "levelText": "Intern",
                  "formatText": "Удалёнка",
                  "deadlineText": "30 июня",
                  "practiceStartText": null,
                  "practiceEndText": null,
                  "summary": "Краткое описание поста.",
                  "relevantForPractice": false
                }

                Текст Telegram-поста:
                "%s"
                """.formatted(postText);
    }

    private void applyExtractionResult(
            TelegramChannelPostMetadata metadata,
            String llmText
    ) throws Exception {
        String json = extractJson(llmText);
        JsonNode root = objectMapper.readTree(json);

        metadata.setPostType(parsePostType(readNullableText(root, "postType")));

        metadata.setTitle(limit(readNullableText(root, "title"), 500));
        metadata.setCompany(limit(readNullableText(root, "company"), 500));
        metadata.setTechnologies(readNullableTextOrArray(root, "technologies"));
        metadata.setLevelText(limit(readNullableText(root, "levelText"), 255));
        metadata.setFormatText(limit(readNullableText(root, "formatText"), 255));

        metadata.setDeadlineText(limit(readNullableText(root, "deadlineText"), 500));
        metadata.setPracticeStartText(limit(readNullableText(root, "practiceStartText"), 255));
        metadata.setPracticeEndText(limit(readNullableText(root, "practiceEndText"), 255));

        metadata.setSummary(readNullableText(root, "summary"));
        metadata.setRelevantForPractice(readBoolean(root, "relevantForPractice", false));
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("LLM extraction response is empty");
        }

        int startIndex = text.indexOf("{");
        int endIndex = text.indexOf("}");

        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) {
            throw new IllegalArgumentException("LLM extraction response does not contain JSON object");
        }

        return text.substring(startIndex, endIndex + 1);
    }

    private TelegramChannelPostType parsePostType(String value) {
        if (value == null || value.isBlank()) {
            return TelegramChannelPostType.OTHER;
        }

        try {
            return TelegramChannelPostType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return TelegramChannelPostType.OTHER;
        }
    }

    private String readNullableText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);

        if (value == null || value.isNull()) {
            return null;
        }

        String text = value.asText();

        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }

        return text.trim();
    }

    private String readNullableTextOrArray(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);

        if (value == null || value.isNull()) {
            return null;
        }

        if (value.isArray()) {
            List<String> items = new ArrayList<>();

            for (JsonNode item : value) {
                String text = item.asText();

                if (text != null || !text.isBlank()) {
                    items.add(text.trim());
                }
            }

            if (items.isEmpty()) {
                return null;
            }

            return String.join(", ", items);
        }

        return readNullableText(root, fieldName);
    }

    private boolean readBoolean(JsonNode root, String fieldName, boolean defaultValue) {
        JsonNode value = root.get(fieldName);

        if (value == null || value.isNull()) {
            return defaultValue;
        }

        return value.asBoolean(defaultValue);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }

    private void markAsSuccessWithEmptyData(TelegramChannelPostMetadata metadata) {
        metadata.setPostType(TelegramChannelPostType.OTHER);
        metadata.setTitle(null);
        metadata.setCompany(null);
        metadata.setTechnologies(null);
        metadata.setLevelText(null);
        metadata.setFormatText(null);
        metadata.setDeadlineText(null);
        metadata.setPracticeStartText(null);
        metadata.setPracticeEndText(null);
        metadata.setSummary("Текст поста отсутствует.");
        metadata.setRelevantForPractice(false);
        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
        metadata.setExtractionError(null);
        metadata.setExtractedAt(OffsetDateTime.now());

        metadataRepository.save(metadata);
    }

    private void markAsFailed(TelegramChannelPostMetadata metadata, String errorText) {
        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.FAILED);
        metadata.setExtractionError(errorText);
        metadata.setExtractedAt(null);

        metadataRepository.save(metadata);

        log.warn(
                "Telegram channel post metadata extraction failed. metadataId={}, error={}",
                metadata.getId(),
                errorText
        );
    }
}
