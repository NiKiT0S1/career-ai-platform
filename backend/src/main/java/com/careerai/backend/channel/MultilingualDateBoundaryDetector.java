package com.careerai.backend.channel;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Определяет смысл текста вокруг найденной даты.
 *
 * Не извлекает саму дату, а только устанавливает,
 * включается ли указанный день в период действия.
 */

@Component
public class MultilingualDateBoundaryDetector {

    private static final int CONTEXT_LENGTH = 80;

    /*
     * Сильные признаки включительной границы
     * перед датой.
     */
    private static final Pattern INCLUSIVE_BEFORE_PATTERN = Pattern.compile(
            "(?iu).*(?:^|\\s)(?:"
                    + "по|"
                    + "до\\s+конца|"
                    + "не\\s+позднее|"
                    + "through|"
                    + "by|"
                    + "no\\s+later\\s+than|"
                    + "кешіктірмей"
                    + ")\\s*$"
    );

    /*
     * Сильные признаки включительной границы
     * после даты.
     */
    private static final Pattern INCLUSIVE_AFTER_PATTERN = Pattern.compile(
            "(?iu)^[\\s(\\[]*(?:"
                    + "включительно|"
                    + "вкл\\.?|"
                    + "inclusive|"
                    + "including|"
                    + "қоса(?:\\s+алғанда)?|"
                    + "дейін\\s+қоса(?:\\s+алғанда)?"
                    + ")(?:\\s|[.,;:!?)]|$).*"
    );

    /*
     * Признаки исключительной границы
     * перед датой.
     */
    private static final Pattern EXCLUSIVE_BEFORE_PATTERN = Pattern.compile(
            "(?iu).*(?:^|\\s)(?:до|before)\\s*$"
    );

    /*
     * Казахская конструкция обычно располагается
     * после даты: "21 шілдеге дейін".
     */
    private static final Pattern EXCLUSIVE_AFTER_PATTERN = Pattern.compile(
            "(?iu)^[\\s(\\[]*(?:дейін|дейінгі)(?:\\s|[.,;:!?)]|$).*"
    );

    public DateBoundaryType detect(String text, int matchStart, int matchEnd) {
        if (text == null
                || matchStart < 0
                || matchEnd < matchStart
                || matchEnd > text.length()) {
            return DateBoundaryType.UNSPECIFIED;
        }

        String before = normalize(text.substring(
                Math.max(0, matchStart - CONTEXT_LENGTH),
                matchStart
        ));

        String after = normalize(text.substring(
                matchEnd,
                Math.min(text.length(), matchEnd + CONTEXT_LENGTH)
        ));

        /*
         * Явная включительность имеет приоритет.
         *
         * Благодаря этому "до 21 июля включительно"
         * не будет ошибочно считаться исключительной датой.
         */
        if (INCLUSIVE_BEFORE_PATTERN.matcher(before).matches()
                || INCLUSIVE_AFTER_PATTERN.matcher(after).matches()) {
            return DateBoundaryType.INCLUSIVE;
        }

        if (EXCLUSIVE_BEFORE_PATTERN.matcher(before).matches()
                || EXCLUSIVE_AFTER_PATTERN.matcher(after).matches()) {
            return DateBoundaryType.EXCLUSIVE;
        }

        return DateBoundaryType.UNSPECIFIED;
    }

    private String normalize(String value) {
        return value
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
    }
}