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
 * Сервис сначала анализирует вопрос пользователя через LLM-router,
 * затем при необходимости достаёт последние посты Telegram-канала
 * и передаёт их в LLM как фактический контекст.
 */

@Service
public class TelegramChannelPostAnswerService {

    private static final int MAX_CONTEXT_POSTS = 15;

    private static final ZoneId ASTANA_ZONE_ID = ZoneId.of("Asia/Almaty");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TelegramChannelPostRepository repository;
    private final ChannelQueryAnalyzer queryAnalyzer;
    private final LlmProvider llmProvider;

    public TelegramChannelPostAnswerService(
            TelegramChannelPostRepository repository,
            ChannelQueryAnalyzer queryAnalyzer,
            LlmProvider llmProvider
    ) {
        this.repository = repository;
        this.queryAnalyzer = queryAnalyzer;
        this.llmProvider = llmProvider;
    }

    public Optional<String> buildAnswerIfRelevant(String userMessage) {
        ChannelQueryAnalysis analysis = queryAnalyzer.analyze(userMessage);

//        if (!analysis.requiresChannelPosts()) {
//            return Optional.empty();
//        }
        if (!shouldUseChannelPosts(analysis)) {
            return Optional.empty();
        }

        List<TelegramChannelPost> posts = repository.findLatestTextPosts(
                PageRequest.of(0, MAX_CONTEXT_POSTS)
        );

        if (posts.isEmpty()) {
            return Optional.of("""
                    <b>Свежие объявления из Telegram-канала ЦКиТа</b>
                    Пока в сохранённой базе нет текстовых постов из Telegram-канала.
                    """);
        }

        String ragPrompt = buildRagPrompt(userMessage, analysis, posts);

        LlmResponse llmResponse = llmProvider.generateAnswer(ragPrompt);

        if (llmResponse.success()) {
            return Optional.of(llmResponse.text());
        }

        return Optional.of(buildDirectFallbackAnswer(posts));
    }

    private String buildRagPrompt(String userMessage, ChannelQueryAnalysis analysis, List<TelegramChannelPost> posts) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
            Пользователь задал вопрос по актуальным объявлениям Telegram-канала ЦКиТа.
    
            Ниже передан блок "НАЙДЕННЫЕ_ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА".
            Это и есть актуальная база постов Telegram-канала, доступная тебе в этом запросе.
    
            Строгие правила:
            - используй только найденные посты как источник фактов;
            - не выдумывай вакансии, компании, дедлайны, даты, форматы и требования;
            - не упоминай Platonus, деканат, кафедру, кураторов или другие системы, если их нет в найденных постах;
            - не говори, что у тебя нет подключённой базы, если блок найденных постов передан;
            - если в найденных постах нет ответа на конкретный вопрос, честно скажи: "В найденных постах этой информации нет";
            - если вопрос про вакансии, не делай главным ответом производственную практику;
            - если вопрос про практику, не перечисляй вакансии без необходимости;
            - если вопрос про дедлайны, разделяй сроки по категориям;
            - не добавляй блок "Следующий шаг" в каждом ответе;
            - если совет действительно уместен, добавь его коротко в конце;
            - если в найденных постах есть странная дата, например "32 июня" или "33 июня", укажи, что дату стоит перепроверить.
            """);

        prompt.append("\nРезультат анализа запроса:\n");
        prompt.append("intent: ").append(analysis.intent()).append("\n");
        prompt.append("topic: ").append(analysis.topic()).append("\n");
        prompt.append("needsChannelPosts: ").append(analysis.needsChannelPosts()).append("\n");
        prompt.append("needsFaq: ").append(analysis.needsFaq()).append("\n");
        prompt.append("needsDeadlines: ").append(analysis.needsDeadlines()).append("\n");

        prompt.append("\nВопрос пользователя:\n");
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
            return "не указана";
        }

        return dateTime
                .atZoneSameInstant(ASTANA_ZONE_ID)
                .format(DATE_TIME_FORMATTER);
    }

    private OffsetDateTime getEffectiveDate(TelegramChannelPost post) {
        if (post.getEditedAt() != null) {
            return post.getEditedAt();
        }

        if (post.getPostedAt() != null) {
            return post.getPostedAt();
        }

        return post.getCreatedAt();
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

    private boolean shouldUseChannelPosts(ChannelQueryAnalysis analysis) {
        if (analysis == null) {
            return false;
        }

        if (analysis.needsChannelPosts() || analysis.needsDeadlines()) {
            return true;
        }

        return switch (analysis.intent()) {
            case VACANCY, PRACTICE, DEADLINE, GENERAL_UPDATES -> true;
            case GENERAL_CHAT, FAQ, UNKNOWN -> false;
        };
    }
}