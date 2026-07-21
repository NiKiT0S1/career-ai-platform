package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultilingualDateTextParserTest {

    private final MultilingualDateTextParser parser = new MultilingualDateTextParser(
            new MultilingualMonthDictionary(),
            new MultilingualDateBoundaryDetector()
    );

    private final LocalDate referenceDate = LocalDate.of(2026, 7, 21);

    @Test
    void treatsRussianDoAsExclusive() {
        DateParseResult result = parser.parse("Дедлайн: до 21 июля", referenceDate);

        assertEquals(DateParseStatus.PARSED, result.status());
        assertEquals(LocalDate.of(2026, 7, 21), result.date());
        assertEquals(DateBoundaryType.EXCLUSIVE, result.boundaryType());
    }

    @Test
    void treatsBareDateAsUnspecified() {
        DateParseResult result = parser.parse("Дедлайн: 21 июля", referenceDate);

        assertEquals(DateParseStatus.PARSED, result.status());
        assertEquals(DateBoundaryType.UNSPECIFIED, result.boundaryType());
    }

    @Test
    void treatsRussianPoAsInclusive() {
        DateParseResult result = parser.parse("Приём заявок по 21 июля", referenceDate);

        assertEquals(DateBoundaryType.INCLUSIVE, result.boundaryType());
    }

    @Test
    void explicitInclusivityOverridesDo() {
        DateParseResult result = parser.parse("Приём заявок до 21 июля включительно", referenceDate);

        assertEquals(DateBoundaryType.INCLUSIVE, result.boundaryType());
    }

    @Test
    void treatsDoKoncaAsInclusive() {
        DateParseResult result = parser.parse("Приём заявок до конца 21 июля", referenceDate);

        assertEquals(DateBoundaryType.INCLUSIVE, result.boundaryType());
    }

    @Test
    void supportsKazakhExclusiveBoundary() {
        DateParseResult result = parser.parse("Өтінімдер 21 шілдеге дейін қабылданады", referenceDate);

        assertEquals(LocalDate.of(2026, 7, 21), result.date());
        assertEquals(DateBoundaryType.EXCLUSIVE, result.boundaryType());
    }

    @Test
    void supportsEnglishExclusiveBoundary() {
        DateParseResult result = parser.parse("Apply before July 21", referenceDate);

        assertEquals(LocalDate.of(2026, 7, 21), result.date());
        assertEquals(DateBoundaryType.EXCLUSIVE, result.boundaryType());
    }

    @Test
    void supportsEnglishInclusiveBoundary() {
        DateParseResult result = parser.parse("Applications accepted through July 21", referenceDate);

        assertEquals(DateBoundaryType.INCLUSIVE, result.boundaryType());
    }

    @Test
    void findsBoundaryOfSpecificDateWhenPostContainsSeveralDates() {
        String text = """
            Обучение начнётся 10 сентября.
            Подать заявку необходимо до 21 июля.
            """;

        DateBoundaryType result = parser.findBoundaryForDate(
                text,
                LocalDate.of(2026, 7, 21),
                referenceDate
        );

        assertEquals(DateBoundaryType.EXCLUSIVE, result);
    }
}