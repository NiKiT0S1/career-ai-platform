package com.careerai.backend.channel;

import org.springframework.stereotype.Component;

import java.time.Month;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Содержит названия месяцев на языках,
 * используемых в Telegram-канале ЦКиТа.
 */

@Component
public class MultilingualMonthDictionary {

    private final Map<String, Month> months;

    public MultilingualMonthDictionary() {
        Map<String, Month> values = new HashMap<>();

        addRussianMonths(values);
        addKazakhMonths(values);
        addEnglishMonths(values);

        months = Map.copyOf(values);
    }

    public Month findMonth(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = normalize(value);

        Month directResult =
                months.get(normalized);

        if (directResult != null) {
            return directResult;
        }

        /*
         * Казахские названия месяцев могут встречаться
         * с падежными окончаниями:
         *
         * қыркүйекке, тамызда, қарашаға.
         */
        for (String suffix : KAZAKH_CASE_SUFFIXES) {
            if (!normalized.endsWith(suffix)) {
                continue;
            }

            String baseForm = normalized.substring(
                    0,
                    normalized.length() - suffix.length()
            );

            Month month =
                    months.get(baseForm);

            if (month != null) {
                return month;
            }
        }

        return null;
    }

    private void addRussianMonths(Map<String, Month> values) {
        add(values, Month.JANUARY, "январь", "января");
        add(values, Month.FEBRUARY, "февраль", "февраля");
        add(values, Month.MARCH, "март", "марта");
        add(values, Month.APRIL, "апрель", "апреля");
        add(values, Month.MAY, "май", "мая");
        add(values, Month.JUNE, "июнь", "июня");
        add(values, Month.JULY, "июль", "июля");
        add(values, Month.AUGUST, "август", "августа");
        add(values, Month.SEPTEMBER, "сентябрь", "сентября");
        add(values, Month.OCTOBER, "октябрь", "октября");
        add(values, Month.NOVEMBER, "ноябрь", "ноября");
        add(values, Month.DECEMBER, "декабрь", "декабря");
    }

    private void addKazakhMonths(
            Map<String, Month> values
    ) {
        add(values, Month.JANUARY, "қаңтар");
        add(values, Month.FEBRUARY, "ақпан");
        add(values, Month.MARCH, "наурыз");
        add(values, Month.APRIL, "сәуір");
        add(values, Month.MAY, "мамыр");
        add(values, Month.JUNE, "маусым");
        add(values, Month.JULY, "шілде");
        add(values, Month.AUGUST, "тамыз");
        add(values, Month.SEPTEMBER, "қыркүйек");
        add(values, Month.OCTOBER, "қазан");
        add(values, Month.NOVEMBER, "қараша");
        add(values, Month.DECEMBER, "желтоқсан");
    }

    private void addEnglishMonths(
            Map<String, Month> values
    ) {
        add(values, Month.JANUARY, "january", "jan");
        add(values, Month.FEBRUARY, "february", "feb");
        add(values, Month.MARCH, "march", "mar");
        add(values, Month.APRIL, "april", "apr");
        add(values, Month.MAY, "may");
        add(values, Month.JUNE, "june", "jun");
        add(values, Month.JULY, "july", "jul");
        add(values, Month.AUGUST, "august", "aug");
        add(values, Month.SEPTEMBER, "september", "sept", "sep");
        add(values, Month.OCTOBER, "october", "oct");
        add(values, Month.NOVEMBER, "november", "nov");
        add(values, Month.DECEMBER, "december", "dec");
    }

    private void add(Map<String, Month> values, Month month, String... aliases) {
        for (String alias : aliases) {
            values.put(normalize(alias), month);
        }
    }

    private String normalize(String value) {
        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(".", "");
    }

    private static final List<String> KAZAKH_CASE_SUFFIXES =
            List.of(
                    "ның", "нің",
                    "дың", "дің",
                    "тың", "тің",
                    "нан", "нен",
                    "дан", "ден",
                    "тан", "тен",
                    "ға", "ге",
                    "қа", "ке",
                    "да", "де",
                    "та", "те"
            );
}
