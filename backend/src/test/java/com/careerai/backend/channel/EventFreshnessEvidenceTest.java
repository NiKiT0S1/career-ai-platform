package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class EventFreshnessEvidenceTest {
    private final TelegramChannelPostFreshnessEvaluator evaluator = new TelegramChannelPostFreshnessEvaluator(
            new MultilingualDateTextParser(new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector()),
            Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Asia/Almaty")));

    @Test
    void registrationDeadlineDoesNotExpireUpcomingEvent() {
        var post = post("Регистрация до 1 сентября 2026. Мероприятие 15 ноября 2026.");
        var metadata = metadata("15 ноября 2026", "до 1 сентября 2026");
        var result = evaluator.evaluate(post, metadata);
        assertEquals(TelegramChannelPostFreshnessStatus.ACTIVE, result.status());
        assertEquals(LocalDate.of(2026, 11, 16), result.expiresAt().toLocalDate());
    }

    @Test
    void registrationOnlyDoesNotPretendToKnowEventDate() {
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,
                evaluator.evaluate(post("Регистрация до 1 сентября 2026"), metadata(null, "до 1 сентября 2026")).status());
    }

    @Test
    void ongoingRangeCannotExpireAtItsFirstDate() {
        for (String range : new String[]{"18 сентября 2026 — 21 сентября 2026", "18–21 сентября 2026"}) {
            var result = evaluator.evaluate(post("Мероприятие " + range), metadata(range, null));
            assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN, result.status());
            assertNull(result.expiresAt());
        }
    }

    @Test
    void unsupportedExtractedMonthCannotSetFreshness() {
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,
                evaluator.evaluate(post("Мероприятие 10 августа 2026"), metadata("10 июля 2026", null)).status());
    }

    private TelegramChannelPost post(String text) {
        var post = new TelegramChannelPost();
        post.setText(text);
        post.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00"));
        return post;
    }
    private TelegramChannelPostMetadata metadata(String event, String deadline) {
        var metadata = new TelegramChannelPostMetadata();
        metadata.setPostType(TelegramChannelPostType.EVENT);
        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
        metadata.setEventDateText(event);
        metadata.setDeadlineText(deadline);
        return metadata;
    }
}
