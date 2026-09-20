package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class MultilingualDateTextParserTest {
    private final MultilingualDateTextParser parser = new MultilingualDateTextParser(
            new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector());
    private final LocalDate referenceDate = LocalDate.of(2026, 7, 9);

    @ParameterizedTest
    @ValueSource(strings={"1 апреля", "Дедлайн до 7 сентября", "January 1", "31 December", "10 тамыз", "29 февраля"})
    void missingYearIsNeverGuessed(String text) {
        var result = parser.parse(text, referenceDate);
        assertEquals(DateParseStatus.UNKNOWN,result.status());
        assertNull(result.date());
        assertTrue(result.reason().contains("Год"));
    }

    @ParameterizedTest
    @CsvSource(delimiter='|',value={"до 7 сентября 2026|UNSPECIFIED", "21 шілдеге 2026 дейін|UNSPECIFIED",
            "строго до 21 июля 2026|EXCLUSIVE", "Apply before July 21 2026|EXCLUSIVE",
            "до 21 июля 2026 включительно|INCLUSIVE", "по 21 июля 2026|INCLUSIVE",
            "до конца 21 июля 2026|INCLUSIVE", "through July 21 2026|INCLUSIVE", "21 июля 2026|UNSPECIFIED"})
    void distinguishesExplicitBoundariesFromAmbiguousUntil(String text,DateBoundaryType boundary) {
        var result=parser.parse(text,referenceDate);
        assertEquals(DateParseStatus.PARSED,result.status());
        assertEquals(boundary,result.boundaryType());
    }

    @ParameterizedTest
    @CsvSource(delimiter='|',value={"1 апреля 2027|2027-04-01", "2025-04-01|2025-04-01", "01.04.2026|2026-04-01",
            "2026 жылғы 10 тамыз|2026-08-10", "1 апреля этого года|2026-04-01", "April 1 next year|2027-04-01",
            "келесі жылы 10 тамыз|2027-08-10", "биылғы 10 тамыз|2026-08-10", "April 1 last year|2025-04-01"})
    void usesOnlyExplicitYearOrUnambiguousRelativeYear(String text,LocalDate expected) {
        assertEquals(expected,parser.parse(text,referenceDate).date());
    }

    @Test
    void decemberJanuaryDoesNotTriggerAutomaticRollover() {
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("1 января",LocalDate.of(2026,12,20)).status());
        assertEquals(LocalDate.of(2027,1,1),parser.parse("1 января следующего года",LocalDate.of(2026,12,20)).date());
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("31 декабря",LocalDate.of(2027,1,2)).status());
    }

    @Test
    void explicitYearWinsOverRelativePhrase() {
        assertEquals(LocalDate.of(2028,4,1),parser.parse("1 апреля 2028 следующего года",referenceDate).date());
    }

    @Test
    void conflictingRelativeYearsRemainUnknown() {
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("1 апреля этого или следующего года",referenceDate).status());
    }

    @Test
    void invalidDayIsRejectedWithoutGuessingAYear() {
        assertEquals(DateParseStatus.INVALID,parser.parse("31 февраля",referenceDate).status());
        assertEquals(DateParseStatus.INVALID,parser.parse("29 февраля 2026",referenceDate).status());
    }

    @Test
    void metadataCannotInventYearOrBoundary() {
        var result=parser.parseMatchingDate("Приём документов до 7 сентября", "7 сентября 2026 включительно", referenceDate);
        assertEquals(DateParseStatus.UNKNOWN,result.status());
        var grounded=parser.parseMatchingDate("Приём документов до 7 сентября 2026", "7 сентября 2027", referenceDate);
        assertEquals(LocalDate.of(2026,9,7),grounded.date());
        assertEquals(DateBoundaryType.UNSPECIFIED,grounded.boundaryType());
    }

    @Test
    void metadataDateAbsentFromSourceIsNotAccepted() {
        assertEquals(DateParseStatus.UNKNOWN,parser.parseMatchingDate("10 августа 2026 состоится мероприятие", "10 июля 2026",referenceDate).status());
    }

    @Test
    void selectedDeadlineDoesNotBorrowPracticeStartDate() {
        var result=parser.parseMatchingDate("Практика с 10 сентября 2026. Подать документы по 7 сентября 2026.","7 сентября",referenceDate);
        assertEquals(LocalDate.of(2026,9,7),result.date());
        assertEquals(DateBoundaryType.INCLUSIVE,result.boundaryType());
    }

    @ParameterizedTest
    @ValueSource(strings={"1 апреля. В следующем году будет другое мероприятие",
            "April 1. Next year there will be another event", "10 тамыз. Келесі жылы басқа іс-шара",
            "1 апреля! В следующем году будет другое мероприятие",
            "1 апреля\nВ следующем году будет другое мероприятие"})
    void neverBorrowsYearFromAnotherSentence(String source) {
        assertEquals(DateParseStatus.UNKNOWN,parser.parse(source,referenceDate).status());
    }

    @Test
    void exposesAllCalendarMentionsWithoutInventedYearsOrDuplicateYearFragments() {
        var mentions=parser.calendarMentions("До 7 сентября. Event August 10 2026; 2027-04-01; 2026 жылғы 10 тамыз.");
        assertEquals(4,mentions.size());assertNull(mentions.get(0).year());
        assertEquals(new MultilingualDateTextParser.DateMention(10,8,2026,"August 10 2026"),mentions.get(1));
        assertEquals(new MultilingualDateTextParser.DateMention(1,4,2027,"2027-04-01"),mentions.get(2));
        assertEquals(2026,mentions.get(3).year());
    }

    @Test
    void shortNumericDatesAreDetectedWithoutInventingYear() {
        var mentions=parser.calendarMentions("Мероприятие 10.07, срок 07.09.");
        assertEquals(2,mentions.size());
        assertEquals(new MultilingualDateTextParser.DateMention(10,7,null,"10.07"),mentions.getFirst());
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("10.07",referenceDate).status());
        assertEquals(LocalDate.of(2026,7,10),parser.parse("10.07 этого года",referenceDate).date());
    }

    @ParameterizedTest
    @ValueSource(strings={"12.00", "Java 21.0", "Python 3.12", "v3.12", "Node.js 20.11", "12.05 часов"})
    void shortNumericDoesNotTreatTimesAndVersionsAsDates(String text) {
        assertTrue(parser.calendarMentions(text).isEmpty(),text);
    }

    @Test
    void ambiguousStartMayBeADateAndNeedsClarification() {
        assertEquals(1,parser.calendarMentions("Начало: 12.05").size());
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("Начало: 12.05",referenceDate).status());
    }

    @Test
    void fullNumericDateIsNotDuplicatedAsShortNumericMention() {
        assertEquals(1,parser.calendarMentions("10.07.2026").size());
        assertEquals(1,parser.calendarMentions("2026-07-10").size());
    }

    @Test
    void ambiguousSlashNeverChoosesEnglishMonthDayConventionSilently() {
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("10/07",referenceDate).status());
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("10/07 next year",referenceDate).status());
        assertEquals(DateParseStatus.UNKNOWN,parser.parse("10/07/2027",referenceDate).status());
        assertFalse(parser.calendarMentions("10/07").isEmpty());
        assertEquals(LocalDate.of(2027,4,13),parser.parse("04/13/2027",referenceDate).date());
        assertEquals(LocalDate.of(2027,4,13),parser.parse("13/04/2027",referenceDate).date());
    }
}
