package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EventTemporalEvidenceServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneId.of("Asia/Almaty"));
    private final EventTemporalEvidenceService service = new EventTemporalEvidenceService(
            mock(TelegramChannelPostMetadataRepository.class),
            new MultilingualDateTextParser(new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector()), clock);

    @Test
    void distinguishesPublicationEditEventAndRegistrationDates() {
        var post = post("Регистрация до 5 августа 2026. Мероприятие состоится 10 августа 2026.");
        post.setEditedAt(OffsetDateTime.parse("2026-07-15T10:00:00+05:00"));
        var metadata = metadata("10 августа 2026", "до 5 августа 2026");
        var result = service.inspect(post, metadata);
        assertEquals(LocalDate.of(2026, 8, 10), result.eventDate().date());
        assertEquals(LocalDate.of(2026, 8, 5), result.deadlineDate().date());
        assertTrue(result.eventHasPassed(LocalDate.of(2026, 9, 19)));
    }

    @Test
    void rejectsHallucinatedPublicationMonthAsEventDate() {
        var result = service.inspect(post("10 августа 2026 состоится мероприятие"), metadata("10 июля 2026", null));
        assertEquals(DateParseStatus.UNKNOWN, result.eventDate().status());
        assertNull(result.eventDateText());
        assertFalse(result.eventDateConfirmed());
    }

    @Test
    void missingYearCannotBecomeConfirmedFutureEvent() {
        var result = service.inspect(post("10 августа состоится мероприятие"), metadata("10 августа", null));
        assertEquals(DateParseStatus.UNKNOWN, result.eventDate().status());
        assertFalse(result.eventDateConfirmed());
    }

    @Test
    void legacyRegistrationDeadlineDoesNotBecomeEventDate() {
        var result = service.inspect(post("Регистрация до 5 августа 2026. Мероприятие пройдёт позже."),
                metadata(null, "до 5 августа 2026"));
        assertEquals(DateParseStatus.UNKNOWN, result.eventDate().status());
        assertEquals(LocalDate.of(2026, 8, 5), result.deadlineDate().date());
    }

    @Test
    void timelessReplyCannotUseItsPublicationTimestampAsEventDate() {
        var result = service.inspect(post("Начало в 12:00, конец в 15:00, Open Space"), metadata(null, null));
        assertEquals(DateParseStatus.UNKNOWN, result.eventDate().status());
    }

    @Test
    void ignoresPendingMetadataAfterTelegramEdit() {
        var metadata = metadata("10 августа 2026", null);
        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.PENDING);
        var result = service.inspect(post("Мероприятие перенесено на 15 ноября 2026"), metadata);
        assertFalse(result.eventDateConfirmed());
        assertNull(result.eventDateText());
    }

    @Test
    void multilingualEventQuotesRetainSourceDate() {
        for (String quote : new String[]{"10 August 2026", "2026 жылғы 10 тамыз", "10 тамыз 2026"}) {
            // ISO in the Kazakh sentence is not needed: the parser retains the explicit source year.
            var result = service.inspect(post(quote), metadata(quote, null));
            assertEquals(LocalDate.of(2026, 8, 10), result.eventDate().date(), quote);
        }
    }

    private TelegramChannelPost post(String text) {
        var post = new TelegramChannelPost();
        post.setId(1L);
        post.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00"));
        post.setText(text);
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
