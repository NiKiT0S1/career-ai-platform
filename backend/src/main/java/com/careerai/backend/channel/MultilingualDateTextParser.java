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

    public MultilingualDateTextParser(MultilingualMonthDictionary monthDictionary) {
        this.monthDictionary = monthDictionary;
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
                    matcher.group()
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
                    matcher.group()
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
                matcher.group()
        );
    }

    private DateParseResult parseIsoDate(String text,  LocalDate referenceDate) {
        Matcher matcher = ISO_DATE_PATTERN.matcher(text);

        if (!matcher.find()) {
            return null;
        }

        return createResult(
                Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(1)),
                referenceDate,
                matcher.group()
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
            String originalText
    ) {
        int year = explicitYear == null
                ? referenceDate.getYear()
                : explicitYear;

        try {
            LocalDate date = LocalDate.of(year, month, day);

            if (explicitYear == null && date.isBefore(referenceDate.minusMonths(6))) {
                date = date.plusYears(1);
            }

            return DateParseResult.parsed(date);
        }
        catch (DateTimeException e) {
            return DateParseResult.invalid("Некорректная календарная дата: " + originalText);
        }
    }
}
