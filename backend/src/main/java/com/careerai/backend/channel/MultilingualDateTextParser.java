package com.careerai.backend.channel;

import org.springframework.stereotype.Component;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Calendar dates grounded in the supplied text. Publication time is not a deadline year. */
@Component
public class MultilingualDateTextParser {
    private static final Pattern DAY_MONTH = Pattern.compile("(?iu)(?<!\\d)(\\d{1,2})(?:-?(?:го|ші|шы|th|st|nd|rd))?\\s+([\\p{L}.]+)(?:\\s*,?\\s*(\\d{4}))?");
    private static final Pattern MONTH_DAY = Pattern.compile("(?iu)([\\p{L}.]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?!\\d)(?:\\s*,?\\s*(\\d{4}))?");
    private static final Pattern NUMERIC = Pattern.compile("(?<![\\d-])(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})(?!\\d)");
    private static final Pattern SHORT_NUMERIC = Pattern.compile("(?<![\\p{L}\\d./-])(\\d{1,2})([./])(\\d{1,2})(?![\\p{L}\\d/]|[.-]\\d)");
    private static final Pattern NON_DATE_PREFIX = Pattern.compile("(?iu)(?:java|jdk|jre|python|node(?:\\.js)?|spring(?:\\s+boot)?|boot|postgres(?:ql)?|npm|react|version|ver\\.?|верси[яию]|release|релиз|v|время|time|сағат)[:\\s]*$");
    private static final Pattern TIME_SUFFIX = Pattern.compile("(?iu)^\\s*(?:час(?:ов|а)?|ч\\.|am|pm|сағат)(?:\\s|[.,;!?]|$)");
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern CURRENT_YEAR = Pattern.compile("(?iu)(?:этого(?:\\s+или\\s+следующего)?|текущего|в\\s+этом|в\\s+текущем)\\s+год[ау]?|this\\s+year|биыл(?:ғы)?|осы\\s+жыл(?:ы|ғы)?");
    private static final Pattern NEXT_YEAR = Pattern.compile("(?iu)(?:следующего|в\\s+следующем)\\s+год[ау]?|next\\s+year|келесі\\s+жыл(?:ы|ғы)?");
    private static final Pattern PREVIOUS_YEAR = Pattern.compile("(?iu)(?:прошлого|в\\s+прошлом)\\s+год[ау]?|last\\s+year|өткен\\s+жыл(?:ы|ғы)?");
    private static final Pattern PREFIX_YEAR = Pattern.compile("(?iu)(?<!\\d)(\\d{4})\\s+(?:жылғы|жылдың|жылы|года|году|г\\.)\\s*$");
    private final MultilingualMonthDictionary months;
    private final MultilingualDateBoundaryDetector boundaries;

    public MultilingualDateTextParser(MultilingualMonthDictionary months, MultilingualDateBoundaryDetector boundaries) {
        this.months = months;
        this.boundaries = boundaries;
    }

    public DateParseResult parse(String text, LocalDate referenceDate) {
        Objects.requireNonNull(referenceDate, "referenceDate must not be null");
        List<Candidate> dates = candidates(text);
        return dates.isEmpty() ? DateParseResult.unknown("Точная календарная дата не найдена")
                : result(text, dates.getFirst(), referenceDate);
    }

    public record DateMention(int day,int month,Integer year,String text) {}

    /** All source calendar mentions, without filling in a missing year. */
    public List<DateMention> calendarMentions(String text) {
        return candidates(text).stream().map(candidate -> new DateMention(candidate.day,candidate.month,
                explicitYear(text,candidate),text.substring(candidate.start,candidate.end))).toList();
    }

    /** A metadata hint selects a day/month, but only the original source supplies its year and boundary. */
    public DateParseResult parseMatchingDate(String source, String hint, LocalDate referenceDate) {
        Objects.requireNonNull(referenceDate, "referenceDate must not be null");
        List<Candidate> hints = candidates(hint);
        if (hints.isEmpty()) return parse(source, referenceDate);
        Candidate wanted = hints.getFirst();
        List<Candidate> matches = candidates(source).stream()
                .filter(candidate -> candidate.day == wanted.day && candidate.month == wanted.month).toList();
        if (matches.isEmpty()) return DateParseResult.unknown("Извлечённая дата не подтверждается исходным текстом; требуется проверка администратора");
        DateParseResult first = result(source, matches.getFirst(), referenceDate);
        for (Candidate match : matches) {
            DateParseResult other = result(source, match, referenceDate);
            if (!Objects.equals(first.date(), other.date()) || first.status() != other.status()
                    || first.boundaryType() != other.boundaryType()) {
                return DateParseResult.unknown("В исходном тексте несколько противоречивых дат; требуется проверка администратора");
            }
        }
        return first;
    }

    public DateBoundaryType findBoundaryForDate(String text, LocalDate targetDate, LocalDate referenceDate) {
        Objects.requireNonNull(targetDate, "targetDate must not be null");
        Objects.requireNonNull(referenceDate, "referenceDate must not be null");
        for (Candidate candidate : candidates(text)) {
            if (candidate.day == targetDate.getDayOfMonth() && candidate.month == targetDate.getMonthValue()
                    && (candidate.year == null || candidate.year == targetDate.getYear())) {
                return boundaries.detect(text, candidate.start, candidate.end);
            }
        }
        return DateBoundaryType.UNSPECIFIED;
    }

    private DateParseResult result(String text, Candidate candidate, LocalDate referenceDate) {
        if (candidate.ambiguousSlash) return DateParseResult.unknown(
                "Формат даты «" + text.substring(candidate.start,candidate.end)
                        + "» неоднозначен: день/месяц или месяц/день; требуется уточнение");
        Integer year = explicitYear(text,candidate);
        if (year == null) year = contextualYear(text, candidate, referenceDate);
        if (year == null) {
            if (date(2000, candidate.month, candidate.day) == null) return DateParseResult.invalid("Некорректная календарная дата: " + text.substring(candidate.start, candidate.end));
            return DateParseResult.unknown("Год даты «" + text.substring(candidate.start, candidate.end)
                    + "» не указан однозначно; требуется подтверждение администратора");
        }
        LocalDate resolved = date(year, candidate.month, candidate.day);
        return resolved == null ? DateParseResult.invalid("Некорректная календарная дата: " + text.substring(candidate.start, candidate.end))
                : DateParseResult.parsed(resolved, boundaries.detect(text, candidate.start, candidate.end));
    }

    private Integer explicitYear(String text,Candidate candidate) {
        if (candidate.year != null) return candidate.year;
        var prefix = PREFIX_YEAR.matcher(text.substring(Math.max(0, candidate.start - 25), candidate.start));
        return prefix.find() ? Integer.parseInt(prefix.group(1)) : null;
    }

    private Integer contextualYear(String text, Candidate candidate, LocalDate referenceDate) {
        int start = candidate.start, end = candidate.end;
        while (start > 0 && candidate.start - start < 40 && !"\n.;!?".contains(String.valueOf(text.charAt(start - 1)))) start--;
        // The month token may already contain a trailing dot ("апреля."). Do not
        // skip that sentence boundary and borrow "next year" from the next sentence.
        if (text.charAt(candidate.end - 1) != '.') {
            while (end < text.length() && end - candidate.end < 40 && !"\n.;!?".contains(String.valueOf(text.charAt(end)))) end++;
        }
        String context = text.substring(start, end);
        if (candidates(context).size() != 1) return null;
        boolean current = CURRENT_YEAR.matcher(context).find();
        boolean next = NEXT_YEAR.matcher(context).find();
        boolean previous = PREVIOUS_YEAR.matcher(context).find();
        if ((current ? 1 : 0) + (next ? 1 : 0) + (previous ? 1 : 0) != 1) return null;
        return referenceDate.getYear() + (next ? 1 : previous ? -1 : 0);
    }

    private List<Candidate> candidates(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Candidate> result = new ArrayList<>();
        var iso = ISO.matcher(text);
        while (iso.find()) result.add(new Candidate(iso.start(), iso.end(), Integer.parseInt(iso.group(3)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(1))));
        var numeric = NUMERIC.matcher(text);
        while (numeric.find()) {
            int day=Integer.parseInt(numeric.group(1)),month=Integer.parseInt(numeric.group(2));
            boolean slash=numeric.group().contains("/");
            boolean ambiguous=slash&&day<=12&&month<=12&&day!=month;
            if(slash&&month>12&&day<=12){int swap=day;day=month;month=swap;}
            result.add(new Candidate(numeric.start(),numeric.end(),day,month,Integer.parseInt(numeric.group(3)),ambiguous));
        }
        var shortNumeric = SHORT_NUMERIC.matcher(text);
        while(shortNumeric.find()) {
            int day=Integer.parseInt(shortNumeric.group(1)),month=Integer.parseInt(shortNumeric.group(3));
            boolean slash=shortNumeric.group(2).equals("/");
            boolean ambiguous=slash&&day<=12&&month<=12&&day!=month;
            if(slash&&month>12&&day<=12){int swap=day;day=month;month=swap;}
            if(day<1||day>31||month<1||month>12)continue;
            String before=text.substring(Math.max(0,shortNumeric.start()-30),shortNumeric.start());
            String after=text.substring(shortNumeric.end());
            if(NON_DATE_PREFIX.matcher(before).find()||TIME_SUFFIX.matcher(after).find())continue;
            result.add(new Candidate(shortNumeric.start(),shortNumeric.end(),day,month,null,ambiguous));
        }
        var dayMonth = DAY_MONTH.matcher(text);
        while (dayMonth.find()) {
            Month month = months.findMonth(dayMonth.group(2));
            if (month != null) result.add(new Candidate(dayMonth.start(), dayMonth.end(), Integer.parseInt(dayMonth.group(1)), month.getValue(), year(dayMonth.group(3))));
        }
        var monthDay = MONTH_DAY.matcher(text);
        while (monthDay.find()) {
            Month month = months.findMonth(monthDay.group(1));
            if (month != null) result.add(new Candidate(monthDay.start(), monthDay.end(), Integer.parseInt(monthDay.group(2)), month.getValue(), year(monthDay.group(3))));
        }
        result.sort(Comparator.comparingInt(Candidate::start));
        return result;
    }

    private static Integer year(String text) { return text == null ? null : Integer.valueOf(text); }
    private static LocalDate date(int year, int month, int day) {
        try { return LocalDate.of(year, month, day); }
        catch (DateTimeException invalid) { return null; }
    }
    private record Candidate(int start, int end, int day, int month, Integer year,boolean ambiguousSlash) {
        private Candidate(int start,int end,int day,int month,Integer year) {this(start,end,day,month,year,false);}
    }
}
