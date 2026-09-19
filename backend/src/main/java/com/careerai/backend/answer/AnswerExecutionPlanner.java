package com.careerai.backend.answer;

import com.careerai.backend.channel.*;
import com.careerai.backend.faq.FaqEntry;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Performance decisions are fail-closed: an LLM label alone never enables a shortcut. */
@Component
public class AnswerExecutionPlanner {
    private static final Set<String> SMALL_TALK = Set.of(
            "привет", "здравствуй", "здравствуйте", "доброе утро", "добрый день", "добрый вечер",
            "как дела", "привет как дела", "спасибо", "благодарю", "пока", "до свидания",
            "сәлем", "сәлеметсіз бе", "салем", "рахмет", "сау бол", "қалың қалай",
            "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
            "how are you", "hello how are you", "thanks", "thank you", "bye", "goodbye"
    );
    private static final Pattern SIMPLE_LIST = Pattern.compile(
            "(?:покажи |показать |дай )?(?:все|полный список) (?:актуальные |актуальных )?"
                    + "(?:вакансии|вакансий|мероприятия|мероприятий)(?: и (?:вакансии|мероприятия))?(?: пожалуйста)?"
                    + "|(?:please )?(?:show |list )?(?:me )?all (?:current |active )?"
                    + "(?:vacancies|jobs|events)(?: and (?:vacancies|jobs|events))?(?: please)?"
                    + "|барлық (?:өзекті )?(?:вакансияларды|вакансиялар|іс шараларды|іс шаралар)"
                    + "(?: және (?:вакансияларды|іс шараларды))?(?: көрсет)?"
    );
    private static final Pattern CORRECTION = Pattern.compile(
            "(?iu)(отмен|уточн|исправ|обнов|продл|перенос|перенес|не будет|больше не|cancel|correct|update|extend|postpon|reschedul|"
                    + "күші жой|болмайды|ұзарт|өзгер|түзет)"
    );

    public Optional<String> directAnswer(String question, ChannelQueryAnalysis analysis) {
        if (analysis == null || analysis.intent() != ChannelSearchIntent.GENERAL_CHAT
                || analysis.requiresTimelineSearch()
                || analysis.needsChannelPosts() || analysis.needsFaq() || analysis.needsDeadlines()
                || !analysis.contentScopes().equals(List.of(ChannelContentScope.NONE))
                || !SMALL_TALK.contains(normalize(question))) {
            return Optional.empty();
        }
        String answer = analysis.directAnswer();
        if (answer == null || answer.isBlank() || answer.length() > 600
                || answer.matches("(?s).*[<>\\d].*") || answer.contains("http")
                || AnswerLanguage.detect(question) != AnswerLanguage.detect(answer)) {
            return Optional.empty();
        }
        return Optional.of(answer.trim());
    }

    public Optional<FaqEntry> exactFaq(String question, ChannelQueryAnalysis analysis, List<FaqEntry> entries) {
        if (analysis == null || !analysis.needsFaq() || analysis.needsChannelPosts() || analysis.needsDeadlines()
                || analysis.requiresTimelineSearch()
                || (analysis.intent() != ChannelSearchIntent.FAQ && analysis.intent() != ChannelSearchIntent.PRACTICE)) {
            return Optional.empty();
        }
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank()) {
            return Optional.empty();
        }
        List<FaqEntry> matches = entries.stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getActive()))
                .filter(entry -> normalizedQuestion.equals(normalize(entry.getQuestion())))
                .filter(entry -> entry.getFullAnswer() != null && !entry.getFullAnswer().isBlank())
                .toList();
        // Duplicate canonical questions are ambiguous even if the first entry has higher priority.
        if (matches.size() != 1 || AnswerLanguage.detect(question)
                != AnswerLanguage.detect(matches.getFirst().getFullAnswer())) {
            return Optional.empty();
        }
        return Optional.of(matches.getFirst());
    }

    public boolean canTryStructuredChannel(String question, ChannelQueryAnalysis analysis) {
        if (analysis == null || !analysis.needsChannelPosts() || analysis.needsFaq() || analysis.needsDeadlines()
                || analysis.requiresTimelineSearch()
                || analysis.resultMode() != ChannelResultMode.ALL_MATCHING
                || !SIMPLE_LIST.matcher(normalize(question)).matches()) {
            return false;
        }
        String normalized = normalize(question);
        Set<ChannelContentScope> expected = new HashSet<>();
        if (normalized.matches(".*(?:ваканси|vacanc|jobs).*")) {
            expected.add(ChannelContentScope.VACANCIES);
        }
        if (normalized.matches(".*(?:мероприяти|events|іс шара).*")) {
            expected.add(ChannelContentScope.EVENTS);
        }
        return !expected.isEmpty() && expected.equals(new HashSet<>(analysis.contentScopes()));
    }

    public boolean hasSimpleSources(ChannelPostSearchResult result) {
        return result != null && result.relationContextComplete() && !result.isEmpty() && result.relations().isEmpty()
                && result.allPosts().stream().allMatch(post -> post.getReplyToTelegramMessageId() == null
                && post.getText() != null && !post.getText().isBlank()
                && !CORRECTION.matcher(post.getText()).find());
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[?!.,:;]+", " ").replaceAll("[\\s\\p{Z}]+", " ").strip();
    }
}
