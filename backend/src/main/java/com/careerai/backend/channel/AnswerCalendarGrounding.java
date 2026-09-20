package com.careerai.backend.channel;

import com.careerai.backend.answer.AnswerLanguage;
import com.careerai.backend.answer.StructuredChannelAnswerBuilder;
import com.careerai.backend.faq.FaqEntry;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Prevents a generated calendar date from being sourced only from publication metadata. */
@Component
public class AnswerCalendarGrounding {
    private static final Pattern CLOCK_LABEL = Pattern.compile("(?iu)(?:текущая\\s+дата|сегодня(?:шняя\\s+дата)?|today(?:['’]s\\s+date)?|current\\s+date|бүгін(?:гі\\s+күн)?)\\s*(?:(?:is|это)\\s*)?[:=—–,-]?\\s*$");
    private static final Pattern SUBJECT_DATE = Pattern.compile("(?iu)мероприят|ивент|событи|дедлайн|срок|регистрац|\\bevent\\b|\\bdeadline\\b|\\bregistration\\b|\\bapplication\\b|мерзім|іс[- ]шара");
    private static final Pattern STATUS_CLAIM = Pattern.compile("(?iu)(?:"
            + "(?:срок|дедлайн|при[её]м|регистраци[яию]|подача|заявки|ваканси[яи]|набор)[^.!?;\\n]{0,90}?(?:ист[её]к|прош[её]л|закрыт|заверш[её]н|открыт|действует|актуал)"
            + "|(?:deadline|registration|applications?|admission|submission|vacanc(?:y|ies)|enrollment)\\b[^.!?;\\n]{0,90}?\\b(?:passed|expired|closed|open|active|over|ended|valid)\\b"
            + "|(?:мерзім|тіркелу|қабылдау|өтінім)[^.!?;\\n]{0,90}?(?:өтіп\\s+кет|аяқтал|жабыл|ашық|өткен|біт)"
            + "|(?:it|this|that)\\s+(?:(?:has|is|was)\\s+)?(?:already\\s+|not\\s+|still\\s+)?(?:passed|expired|closed|open|active|over)\\b"
            + "|(?:он|она|это)\\s+(?:уже\\s+|ещ[её]\\s+не\\s+)?(?:ист[её]к|прош[её]л|закрыт|открыт)"
            + "|уже\\s+поздно|ещ[её]\\s+успева|(?:вы|ты)\\s+опоздал|too\\s+late|still\\s+time|you(?:['’]re|\\s+are)\\s+late|you\\s+can\\s+still\\s+(?:apply|submit))");
    private static final Pattern UNCERTAIN_STATUS = Pattern.compile("(?iu)(?:"
            + "не\\s+могу|нельзя|невозможно|неизвестно|неясно|не\\s+подтвержд|нет\\s+подтвержд|уточн|если|возможно"
            + "|cannot|can['’]t|unable|unknown|unclear|not\\s+confirmed|not\\s+known|not\\s+possible|\\bif\\b|\\bmight\\b|\\bmay\\b|\\bcould\\b|\\bwhether\\b"
            + "|белгісіз|нақтылау|расталмаған|егер|мүмкін)");
    private final MultilingualDateTextParser parser;
    private final Clock clock;
    public AnswerCalendarGrounding(MultilingualDateTextParser parser, Clock clock) {
        this.parser = parser; this.clock = clock;
    }

    public boolean supported(String answer, List<TelegramChannelPost> posts, List<FaqEntry> faqs) {
        List<MultilingualDateTextParser.DateMention> allowed = new ArrayList<>();
        boolean resolvedSourceYear = false;
        for (TelegramChannelPost post : posts) {
            LocalDate reference = post.getPostedAt() == null ? LocalDate.now(clock)
                    : post.getPostedAt().atZoneSameInstant(clock.getZone()).toLocalDate();
            for (var mention : parser.calendarMentions(post.getText())) {
                allowed.add(mention);
                DateParseResult resolved = parser.parseMatchingDate(post.getText(), mention.text(), reference);
                if (resolved.status() == DateParseStatus.PARSED) {
                    addDate(allowed, resolved.date());
                    resolvedSourceYear = true;
                }
            }
            if (post.hasCurrentDateConfirmation()) {
                addDate(allowed, post.getConfirmedDate());
                resolvedSourceYear = true;
            }
        }
        for (FaqEntry faq : faqs) {
            var mentions=parser.calendarMentions(faq.getFullAnswer());
            allowed.addAll(mentions);
            for(var mention:mentions) {
                if(parser.parseMatchingDate(faq.getFullAnswer(),mention.text(),LocalDate.now(clock)).status()==DateParseStatus.PARSED)
                    resolvedSourceYear=true;
            }
        }
        String plain = answer.replaceAll("<[^>]+>", "");
        if (!allowed.isEmpty() && !resolvedSourceYear && assertsUnverifiedStatus(plain)) return false;
        int position = 0;
        for (var claimed : parser.calendarMentions(plain)) {
            int start = plain.indexOf(claimed.text(),position);
            int end = start < 0 ? position : start + claimed.text().length();
            boolean found = allowed.stream().anyMatch(source -> source.day() == claimed.day()
                    && source.month() == claimed.month()
                    && (claimed.year() == null || Objects.equals(claimed.year(), source.year())));
            if (!found && start >= 0) found = isClockReference(plain,claimed,start,end);
            if (!found) return false;
            position=end;
        }
        return true;
    }

    private boolean isClockReference(String answer,MultilingualDateTextParser.DateMention date,int start,int end) {
        LocalDate today=LocalDate.now(clock);
        if(date.day()!=today.getDayOfMonth()||date.month()!=today.getMonthValue()
                ||(date.year()!=null&&date.year()!=today.getYear()))return false;
        int clauseStart=start;
        while(clauseStart>0&&!".,;!?\n".contains(String.valueOf(answer.charAt(clauseStart-1))))clauseStart--;
        String prefix=answer.substring(clauseStart,start);
        if(!CLOCK_LABEL.matcher(prefix).find()||SUBJECT_DATE.matcher(prefix).find())return false;
        int clauseEnd=end;
        while(clauseEnd<answer.length()&&!".,;!?\n".contains(String.valueOf(answer.charAt(clauseEnd))))clauseEnd++;
        // A pure clock reference may end with a year suffix. "Today ... the event takes
        // place" is an event-date claim and must remain grounded in the source instead.
        return answer.substring(end,clauseEnd).matches("(?iu)\\s*(?:года?|жыл(?:ы)?)?\\s*");
    }

    private boolean assertsUnverifiedStatus(String answer) {
        String scoped = answer.replaceAll("(?iuU)((?:нельзя|невозможно|не\\s+могу)\\s+(?:подтвердить|определить)|(?:cannot|can['’]t)\\s+confirm)\\s*,\\s*(что|ли|whether|if)\\b", "$1 $2");
        for(String clause:scoped.split("(?iuU)[,.!?;\\n]+|\\b(?:but|however|но|однако|бірақ)\\b")) {
            var claims=STATUS_CLAIM.matcher(clause);
            while(claims.find()) {
                if(!UNCERTAIN_STATUS.matcher(clause.substring(0,claims.end())).find())return true;
            }
        }
        return false;
    }

    public String sourceDates(String question, List<TelegramChannelPost> posts) {
        AnswerLanguage language = AnswerLanguage.detect(question);
        StringBuilder answer = new StringBuilder(language.select(
                "Не удалось подтвердить даты в сформированном ответе. В исходных публикациях указано:",
                "Құрастырылған жауаптағы күндерді растай алмадым. Бастапқы жарияланымдарда көрсетілгені:",
                "I could not verify the dates in the generated answer. The original publications state:"));
        boolean any = false;
        for (TelegramChannelPost post : posts) {
            LocalDate reference = post.getPostedAt() == null ? LocalDate.now(clock)
                    : post.getPostedAt().atZoneSameInstant(clock.getZone()).toLocalDate();
            List<String> dates = parser.calendarMentions(post.getText()).stream().map(mention -> {
                String quoted=escape(mention.text());
                DateParseResult resolved=parser.parseMatchingDate(post.getText(),mention.text(),reference);
                if(resolved.status()==DateParseStatus.PARSED) {
                    if(mention.year()==null) return quoted+" — "+resolved.date()+language.select(
                            " (год определён по тексту публикации)"," (жыл жарияланым мәтіні бойынша анықталған)",
                            " (year resolved from the publication text)");
                    return mention.text().contains(mention.year().toString())?quoted:quoted+" — "+resolved.date();
                }
                if(resolved.status()==DateParseStatus.INVALID) return quoted+language.select(
                        " (некорректная дата в источнике)"," (дереккөздегі күн қате)"," (invalid date in the source)");
                if(mention.text().contains("/")) return quoted+language.select(
                        " (формат даты или год требуют уточнения)"," (күн пішімі немесе жыл нақтылануы керек)",
                        " (date format or year needs clarification)");
                return quoted+(mention.year()==null?language.select(" (год в дате не указан)",
                        " (күнде жыл көрсетілмеген)"," (no year in this date)"):"");
            }).distinct().toList();
            if (post.hasCurrentDateConfirmation()) {
                dates = new ArrayList<>(dates);
                dates.add(language.select("Подтверждено администратором: ", "Әкімші растаған күн: ", "Administrator-confirmed date: ")
                        + post.getConfirmedDate());
            }
            if (dates.isEmpty()) continue;
            any = true;
            answer.append("\n\n• ").append(String.join("; ", dates));
            StructuredChannelAnswerBuilder.sourceUrl(post).ifPresent(url -> answer.append("\n").append(url));
        }
        if (!any) answer.append("\n").append(language.select("Подтверждённых календарных дат в этой выборке не найдено.",
                "Бұл іріктемеде расталған күндер табылмады.", "No confirmed calendar dates were found in this selection."));
        return answer.append("\n\n").append(language.select(
                "По ссылкам можно проверить, к чему относится каждая дата. Применимость срока и возможность участия уточни в ЦКиТ.",
                "Әр күннің неге қатысты екенін сілтемелерден тексеріңіз. Мерзімнің қолданылуын және қатысу мүмкіндігін Мансап орталығынан нақтылаңыз.",
                "Check the linked publications for what each date applies to. Confirm applicability and participation with the Career Center.")).toString();
    }

    private static void addDate(List<MultilingualDateTextParser.DateMention> dates, LocalDate date) {
        dates.add(new MultilingualDateTextParser.DateMention(date.getDayOfMonth(), date.getMonthValue(), date.getYear(), date.toString()));
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
