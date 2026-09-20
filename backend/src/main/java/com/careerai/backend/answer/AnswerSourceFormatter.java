package com.careerai.backend.answer;

import com.careerai.backend.channel.*;
import java.util.*;

/** Adds auditable links from persisted sources rather than asking the model to invent them. */
public final class AnswerSourceFormatter {
    private AnswerSourceFormatter() { }

    public static String append(String question, String answer, ChannelPostSearchResult result) {
        AnswerLanguage language = AnswerLanguage.detect(question);
        StringBuilder output = new StringBuilder(language.localizeStatusLabels(answer).strip());
        boolean uncertainVacancy = result.groups().stream()
                .filter(group -> group.scope() == ChannelContentScope.VACANCIES)
                .flatMap(group -> group.posts().stream())
                .anyMatch(post -> post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.UNKNOWN
                        || post.getExpiresAt() == null);
        if (uncertainVacancy) output.append("\n\n").append(language.select(
                "В выборке есть вакансии без подтверждённого срока. Наличие публикации не подтверждает, что приём откликов ещё открыт.",
                "Іріктемеде мерзімі расталмаған бос жұмыс орындары бар. Жарияланымның болуы өтінім қабылдау әлі ашық екенін растамайды.",
                "Some vacancies have no confirmed closing date. A saved announcement does not confirm that applications are still open."));
        Set<String> links = new LinkedHashSet<>();
        for (TelegramChannelPost post : result.allPosts()) StructuredChannelAnswerBuilder.sourceUrl(post).ifPresent(links::add);
        List<String> missing = links.stream().filter(url -> !answer.contains(url)).toList();
        if (!missing.isEmpty()) {
            output.append("\n\n<b>").append(language.select("Публикации, использованные для ответа:",
                    "Жауапта пайдаланылған жарияланымдар:", "Source publications:")).append("</b>\n");
            for (String link : missing) output.append(link).append("\n");
        }
        return output.toString().strip();
    }
}
