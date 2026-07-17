package com.careerai.backend.channel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Определяет актуальность Telegram-поста
 * на основании его структурированной metadata.
 *
 * Этот сервис ничего не сохраняет в базу:
 * он только принимает данные и возвращает решение.
 */

@Component
public class TelegramChannelPostFreshnessEvaluator {

    private final MultilingualDateTextParser dateParser;
    private final Clock clock;

    public TelegramChannelPostFreshnessEvaluator(
            MultilingualDateTextParser dateParser,
            Clock clock
    ) {
        this.dateParser = dateParser;
        this.clock = clock;
    }

    public TelegramChannelPostFreshnessEvaluation evaluate(
            TelegramChannelPost post,
            TelegramChannelPostMetadata metadata
    ) {
        if (post == null) {
            return unknown("Telegram-пост отсутствует");
        }

        if (metadata == null) {
            return unknown("Для поста отсутствует структурированная metadata");
        }

        if (metadata.getExtractionStatus() != TelegramChannelPostExtractionStatus.SUCCESS) {
            return unknown("Структурированная metadata ещё не готова: " + metadata.getExtractionStatus());
        }

        TelegramChannelPostType postType = metadata.getPostType();

        if (postType == null) {
            return unknown("Тип Telegram-поста не определён");
        }

        LocalDate referenceDate = resolveReferenceDate(post);

        return switch (postType) {
            case VACANCY ->
                evaluateDatedContent(
                        metadata.getDeadlineText(),
                        referenceDate,
                        "дедлайн вакансии"
                );
            case EVENT ->
                    evaluateDatedContent(
                            joinText(
                                    metadata.getDeadlineText(),
                                    metadata.getTitle(),
                                    metadata.getSummary(),
                                    post.getText()
                            ),
                            referenceDate,
                            "дата мероприятия"
                    );

            case PRACTICE ->
                    evaluateDatedContent(
                            joinText(
                                    metadata.getDeadlineText(),
                                    metadata.getPracticeEndText(),
                                    metadata.getSummary(),
                                    post.getText()
                            ),
                            referenceDate,
                            "срок публикации о практике"
                    );

            case DEADLINE ->
                    evaluateDatedContent(
                            joinText(
                                    metadata.getDeadlineText(),
                                    metadata.getSummary(),
                                    post.getText()
                            ),
                            referenceDate,
                            "дедлайн объявления"
                    );

            case ANNOUNCEMENT, OTHER ->
                    unknown("Для уточнений и общих объявлений нет самостоятельного срока действия");
        };
    }

    private TelegramChannelPostFreshnessEvaluation
    evaluateDatedContent(
            String sourceText,
            LocalDate referenceDate,
            String dateKind
    ) {
        DateParseResult parseResult =
                dateParser.parse(
                        sourceText,
                        referenceDate
                );

        return switch (parseResult.status()) {
            case PARSED ->
                    evaluateParsedDate(
                            parseResult.date(),
                            dateKind
                    );

            case INVALID ->
                    invalid(
                            parseResult.reason()
                    );

            case UNKNOWN ->
                    unknown(
                            "Не удалось определить "
                                    + dateKind
                                    + ": "
                                    + parseResult.reason()
                    );
        };
    }

    private TelegramChannelPostFreshnessEvaluation
    evaluateParsedDate(
            LocalDate date,
            String dateKind
    ) {
        OffsetDateTime expiresAt = date
                .plusDays(1)
                .atStartOfDay(clock.getZone())
                .toOffsetDateTime();

        OffsetDateTime now =
                OffsetDateTime.now(clock);

        if (!now.isBefore(expiresAt)) {
            return new TelegramChannelPostFreshnessEvaluation(
                    TelegramChannelPostFreshnessStatus.EXPIRED,
                    expiresAt,
                    "Указанная дата уже прошла: "
                            + date
                            + ". Тип даты: "
                            + dateKind
            );
        }

        return new TelegramChannelPostFreshnessEvaluation(
                TelegramChannelPostFreshnessStatus.ACTIVE,
                expiresAt,
                "Указанная дата ещё не прошла: "
                        + date
                        + ". Тип даты: "
                        + dateKind
        );
    }

    private TelegramChannelPostFreshnessEvaluation unknown(
            String reason
    ) {
        return new TelegramChannelPostFreshnessEvaluation(
                TelegramChannelPostFreshnessStatus.UNKNOWN,
                null,
                reason
        );
    }

    private TelegramChannelPostFreshnessEvaluation invalid(
            String reason
    ) {
        return new TelegramChannelPostFreshnessEvaluation(
                TelegramChannelPostFreshnessStatus.INVALID,
                null,
                reason
        );
    }

    private LocalDate resolveReferenceDate(
            TelegramChannelPost post
    ) {
        if (post.getPostedAt() != null) {
            return post.getPostedAt()
                    .atZoneSameInstant(clock.getZone())
                    .toLocalDate();
        }

        if (post.getCreatedAt() != null) {
            return post.getCreatedAt()
                    .atZoneSameInstant(clock.getZone())
                    .toLocalDate();
        }

        return LocalDate.now(clock);
    }

    private String joinText(String... values) {
        StringBuilder result =
                new StringBuilder();

        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append("\n");
            }

            result.append(value.trim());
        }

        return result.toString();
    }
}
