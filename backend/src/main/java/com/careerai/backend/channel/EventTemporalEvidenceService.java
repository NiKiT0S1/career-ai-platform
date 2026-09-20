package com.careerai.backend.channel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/** Dates used in answers are grounded in the current source, never its publication timestamp. */
@Service
public class EventTemporalEvidenceService {
    private static final java.util.regex.Pattern COMPRESSED_RANGE = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\d-])\\d{1,2}\\s*(?:[-–—]|по|to|through)\\s*\\d{1,2}\\s+[\\p{L}]+");
    private final TelegramChannelPostMetadataRepository metadataRepository;
    private final MultilingualDateTextParser parser;
    private final Clock clock;

    public EventTemporalEvidenceService(TelegramChannelPostMetadataRepository metadataRepository,
                                       MultilingualDateTextParser parser, Clock clock) {
        this.metadataRepository = metadataRepository;
        this.parser = parser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Evidence inspect(TelegramChannelPost post) {
        if (post == null) return Evidence.empty();
        TelegramChannelPostMetadata metadata = post.getId() == null ? null
                : metadataRepository.findByPostId(post.getId()).orElse(null);
        return inspect(post, metadata);
    }

    public Evidence inspect(TelegramChannelPost post, TelegramChannelPostMetadata metadata) {
        if (post == null) return Evidence.empty();
        LocalDate reference = post.getPostedAt() == null ? LocalDate.now(clock)
                : post.getPostedAt().atZoneSameInstant(clock.getZone()).toLocalDate();
        boolean ready = metadata != null
                && metadata.getExtractionStatus() == TelegramChannelPostExtractionStatus.SUCCESS;
        boolean event = ready && metadata.getPostType() == TelegramChannelPostType.EVENT;
        String eventQuote = ready ? supportedQuote(post.getText(), metadata.getEventDateText()) : null;
        String deadlineQuote = ready ? supportedQuote(post.getText(), metadata.getDeadlineText()) : null;
        DateParseResult eventDate = DateParseResult.unknown("Дата проведения не подтверждена исходным текстом");
        DateParseResult deadlineDate = DateParseResult.unknown("Срок подачи/регистрации не указан");
        if (eventQuote != null) {
            eventDate = parser.parseMatchingDate(post.getText(), eventQuote, reference);
        } else if (event && (metadata.getDeadlineText() == null || metadata.getDeadlineText().isBlank())
                && (metadata.getEventDateText() == null || metadata.getEventDateText().isBlank())) {
            // Legacy metadata without either date may use the source itself. An unsupported
            // extracted quote is deliberately NOT replaced with another guessed date.
            eventDate = parser.parse(post.getText(), reference);
        }
        if (deadlineQuote != null) deadlineDate = parser.parseMatchingDate(post.getText(), deadlineQuote, reference);
        String eventSource = eventQuote != null ? eventQuote : post.getText();
        if (eventDate.status() == DateParseStatus.PARSED && containsUnresolvedRange(parser, eventSource)) {
            eventDate = DateParseResult.unknown("Указан период или несколько дат мероприятия; границы проведения требуют уточнения");
        }
        if (post.hasCurrentDateConfirmation()) {
            DateParseResult confirmed = DateParseResult.parsed(post.getConfirmedDate(), post.getConfirmedDateBoundary());
            if (post.getConfirmedDatePurpose() == ChannelPostDatePurpose.EVENT_DATE) {
                eventDate = confirmed;
                event = true;
            } else if (post.getConfirmedDatePurpose() == ChannelPostDatePurpose.APPLICATION_DEADLINE) {
                deadlineDate = confirmed;
            }
        }
        return new Evidence(event || eventQuote != null, eventQuote, eventDate, deadlineQuote, deadlineDate);
    }

    /** Extraction may normalize whitespace, but it may not invent or change a date or year. */
    static String supportedQuote(String source, String quote) {
        if (source == null || quote == null || quote.isBlank()) return null;
        return normalize(source).contains(normalize(quote)) ? quote.trim() : null;
    }

    static boolean containsUnresolvedRange(MultilingualDateTextParser parser, String text) {
        return text != null && (parser.calendarMentions(text).size() > 1 || COMPRESSED_RANGE.matcher(text).find());
    }

    private static String normalize(String text) {
        return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public record Evidence(boolean eventRelated, String eventDateText, DateParseResult eventDate,
                           String deadlineText, DateParseResult deadlineDate) {
        static Evidence empty() {
            return new Evidence(false, null, DateParseResult.unknown("Публикация отсутствует"),
                    null, DateParseResult.unknown("Публикация отсутствует"));
        }
        public boolean eventHasPassed(LocalDate today) {
            return eventDate.status() == DateParseStatus.PARSED && eventDate.date().isBefore(today);
        }
        public boolean eventDateConfirmed() { return eventDate.status() == DateParseStatus.PARSED; }
    }
}
