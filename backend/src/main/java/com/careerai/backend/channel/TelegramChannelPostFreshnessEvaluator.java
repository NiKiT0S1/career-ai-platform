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
            case VACANCY -> evaluateDatedContent(
                    metadata.getDeadlineText(),
                    post.getText(),
                    referenceDate,
                    "дедлайн вакансии"
            );

            case EVENT -> evaluateDatedContent(
                    joinText(
                            metadata.getDeadlineText(),
                            metadata.getTitle(),
                            metadata.getSummary()
                    ),
                    post.getText(),
                    referenceDate,
                    "дата мероприятия"
            );

            case PRACTICE -> evaluateDatedContent(
                    joinText(
                            metadata.getDeadlineText(),
                            metadata.getPracticeEndText(),
                            metadata.getSummary()
                    ),
                    post.getText(),
                    referenceDate,
                    "срок публикации о практике"
            );

            case DEADLINE -> evaluateDatedContent(
                    joinText(
                            metadata.getDeadlineText(),
                            metadata.getSummary()
                    ),
                    post.getText(),
                    referenceDate,
                    "дедлайн объявления"
            );

            case ANNOUNCEMENT, OTHER ->
                    unknown("Для уточнений и общих объявлений нет самостоятельного срока действия");
        };
    }

    private TelegramChannelPostFreshnessEvaluation evaluateDatedContent(
            String metadataText,
            String originalPostText,
            LocalDate referenceDate,
            String dateKind
    ) {
        DateParseResult parseResult = resolveDateParseResult(
                metadataText,
                originalPostText,
                referenceDate
        );

        return switch (parseResult.status()) {
            case PARSED -> evaluateParsedDate(
                    parseResult.date(),
                    parseResult.boundaryType(),
                    dateKind
            );

            case INVALID -> invalid(parseResult.reason());

            case UNKNOWN -> unknown(
                    "Не удалось определить "
                            + dateKind
                            + ": "
                            + parseResult.reason()
            );
        };
    }

    private TelegramChannelPostFreshnessEvaluation evaluateParsedDate(
            LocalDate date,
            DateBoundaryType boundaryType,
            String dateKind
    ) {
        DateBoundaryType safeBoundary = boundaryType == null
                ? DateBoundaryType.UNSPECIFIED
                : boundaryType;

        /*
         * EXCLUSIVE:
         * "до 21 июля" перестаёт действовать 21 июля в 00:00.
         *
         * INCLUSIVE и UNSPECIFIED:
         * публикация действует весь указанный день.
         */
        LocalDate expirationDate = safeBoundary == DateBoundaryType.EXCLUSIVE
                ? date
                : date.plusDays(1);

        OffsetDateTime expiresAt = expirationDate
                .atStartOfDay(clock.getZone())
                .toOffsetDateTime();

        OffsetDateTime now = OffsetDateTime.now(clock);
        String boundaryDescription = describeBoundary(safeBoundary);

        if (!now.isBefore(expiresAt)) {
            return new TelegramChannelPostFreshnessEvaluation(
                    TelegramChannelPostFreshnessStatus.EXPIRED,
                    expiresAt,
                    "Указанный срок уже истёк: " + date
                            + ". Тип даты: " + dateKind
                            + ". Граница: " + boundaryDescription
            );
        }

        return new TelegramChannelPostFreshnessEvaluation(
                TelegramChannelPostFreshnessStatus.ACTIVE,
                expiresAt,
                "Указанный срок ещё не истёк: " + date
                        + ". Тип даты: " + dateKind
                        + ". Граница: " + boundaryDescription
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

    private String describeBoundary(DateBoundaryType boundaryType) {
        return switch (boundaryType) {
            case EXCLUSIVE -> "дата не включается";
            case INCLUSIVE -> "дата включается";
            case UNSPECIFIED -> "граница не указана, дата считается включительной";
        };
    }

    /**
     * Использует metadata для определения нужной даты,
     * но восстанавливает её границу из оригинального поста.
     */
    private DateParseResult resolveDateParseResult(
            String metadataText,
            String originalPostText,
            LocalDate referenceDate
    ) {
        DateParseResult metadataResult = dateParser.parse(
                metadataText,
                referenceDate
        );

        /*
         * Если LLM вообще не смогла извлечь дату,
         * пытаемся разобрать исходный Telegram-пост.
         */
        if (metadataResult.status() != DateParseStatus.PARSED) {
            DateParseResult originalResult = dateParser.parse(
                    originalPostText,
                    referenceDate
            );

            if (originalResult.status() == DateParseStatus.PARSED) {
                return originalResult;
            }

            return metadataResult;
        }

        /*
         * Если metadata уже сохранила явную границу,
         * ничего дополнительно искать не нужно.
         */
        if (metadataResult.boundaryType() != DateBoundaryType.UNSPECIFIED) {
            return metadataResult;
        }

        /*
         * LLM могла вернуть "21 июля" вместо "до 21 июля".
         * Ищем именно эту дату в исходном тексте,
         * даже если до неё в посте встречаются другие даты.
         */
        DateBoundaryType originalBoundary = dateParser.findBoundaryForDate(
                originalPostText,
                metadataResult.date(),
                referenceDate
        );

        if (originalBoundary == DateBoundaryType.UNSPECIFIED) {
            return metadataResult;
        }

        return DateParseResult.parsed(
                metadataResult.date(),
                originalBoundary
        );
    }
}
