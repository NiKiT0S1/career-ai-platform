package com.careerai.backend.channel;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/** High-confidence semantic constraints applied after the probabilistic router. */
final class ChannelQueryPolicy {
    private ChannelQueryPolicy() { }
    private static final Pattern EVENTS = words("ивент\\p{L}*|мероприяти\\p{L}*|мастер\\s*класс\\p{L}*|events?|evnts?|workshops?|іс\\s*шара\\p{L}*");
    private static final Pattern PRACTICE = words("практик\\p{L}*|практику|практике|өндірістік|practice|practicum");
    private static final Pattern VACANCIES = words("ваканси\\p{L}*|jobs?|vacanc\\p{L}*|internships?|тағылымдама\\p{L}*");
    private static final Pattern DEADLINE = words("дедла[йи]н\\p{L}*|срок\\p{L}*|сроки|deadline\\p{L}*|мерзім\\p{L}*|мерзим\\p{L}*");
    private static final Pattern DEADLINE_QUESTION = Pattern.compile("(?iu)(до\\s+какого|когда.{0,25}(сда|пода)|успева|опозда|when.{0,35}(submit|apply)|too\\s+late|still.{0,20}(apply|submit)|қашан.{0,25}(тапсыр|өткіз)|қай\\s+күнге|үлгере|кешігіп)");
    private static final Pattern DOCUMENTS = words("документ\\p{L}*|докуметы|documents?|paperwork|құжат\\p{L}*|кужат\\p{L}*");
    private static final Pattern PAST = words("был\\p{L}*|ист[её]к\\p{L}*|прошедш\\p{L}*|прошл\\p{L}*|прош[её]л|проходил\\p{L}*|проводил\\p{L}*|состоял\\p{L}*|"
            + "were|was|expired|past|took\\s+place|happened|occurred|did.{0,60}(?:take\\s+place|happen|occur)|"
            + "өткен|өтті|болған|болды|аяқталған");
    private static final Pattern PUBLICATION = words("пост\\p{L}*|публикаци\\p{L}*|опубликова\\p{L}*|публикова\\p{L}*|вылож\\p{L}*|новост\\p{L}*|posts?|published|posted|publications?|news|жариялан\\p{L}*|жарияла\\p{L}*|жаңалық\\p{L}*");
    private static final Pattern TODAY = words("сегодня|today|бүгін\\p{L}*");
    private static final Pattern YESTERDAY = words("вчера|yesterday|кеше\\p{L}*");
    private static final Pattern WEEK = words("недел\\p{L}*|week|апта\\p{L}*");
    private static final Pattern TOMORROW = words("завтра|tomorrow|ертең\\p{L}*");
    private static final Pattern BROAD_UPCOMING = words("now|upcoming|coming\\s+up|сейчас|щас|ща|будут|ближайш\\p{L}*|предстоящ\\p{L}*|қазір|алдағы|жақында");
    private static final Pattern EXPLICIT_CALENDAR = words(
            "январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|ма[йяе]|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*|"
                    + "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?|"
                    + "қаңтар\\p{L}*|ақпан\\p{L}*|наурыз\\p{L}*|сәуір\\p{L}*|мамыр\\p{L}*|маусым\\p{L}*|шілде\\p{L}*|тамыз\\p{L}*|қыркүйек\\p{L}*|қазан\\p{L}*|қараша\\p{L}*|желтоқсан\\p{L}*|"
                    + "понедельник\\p{L}*|вторник\\p{L}*|сред[ауые]|четверг\\p{L}*|пятниц\\p{L}*|суббот\\p{L}*|воскресень\\p{L}*|"
                    + "monday|tuesday|wednesday|thursday|friday|saturday|sunday|дүйсенбі\\p{L}*|сейсенбі\\p{L}*|сәрсенбі\\p{L}*|бейсенбі\\p{L}*|жұма\\p{L}*|сенбі\\p{L}*|жексенбі\\p{L}*|"
                    + "day\\p{L}*|month\\p{L}*|year\\p{L}*|semester\\p{L}*|дн\\p{L}*|день|месяц\\p{L}*|год\\p{L}*|семестр\\p{L}*|күн\\p{L}*|ай\\p{L}*|жыл\\p{L}*");
    private static final Pattern NUMBER = Pattern.compile("\\p{N}");

    static ChannelQueryAnalysis normalize(String question, ChannelQueryAnalysis original) {
        return normalize(question, original, LocalDate.now(ZoneId.of("Asia/Almaty")));
    }

    static ChannelQueryAnalysis normalize(String question, ChannelQueryAnalysis original, LocalDate today) {
        if (original == null) original = ChannelQueryAnalysis.unknown();
        String text = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace('-', ' ').replace('ё', 'е');
        boolean events = has(EVENTS, text), practice = has(PRACTICE, text), vacancies = has(VACANCIES, text);
        boolean documents = has(DOCUMENTS, text);
        boolean deadline = has(DEADLINE, text) || has(DEADLINE_QUESTION, text)
                && (practice || vacancies || documents);
        // Weak words such as "срок" or "practice" do not override a confident out-of-scope route.
        if (original.intent() == ChannelSearchIntent.GENERAL_CHAT && !original.needsChannelPosts()
                && !original.needsFaq() && !events && !vacancies && !(deadline && (practice || documents))) {
            return original;
        }
        // Stable FAQ questions retain the exact FAQ shortcut and do not receive unrelated announcements.
        if (original.needsFaq() && !original.needsChannelPosts() && !deadline && !events && !vacancies) {
            return original;
        }
        if (original.intent() == ChannelSearchIntent.UNKNOWN && practice && !deadline && !events && !vacancies) {
            return original;
        }
        LinkedHashSet<ChannelContentScope> scopes = new LinkedHashSet<>(original.contentScopes());
        if (practice) scopes.add(ChannelContentScope.PRACTICE);
        if (vacancies) scopes.add(ChannelContentScope.VACANCIES);
        if (events) scopes.add(ChannelContentScope.EVENTS);
        if (deadline && !(practice || vacancies || events)
                && (scopes.equals(Set.of(ChannelContentScope.NONE)) || original.intent() == ChannelSearchIntent.GENERAL_CHAT
                || original.intent() == ChannelSearchIntent.UNKNOWN)) scopes.add(ChannelContentScope.DEADLINES);
        boolean grounded = deadline || events || practice || vacancies;
        if (!grounded) return original;
        scopes.remove(ChannelContentScope.NONE);
        // ALL_UPDATES is an explicit broad request; naming examples must not narrow it.
        if (scopes.isEmpty()) scopes.add(ChannelContentScope.DEADLINES);
        ChannelSearchIntent intent = original.intent();
        if (intent == ChannelSearchIntent.GENERAL_CHAT || intent == ChannelSearchIntent.UNKNOWN) {
            intent = vacancies ? ChannelSearchIntent.VACANCY : practice ? ChannelSearchIntent.PRACTICE
                    : deadline ? ChannelSearchIntent.DEADLINE : ChannelSearchIntent.GENERAL_UPDATES;
        }
        // An event happening today is not restricted to announcements published today.
        boolean publicationWindow = has(PUBLICATION, text);
        ChannelTimeScope time = original.timeScope();
        LocalDate eventFrom = original.eventDateFrom(), eventTo = original.eventDateTo();
        if (events && !publicationWindow && (time == ChannelTimeScope.ANY_TIME || time == ChannelTimeScope.TODAY)
                && original.dateFrom() == null && original.dateTo() == null
                && today.equals(eventFrom) && today.equals(eventTo) && has(BROAD_UPCOMING, text)
                && !has(TODAY, text) && !has(YESTERDAY, text) && !has(TOMORROW, text)
                && !has(WEEK, text) && !has(EXPLICIT_CALENDAR, text) && !has(NUMBER, text)) {
            // "Now or coming up" is open-ended. Providers sometimes invent a today-only window.
            eventFrom = null;
            eventTo = null;
        }
        if (events && !publicationWindow) {
            switch (time) {
                case CUSTOM_RANGE -> {
                    // Keep malformed/missing ranges visible to validation, including reversed bounds.
                    if (original.dateFrom() != null || original.dateTo() != null || original.hasEventDateRange()) {
                        if (!original.hasEventDateRange()) {
                            eventFrom = original.dateFrom();
                            eventTo = original.dateTo();
                        }
                        time = ChannelTimeScope.ANY_TIME;
                    }
                }
                case TODAY, YESTERDAY, LAST_7_DAYS -> {
                    boolean explicitOccurrenceWindow = time == ChannelTimeScope.TODAY && has(TODAY, text)
                            || time == ChannelTimeScope.YESTERDAY && has(YESTERDAY, text)
                            || time == ChannelTimeScope.LAST_7_DAYS && (has(WEEK, text) || text.contains("7"));
                    if (!original.hasEventDateRange() && explicitOccurrenceWindow) {
                        eventTo = time == ChannelTimeScope.YESTERDAY ? today.minusDays(1) : today;
                        eventFrom = time == ChannelTimeScope.LAST_7_DAYS ? today.minusDays(6) : eventTo;
                    }
                    time = ChannelTimeScope.ANY_TIME;
                }
                case ANY_TIME -> { }
            }
        }
        ChannelFreshnessScope freshness = (deadline || events) && has(PAST, text)
                && original.freshnessScope() == ChannelFreshnessScope.CURRENT
                ? ChannelFreshnessScope.ALL : original.freshnessScope();
        return new ChannelQueryAnalysis(intent, original.topic(), List.copyOf(scopes), original.resultMode(),
                true, original.needsFaq() || practice || deadline && has(DOCUMENTS, text),
                original.needsDeadlines() || deadline, null, time, freshness,
                time == ChannelTimeScope.ANY_TIME ? null : original.dateFrom(),
                time == ChannelTimeScope.ANY_TIME ? null : original.dateTo(), eventFrom, eventTo);
    }

    private static Pattern words(String body) { return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:" + body + ")(?![\\p{L}\\p{N}])"); }
    private static boolean has(Pattern pattern, String text) { return pattern.matcher(text).find(); }
}
