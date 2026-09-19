package com.careerai.backend.answer;

import com.careerai.backend.channel.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** Renders source previews, never synthesizes conditions or resolves contradictions. */
@Component
public class StructuredChannelAnswerBuilder {
    private final TelegramChannelPostSearchEligibility eligibility;
    private final TelegramChannelPostRepository posts;
    private final StandaloneRelationCandidateRepository candidates;
    private final AnswerExecutionPlanner planner;
    private final Clock clock;

    public StructuredChannelAnswerBuilder(TelegramChannelPostSearchEligibility eligibility,
                                         TelegramChannelPostRepository posts,
                                         StandaloneRelationCandidateRepository candidates,
                                         AnswerExecutionPlanner planner, Clock clock) {
        this.eligibility = eligibility;
        this.posts = posts;
        this.candidates = candidates;
        this.planner = planner;
        this.clock = clock;
    }

    public Optional<String> build(String question, ChannelPostSearchResult result, int limit) {
        if (!planner.hasSimpleSources(result)) return Optional.empty();
        List<TelegramChannelPost> sources = result.allPosts();
        if (sources.isEmpty() || sources.stream().anyMatch(post -> !eligibility.isSearchable(post)
                || sourceUrl(post).isEmpty() || effectiveDate(post).equals(OffsetDateTime.MIN))
                || candidates.hasUnresolvedForPostIds(sources.stream().map(TelegramChannelPost::getId).toList())) {
            return Optional.empty();
        }
        // A correction can be classified OTHER and therefore be outside the requested category.
        // Scan newer source texts as well; if this bounded scan cannot cover the selected range,
        // defer to retrieval and generation instead of claiming a safe plain list.
        List<TelegramChannelPost> recent = posts.findLatestSearchableTextPosts(PageRequest.of(0, 100));
        OffsetDateTime oldestSource = sources.stream().map(this::effectiveDate)
                .min(OffsetDateTime::compareTo).orElse(OffsetDateTime.MIN);
        if (recent.size() == 100 && effectiveDate(recent.getLast()).isAfter(oldestSource)) {
            return Optional.empty();
        }
        List<TelegramChannelPost> newer = recent.stream().filter(eligibility::isSearchable)
                .filter(post -> !effectiveDate(post).isBefore(oldestSource)).toList();
        if (!newer.isEmpty() && !planner.hasSimpleSources(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.ALL_UPDATES, newer))))) {
            return Optional.empty();
        }

        AnswerLanguage language = AnswerLanguage.detect(question);
        StringBuilder answer = new StringBuilder(language.select(
                "Публикации канала: превью и ссылки на полные условия.",
                "Арна жарияланымдары: үзінділер және толық шарттарға сілтемелер.",
                "Channel posts: previews and links to the full details."));
        answer.append("\n").append(language.select(
                "Показано до " + limit + " последних совпадений.",
                "Соңғы " + limit + " сәйкестікке дейін көрсетілген.",
                "Showing up to " + limit + " latest matches."));
        for (ChannelPostSearchGroup group : result.groups()) {
            answer.append("\n\n<b>").append(title(group.scope(), language)).append("</b>\n");
            int number = 0;
            for (TelegramChannelPost post : group.posts()) {
                String text = post.getText().replaceAll("\\s+", " ").strip();
                if (text.length() > 160) text = text.substring(0, 160).stripTrailing() + "…";
                answer.append(++number).append(". ").append(escape(text)).append("\n");
                answer.append(effectiveDate(post).atZoneSameInstant(clock.getZone())
                        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
                if (post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.UNKNOWN) {
                    answer.append(" · ").append(language.select("срок не подтверждён",
                            "мерзімі расталмаған", "expiry unconfirmed"));
                }
                answer.append("\n").append(sourceUrl(post).orElseThrow()).append("\n\n");
            }
        }
        return Optional.of(answer.toString().trim());
    }

    public static Optional<String> sourceUrl(TelegramChannelPost post) {
        if (post.getTelegramMessageId() == null || post.getTelegramMessageId() <= 0) return Optional.empty();
        if (post.getChannelUsername() != null && post.getChannelUsername().matches("[A-Za-z0-9_]{5,32}")) {
            return Optional.of("https://t.me/" + post.getChannelUsername() + "/" + post.getTelegramMessageId());
        }
        String chatId = String.valueOf(post.getTelegramChatId());
        if (chatId.startsWith("-100") && chatId.length() > 4) {
            return Optional.of("https://t.me/c/" + chatId.substring(4) + "/" + post.getTelegramMessageId());
        }
        return Optional.empty();
    }

    private String title(ChannelContentScope scope, AnswerLanguage language) {
        return switch (scope) {
            case VACANCIES -> language.select("Вакансии", "Бос жұмыс орындары", "Vacancies");
            case EVENTS -> language.select("Мероприятия", "Іс-шаралар", "Events");
            default -> language.select("Публикации", "Жарияланымдар", "Posts");
        };
    }

    private OffsetDateTime effectiveDate(TelegramChannelPost post) {
        if (post.getEditedAt() != null) return post.getEditedAt();
        if (post.getPostedAt() != null) return post.getPostedAt();
        return post.getCreatedAt() == null ? OffsetDateTime.MIN : post.getCreatedAt();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
