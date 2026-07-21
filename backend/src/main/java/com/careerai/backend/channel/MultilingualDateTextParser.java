package com.careerai.backend.channel;

import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлекает календарные даты из русского,
 * казахского и английского текста.
 */

@Component
public class MultilingualDateTextParser {

    /**
     * Число перед месяцем:
     * 23 июля, 23 шілде, 23 July.
     */
    private static final Pattern DAY_MONTH_PATTERN =
            Pattern.compile(
                    "(?iu)(?<!\\d)"
                            + "(\\d{1,2})"
                            + "(?:-?(?:го|ші|шы|th|st|nd|rd))?"
                            + "\\s+"
                            + "([\\p{L}.]+)"
                            + "(?:\\s*,?\\s*(\\d{4}))?"
            );

    /**
     * Месяц перед числом:
     * July 23, September 7 2026.
     */
    private static final Pattern MONTH_DAY_PATTERN =
            Pattern.compile(
                    "(?iu)([\\p{L}.]+)"
                            + "\\s+"
                            + "(\\d{1,2})"
                            + "(?:st|nd|rd|th)?"
                            + "(?:\\s*,?\\s*(\\d{4}))?"
            );

    /**
     * 23.07.2026, 23/07/2026, 23-07-2026.
     */
    private static final Pattern NUMERIC_DATE_PATTERN =
            Pattern.compile(
                    "(?<!\\d)"
                            + "(\\d{1,2})"
                            + "[./-]"
                            + "(\\d{1,2})"
                            + "[./-]"
                            + "(\\d{4})"
                            + "(?!\\d)"
            );

    /**
     * Международный ISO-формат: 2026-07-23.
     */
    private static final Pattern ISO_DATE_PATTERN =
            Pattern.compile(
                    "(?<!\\d)"
                            + "(\\d{4})"
                            + "-"
                            + "(\\d{1,2})"
                            + "-"
                            + "(\\d{1,2})"
                            + "(?!\\d)"
            );

    private final MultilingualMonthDictionary monthDictionary;
    private final MultilingualDateBoundaryDetector boundaryDetector;

    public MultilingualDateTextParser(
            MultilingualMonthDictionary monthDictionary,
            MultilingualDateBoundaryDetector boundaryDetector
    ) {
        this.monthDictionary = monthDictionary;
        this.boundaryDetector = boundaryDetector;
    }

    public DateParseResult parse(String text, LocalDate referenceDate) {
        Objects.requireNonNull(referenceDate, "referenceDate must not be null");

        if (text == null || text.isBlank()) {
            return DateParseResult.unknown("Текст с датой отсутствует");
        }

        DateParseResult result = parseIsoDate(text, referenceDate);

        if (result != null) {
            return result;
        }

        result = parseNumericDate(text, referenceDate);

        if (result != null) {
            return result;
        }

        result = parseDayMonthDate(text, referenceDate);

        if (result != null) {
            return result;
        }

        result = parseMonthDayDate(text, referenceDate);

        if (result != null) {
            return result;
        }

        return DateParseResult.unknown("Точная календарная дата не найдена");
    }

    /**
     * Ищет в исходном тексте конкретную календарную дату
     * и возвращает границу, указанную рядом с ней.
     *
     * Метод нужен, когда LLM корректно извлекла дату,
     * но потеряла слова "до", "по" или "включительно".
     */
    public DateBoundaryType findBoundaryForDate(
            String text,
            LocalDate targetDate,
            LocalDate referenceDate
    ) {
        Objects.requireNonNull(targetDate, "targetDate must not be null");
        Objects.requireNonNull(referenceDate, "referenceDate must not be null");

        if (text == null || text.isBlank()) {
            return DateBoundaryType.UNSPECIFIED;
        }

        DateBoundaryType boundary = findBoundaryInIsoDates(text, targetDate, referenceDate);

        if (boundary != DateBoundaryType.UNSPECIFIED) {
            return boundary;
        }

        boundary = findBoundaryInNumericDates(text, targetDate, referenceDate);

        if (boundary != DateBoundaryType.UNSPECIFIED) {
            return boundary;
        }

        boundary = findBoundaryInDayMonthDates(text, targetDate, referenceDate);

        if (boundary != DateBoundaryType.UNSPECIFIED) {
            return boundary;
        }

        return findBoundaryInMonthDayDates(text, targetDate, referenceDate);
    }

    private DateBoundaryType findBoundaryInIsoDates(
            String text,
            LocalDate targetDate,
            LocalDate referenceDate
    ) {
        Matcher matcher = ISO_DATE_PATTERN.matcher(text);

        while (matcher.find()) {
            LocalDate candidateDate = resolveDate(
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1)),
                    referenceDate
            );

            if (targetDate.equals(candidateDate)) {
                return boundaryDetector.detect(text, matcher.start(), matcher.end());
            }
        }

        return DateBoundaryType.UNSPECIFIED;
    }

    private DateBoundaryType findBoundaryInNumericDates(
            String text,
            LocalDate targetDate,
            LocalDate referenceDate
    ) {
        Matcher matcher = NUMERIC_DATE_PATTERN.matcher(text);

        while (matcher.find()) {
            LocalDate candidateDate = resolveDate(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    referenceDate
            );

            if (targetDate.equals(candidateDate)) {
                return boundaryDetector.detect(text, matcher.start(), matcher.end());
            }
        }

        return DateBoundaryType.UNSPECIFIED;
    }

    private DateBoundaryType findBoundaryInDayMonthDates(
            String text,
            LocalDate targetDate,
            LocalDate referenceDate
    ) {
        Matcher matcher = DAY_MONTH_PATTERN.matcher(text);

        while (matcher.find()) {
            Month month = monthDictionary.findMonth(matcher.group(2));

            if (month == null) {
                continue;
            }

            LocalDate candidateDate = resolveDate(
                    Integer.parseInt(matcher.group(1)),
                    month.getValue(),
                    parseYear(matcher.group(3)),
                    referenceDate
            );

            if (targetDate.equals(candidateDate)) {
                return boundaryDetector.detect(text, matcher.start(), matcher.end());
            }
        }

        return DateBoundaryType.UNSPECIFIED;
    }

    private DateBoundaryType findBoundaryInMonthDayDates(
            String text,
            LocalDate targetDate,
            LocalDate referenceDate
    ) {
        Matcher matcher = MONTH_DAY_PATTERN.matcher(text);

        while (matcher.find()) {
            Month month = monthDictionary.findMonth(matcher.group(1));

            if (month == null) {
                continue;
            }

            LocalDate candidateDate = resolveDate(
                    Integer.parseInt(matcher.group(2)),
                    month.getValue(),
                    parseYear(matcher.group(3)),
                    referenceDate
            );

            if (targetDate.equals(candidateDate)) {
                return boundaryDetector.detect(text, matcher.start(), matcher.end());
            }
        }

        return DateBoundaryType.UNSPECIFIED;
    }

    private DateParseResult parseDayMonthDate(String text, LocalDate referenceDate) {
        Matcher matcher = DAY_MONTH_PATTERN.matcher(text);

        while (matcher.find()) {
            Month month = monthDictionary.findMonth(matcher.group(2));

            if (month == null) {
                continue;
            }

            return createResult(
                    Integer.parseInt(matcher.group(1)),
                    month.getValue(),
                    parseYear(matcher.group(3)),
                    referenceDate,
                    matcher.group(),
                    boundaryDetector.detect(text, matcher.start(), matcher.end())
            );
        }

        return null;
    }

    private DateParseResult parseMonthDayDate(String text, LocalDate referenceDate) {
        Matcher matcher = MONTH_DAY_PATTERN.matcher(text);

        while (matcher.find()) {
            Month month = monthDictionary.findMonth(matcher.group(1));

            if (month == null) {
                continue;
            }

            return createResult(
                    Integer.parseInt(matcher.group(2)),
                    month.getValue(),
                    parseYear(matcher.group(3)),
                    referenceDate,
                    matcher.group(),
                    boundaryDetector.detect(text, matcher.start(), matcher.end())
            );
        }

        return null;
    }

    private DateParseResult parseNumericDate(String text, LocalDate referenceDate) {
        Matcher matcher = NUMERIC_DATE_PATTERN.matcher(text);

        if (!matcher.find()) {
            return null;
        }

        return createResult(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                referenceDate,
                matcher.group(),
                boundaryDetector.detect(text, matcher.start(), matcher.end())
        );
    }

    private DateParseResult parseIsoDate(String text, LocalDate referenceDate) {
        Matcher matcher = ISO_DATE_PATTERN.matcher(text);

        if (!matcher.find()) {
            return null;
        }

        return createResult(
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(1)),
                referenceDate,
                matcher.group(),
                boundaryDetector.detect(text, matcher.start(), matcher.end())
        );
    }

    private Integer parseYear(String value) {
        return value == null || value.isBlank()
                ? null
                : Integer.parseInt(value);
    }

    private DateParseResult createResult(
            int day,
            int month,
            Integer explicitYear,
            LocalDate referenceDate,
            String originalText,
            DateBoundaryType boundaryType
    ) {
        LocalDate date = resolveDate(day, month, explicitYear, referenceDate);

        if (date == null) {
            return DateParseResult.invalid(
                    "Некорректная календарная дата: " + originalText
            );
        }

        return DateParseResult.parsed(date, boundaryType);
    }

    private LocalDate resolveDate(
            int day,
            int month,
            Integer explicitYear,
            LocalDate referenceDate
    ) {
        int year = explicitYear == null
                ? referenceDate.getYear()
                : explicitYear;

        try {
            LocalDate date = LocalDate.of(year, month, day);

            if (explicitYear == null && date.isBefore(referenceDate.minusMonths(6))) {
                date = date.plusYears(1);
            }

            return date;
        }
        catch (DateTimeException exception) {
            return null;
        }
    }
}
