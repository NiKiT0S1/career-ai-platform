package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnswerCalendarGroundingTest {
    private final MultilingualDateTextParser parser = new MultilingualDateTextParser(new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector());
    private final AnswerCalendarGrounding guard = new AnswerCalendarGrounding(parser, Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneId.of("Asia/Almaty")));

    @Test void publicationMonthCannotReplaceEventMonth() {
        var post = post("10 августа состоится мероприятие. Начало в 12:00.");
        post.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00"));
        assertFalse(guard.supported("Мероприятие 10 июля 2026 года", List.of(post), List.of()));
        assertTrue(guard.supported("В тексте указано 10 августа, год не уточнён.", List.of(post), List.of()));
        assertFalse(guard.supported("Мероприятие 10 августа 2026 года", List.of(post), List.of()));
    }
    @ParameterizedTest
    @ValueSource(strings={"Deadline: September 7, 2026", "Срок: 7 сентября 2026", "Мерзімі: 7 қыркүйек 2026", "2026-09-07"})
    void explicitDateIsGroundedAcrossLanguages(String answer) {
        assertTrue(guard.supported(answer, List.of(post("До 7 сентября 2026 года")), List.of()));
    }
    @Test void fallbackKeepsLiteralDatesAndSourceLinkWithoutInventingYear() {
        String answer = guard.sourceDates("Когда мероприятие?", List.of(post("10 августа состоится мероприятие")));
        assertTrue(answer.contains("10 августа")); assertTrue(answer.contains("год в дате не указан"));
        assertTrue(answer.contains("https://t.me/career_channel/12")); assertFalse(answer.contains("2026"));
    }

    @ParameterizedTest
    @ValueSource(strings={"Мероприятие 10.07", "The event is on 10/07"})
    void shortNumericWrongMonthCannotBypassCalendarGuard(String answer) {
        assertFalse(guard.supported(answer,List.of(post("10 августа состоится мероприятие")),List.of()));
    }

    @Test void matchingShortDateIsAllowedButCannotInventYear() {
        var source=post("10 августа состоится мероприятие");
        assertTrue(guard.supported("Мероприятие 10.08, год не уточнён",List.of(source),List.of()));
        assertFalse(guard.supported("Мероприятие 10.08.2026",List.of(source),List.of()));
        assertTrue(guard.supported("Нужен Python 3.12. Начало в 12.00",List.of(source),List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings={"1 апреля следующего года", "April 1 next year", "келесі жылы 1 сәуір"})
    void fallbackPreservesRelativeYearFromPublicationContext(String source) {
        var entry=post(source);entry.setPostedAt(OffsetDateTime.parse("2026-07-10T10:00:00+05:00"));
        String answer=guard.sourceDates("Когда дедлайн?",List.of(entry));
        assertTrue(answer.contains("2027-04-01"),answer);
        assertFalse(answer.contains("год в дате не указан"),answer);
        assertTrue(answer.contains("год определён по тексту публикации"));
    }

    @Test void fallbackPreservesKazakhPrefixYear() {
        String answer=guard.sourceDates("Когда мероприятие?",List.of(post("2027 жылғы 10 тамыз")));
        assertTrue(answer.contains("2027-08-10"),answer);
    }

    @Test void ambiguousSlashFallbackDoesNotInventCalendarDate() {
        String answer=guard.sourceDates("Когда мероприятие?",List.of(post("Event 10/07 next year")));
        assertTrue(answer.contains("формат даты или год требуют уточнения"),answer);
        assertFalse(answer.contains("2027-07-10"));
    }

    @Test void groundedDeadlineCanExplainExpiryUsingClearlyLabelledCurrentDate() {
        var source=post("Срок сдачи документов продлён до 7 сентября 2026 года.");
        String answer="Срок сдачи документов продлили до 7 сентября 2026 года. "
                +"Так как текущая дата — 19 сентября 2026 года, этот срок уже прошёл.";
        assertTrue(guard.supported(answer,List.of(source),List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings={"Сегодня — 19 сентября 2026 года.","Today is September 19, 2026.",
            "The current date is 2026-09-19.","Today's date: September 19, 2026.",
            "Бүгінгі күн — 19 қыркүйек 2026 жыл.","Бүгін 19 қыркүйек 2026."})
    void explicitlyLabelledClockReferenceIsAllowedAcrossLanguages(String answer) {
        assertTrue(guard.supported(answer,List.of(post("Дедлайн 1 апреля")),List.of()),answer);
    }

    @ParameterizedTest
    @ValueSource(strings={"Сегодня — 20 сентября 2026 года.","The current date is September 19, 2027.",
            "Мероприятие сегодня 19 сентября 2026 года.","Today September 19, 2026 the event takes place.",
            "Дата мероприятия — 19 сентября 2026.","Today is September 19, 2026. The event is on September 19, 2026."})
    void currentDateExceptionDoesNotPermitWrongClockOrUnsupportedEventDate(String answer) {
        assertFalse(guard.supported(answer,List.of(post("Мероприятие 10 августа")),List.of()),answer);
    }

    @ParameterizedTest
    @ValueSource(strings={"The deadline is April 1. Today is September 19, 2026. The deadline has already passed.",
            "The deadline is April 1. It has already expired.",
            "Дедлайн 1 апреля. Срок уже истёк.","Дедлайн 1 апреля. Регистрация ещё открыта.",
            "The deadline is April 1 and applications are still open.","You can still apply before April 1.",
            "Мерзімі 1 сәуір. Өтінім қабылдау мерзімі өтіп кетті.",
            "The year is unknown, but the deadline has passed."})
    void unresolvedSourceYearCannotSupportExpiredOrOpenAssertions(String answer) {
        assertFalse(guard.supported(answer,List.of(post("Дедлайн 1 апреля")),List.of()),answer);
    }

    @ParameterizedTest
    @ValueSource(strings={"The source says April 1, but the year is unknown. I cannot confirm whether the deadline has passed.",
            "Указано 1 апреля. Нельзя подтвердить, что срок уже истёк.",
            "If the deadline has passed, contact the Career Center.",
            "Невозможно определить, открыта ли регистрация без подтверждённого года."})
    void uncertaintyAndConditionalAdviceAreNotMistakenForStatusClaims(String answer) {
        assertTrue(guard.supported(answer,List.of(post("Дедлайн 1 апреля")),List.of()),answer);
    }

    @Test void currentAdminConfirmationCanSupportStatusEvenWhenOriginalYearIsMissing() {
        var source=post("Дедлайн 1 апреля");source.setConfirmedDate(LocalDate.of(2026,4,1));
        source.setConfirmedDateBoundary(DateBoundaryType.INCLUSIVE);
        source.setConfirmedDatePurpose(ChannelPostDatePurpose.APPLICATION_DEADLINE);
        source.setConfirmedDateSourceHash(source.dateConfirmationSourceHash());
        assertTrue(guard.supported("The deadline was April 1, 2026. Today is September 19, 2026. The deadline has passed.",List.of(source),List.of()));
    }
    private TelegramChannelPost post(String text) {
        var post = new TelegramChannelPost(); post.setId(1L); post.setText(text);
        post.setChannelUsername("career_channel"); post.setTelegramMessageId(12L);
        return post;
    }
}
