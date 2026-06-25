package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Формирует ответы на основе сохранённых постов Telegram-канала.
 *
 * Сервис сначала ищет релевантные посты в PostgreSQL, затем передаёт найденный
 * контекст в LLM. Благодаря этому бот отвечает не "из головы", а на основе
 * фактических объявлений из Telegram-канала ЦКиТа.
 */

@Service
public class TelegramChannelPostAnswerService {

    private static final int MAX_CANDIDATE_POSTS = 20;
    private static final int MAX_CONTEXT_POSTS = 5;

    private static final ZoneId ASTANA_ZONE_ID = ZoneId.of("Asia/Almaty");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TelegramChannelPostRepository repository;
    private final LlmProvider llmProvider;

    public TelegramChannelPostAnswerService(
            TelegramChannelPostRepository repository,
            LlmProvider llmProvider
    ) {
        this.repository = repository;
        this.llmProvider = llmProvider;
    }

    public Optional<String> buildAnswerIfRelevant(String userMessage) {
        if (!isChannelPostSearchRequest(userMessage)) {
            return Optional.empty();
        }

        List<TelegramChannelPost> candidates = repository.findLatestOpportunityPosts(
                PageRequest.of(0, MAX_CANDIDATE_POSTS)
        );

        if (candidates.isEmpty()) {
            return Optional.of("""
                    <b>Свежие объявления из Telegram-канала ЦКиТа</b>
                    Пока в сохранённой базе нет постов, похожих на вакансии, стажировки или важные объявления.
                    
                    Если объявление только что опубликовали, подожди немного: backend должен получить его через Telegram updates.
                    """);
        }

        List<TelegramChannelPost> selectedPosts = selectRelevantPosts(userMessage, candidates);

        String ragPrompt = buildRagPrompt(userMessage, selectedPosts);

        LlmResponse llmResponse = llmProvider.generateAnswer(ragPrompt);

        if (llmResponse.success()) {
            return Optional.of(llmResponse.text());
        }

        return Optional.of(buildDirectFallbackAnswer(selectedPosts));
    }

    private boolean isChannelPostSearchRequest(String text) {
        String normalizedText = text.toLowerCase();

        return normalizedText.contains("ваканс")
                || normalizedText.contains("стажиров")
                || normalizedText.contains("intern")
                || normalizedText.contains("junior")
                || normalizedText.contains("работ")
                || normalizedText.contains("практик")
                || normalizedText.contains("дедлайн")
                || normalizedText.contains("что нового")
                || normalizedText.contains("свеж")
                || normalizedText.contains("объявлен")
                || normalizedText.contains("канал");
    }

    private List<TelegramChannelPost> selectRelevantPosts(
            String userMessage,
            List<TelegramChannelPost> candidates
    ) {
        Set<String> searchTerms = extractSearchTerms(userMessage);

        List<ScoredPost> scoredPosts = candidates.stream()
                .map(post -> new ScoredPost(post, calculateScore(post, searchTerms)))
                .sorted(Comparator
                        .comparingInt(ScoredPost::score)
                        .reversed()
                        .thenComparing(
                                scoredPost -> getEffectiveDate(scoredPost.post()),
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
                )
                .toList();

        boolean hasPositiveScore = scoredPosts.stream()
                .anyMatch(scoredPost -> scoredPost.score() > 0);

        return scoredPosts.stream()
                .filter(scoredPost -> !hasPositiveScore || scoredPost.score() > 0)
                .limit(MAX_CONTEXT_POSTS)
                .map(ScoredPost::post)
                .toList();
    }

    private Set<String> extractSearchTerms(String userMessage) {
        String normalizedText = userMessage.toLowerCase();

        Set<String> terms = new LinkedHashSet<>();

        if (normalizedText.contains("ваканс")) {
            terms.add("ваканс");
        }

        if (normalizedText.contains("стаж")) {
            terms.add("стаж");
        }

        if (normalizedText.contains("практик")) {
            terms.add("практик");
        }

        if (normalizedText.contains("java") || normalizedText.contains("джава")) {
            terms.add("java");
            terms.add("джава");
        }

        if (normalizedText.contains("python") || normalizedText.contains("питон")) {
            terms.add("python");
            terms.add("питон");
        }

        if (normalizedText.contains("backend") || normalizedText.contains("бэкенд")) {
            terms.add("backend");
            terms.add("бэкенд");
        }

        if (normalizedText.contains("frontend") || normalizedText.contains("фронтенд")) {
            terms.add("frontend");
            terms.add("фронтенд");
        }

        String[] words = normalizedText
                .replaceAll("[^a-zа-яё0-9+# ]", " ")
                .split("\\s+");

        for (String word : words) {
            if (word.length() >= 4 && !isStopWord(word)) {
                terms.add(word);
            }
        }

        return terms;
    }

    private boolean isStopWord(String word) {
        return Set.of(
                "есть",
                "новые",
                "нового",
                "свежие",
                "какие",
                "какая",
                "какой",
                "можно",
                "меня",
                "тебя",
                "позицию",
                "направлению",
                "разработчика",
                "сейчас",
                "сегодня"
        ).contains(word);
    }

    private int calculateScore(TelegramChannelPost post, Set<String> searchTerms) {
        String text = post.getText() == null
                ? ""
                : post.getText().toLowerCase();

        int score = 0;

        for (String term : searchTerms) {
            if (text.contains(term)) {
                score += 3;
            }
        }

        if (text.contains("ваканс")) {
            score += 2;
        }

        if (text.contains("стаж")) {
            score += 2;
        }

        if (text.contains("intern") || text.contains("junior")) {
            score += 2;
        }

        return score;
    }

    private String buildRagPrompt(String userMessage, List<TelegramChannelPost> posts) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                Пользователь задал вопрос по актуальным объявлениям Telegram-канала ЦКиТа.

                ВАЖНО:
                Ниже передан блок "НАЙДЕННЫЕ_ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА".
                Используй только эти посты как источник фактов.
                Не выдумывай дополнительные вакансии, компании, дедлайны, форматы и требования.
                Если в найденных постах есть странная дата, например "32 июня", аккуратно укажи,
                что дату стоит перепроверить.
                Если среди постов есть нерелевантные объявления, не делай их главными.

                Ответь красиво и полезно:
                • кратко перечисли подходящие объявления;
                • выдели название позиции, дедлайн, формат и компанию, если они есть;
                • если пользователь спрашивает про конкретную технологию, например Java, сначала покажи связанные с ней посты;
                • в конце дай практичный следующий шаг.

                Вопрос пользователя:
                """);

        prompt.append(userMessage).append("\n\n");

        prompt.append("НАЙДЕННЫЕ_ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА:\n\n");

        for (int i = 0; i < posts.size(); i++) {
            TelegramChannelPost post = posts.get(i);

            prompt.append("ПОСТ ").append(i + 1).append(":\n");
            prompt.append("Дата: ").append(formatPostDate(post)).append("\n");
            prompt.append("Текст:\n");
            prompt.append(post.getText()).append("\n\n");
        }

        return prompt.toString();
    }

    private String buildDirectFallbackAnswer(List<TelegramChannelPost> posts) {
        StringBuilder answer = new StringBuilder();

        answer.append("<b>Свежие посты из Telegram-канала ЦКиТа</b>\n");
        answer.append("AI-модель сейчас не смогла обработать найденные посты, поэтому показываю прямой список из базы.\n\n");

        for (int i = 0; i < posts.size(); i++) {
            TelegramChannelPost post = posts.get(i);

            answer.append("<b>").append(i + 1).append(". Объявление</b>\n");
            answer.append(escapeTelegramHtml(shortenText(post.getText()))).append("\n");

            String dateText = formatPostDate(post);

            if (dateText != null) {
                answer.append("<i>Дата: ").append(dateText).append("</i>\n");
            }

            answer.append("\n");
        }

        return answer.toString().trim();
    }

    private String shortenText(String text) {
        if (text == null || text.isBlank()) {
            return "Текст объявления отсутствует.";
        }

        String normalizedText = text
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        int maxLength = 900;

        if (normalizedText.length() <= maxLength) {
            return normalizedText;
        }

        return normalizedText.substring(0, maxLength).trim() + "...";
    }

    private String formatPostDate(TelegramChannelPost post) {
        OffsetDateTime dateTime = getEffectiveDate(post);

        if (dateTime == null) {
            return null;
        }

        return dateTime
                .atZoneSameInstant(ASTANA_ZONE_ID)
                .format(DATE_TIME_FORMATTER);
    }

    private OffsetDateTime getEffectiveDate(TelegramChannelPost post) {
        if (post.getEditedAt() != null) {
            return post.getEditedAt();
        }

        return post.getPostedAt();
    }

    private String escapeTelegramHtml(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private record ScoredPost(
            TelegramChannelPost post,
            int score
    ) {
    }
}