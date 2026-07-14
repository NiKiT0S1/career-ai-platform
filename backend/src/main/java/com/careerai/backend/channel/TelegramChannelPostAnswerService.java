package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import com.careerai.backend.faq.FaqEntry;
import com.careerai.backend.faq.FaqEntryService;
import com.careerai.backend.semantic.FaqSemanticSearchService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Формирует ответы на основе внутренних источников знаний ЦКиТ:
 * FAQ-базы и сохранённых постов Telegram-канала.
 *
 * Сервис сначала анализирует вопрос пользователя через LLM-router,
 * затем при необходимости достаёт FAQ-записи и/или посты Telegram-канала
 * и передаёт найденный контекст в LLM как источник фактов.
 */

@Service
public class TelegramChannelPostAnswerService {

    private static final int MAX_CONTEXT_POSTS = 15;

    private static final ZoneId ASTANA_ZONE_ID = ZoneId.of("Asia/Almaty");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final DateTimeFormatter CURRENT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TelegramChannelPostRepository repository;
    private final ChannelQueryAnalyzer queryAnalyzer;
    private final LlmProvider llmProvider;
    private final TelegramChannelPostStructuredSearchService structuredSearchService;
    private final FaqEntryService faqEntryService;
    private final FaqSemanticSearchService faqSemanticSearchService;

    public TelegramChannelPostAnswerService(
            TelegramChannelPostRepository repository,
            ChannelQueryAnalyzer queryAnalyzer,
            LlmProvider llmProvider,
            TelegramChannelPostStructuredSearchService structuredSearchService,
            FaqEntryService faqEntryService,
            FaqSemanticSearchService faqSemanticSearchService
    ) {
        this.repository = repository;
        this.queryAnalyzer = queryAnalyzer;
        this.llmProvider = llmProvider;
        this.structuredSearchService = structuredSearchService;
        this.faqEntryService = faqEntryService;
        this.faqSemanticSearchService = faqSemanticSearchService;
    }

    public Optional<String> buildAnswerIfRelevant(String userMessage) {
        ChannelQueryAnalysis analysis = queryAnalyzer.analyze(userMessage);

        boolean shouldUseFaq = shouldUseFaqEntries(analysis);
        boolean shouldUseChannelPosts = shouldUseChannelPosts(analysis);

        if (!shouldUseFaq && !shouldUseChannelPosts) {
            return Optional.empty();
        }

        List<FaqEntry> faqEntries = shouldUseFaq
                ? findRelevantFaqEntries(userMessage)
                : List.of();

        List<TelegramChannelPost> posts = shouldUseChannelPosts
                ? findRelevantPost(analysis)
                : List.of();

        if (faqEntries.isEmpty() && posts.isEmpty()) {
            return Optional.of(buildNoConfirmedInformationAnswer());
        }

        String ragPrompt = buildCombinedRagPrompt(userMessage, analysis, faqEntries, posts);

        LlmResponse llmResponse = llmProvider.generateAnswer(ragPrompt);

        if (llmResponse.success()) {
            return Optional.of(llmResponse.text());
        }

        return Optional.of(buildDirectFallbackAnswer(faqEntries, posts));
    }

    private List<TelegramChannelPost> findRelevantPost(ChannelQueryAnalysis analysis) {
        List<TelegramChannelPost> posts = structuredSearchService.findRelevantPosts(
                analysis,
                MAX_CONTEXT_POSTS
        );

        if (!posts.isEmpty()) {
            return posts;
        }

        if (analysis.intent() == ChannelSearchIntent.GENERAL_UPDATES) {
            return repository.findLatestTextPosts(
                    PageRequest.of(0, MAX_CONTEXT_POSTS)
            );
        }

        return List.of();
    }

    /**
     * Сначала пытается найти релевантные FAQ через Semantic Search.
     *
     * Если Semantic Search выключен, сломан, не проиндексирован
     * или не нашёл уверенных совпадений, возвращает все FAQ
     * по стабильной логике до Semantic.
     */
    private List<FaqEntry> findRelevantFaqEntries(String userMessage) {
        Optional<List<FaqEntry>> semanticResult = faqSemanticSearchService.findRelevantEntries(userMessage);

        if (semanticResult.isPresent()
                && !semanticResult.get().isEmpty()) {
            return semanticResult.get();
        }

        return faqEntryService.findActiveEntries();
    }

    private String buildCombinedRagPrompt(String userMessage, ChannelQueryAnalysis analysis, List<FaqEntry> faqEntries, List<TelegramChannelPost> posts) {
        StringBuilder prompt = new StringBuilder();

        String currentDateText = LocalDate
                .now(ASTANA_ZONE_ID)
                .format(CURRENT_DATE_FORMATTER);

        prompt.append("""
            Пользователь задал вопрос по актуальным объявлениям Telegram-канала ЦКиТа.
    
            Тебе переданы внутренние источники знаний:
            1. FAQ_ЦКИТ — стабильная база частых вопросов и официальных ответов.
            2. ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА — свежие объявления, дедлайны, вакансии и новости из Telegram-канала.
            
            Текущая дата: %s.
            Часовой пояс: Asia/Almaty.
    
            Строгие правила:
            - отвечай только на основе FAQ_ЦКИТ и ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА;
            - не добавляй факты из своих общих знаний;
            - не выдумывай сроки, кабинеты, контакты, вакансии, компании, дедлайны, даты, форматы, требования или процедуры;
            - если информация есть в FAQ, используй её как стабильное официальное объяснение;
            - если информация есть в Telegram-постах, используй её как актуальную информацию;
            - если вопрос состоит из нескольких частей, отвечай по каждой части отдельно;
            - если по одной части вопроса информация есть, а по другой нет, честно раздели это в ответе;
            - если в переданных источниках нет ответа на конкретную часть вопроса, напиши: "В доступных источниках этой информации нет";
            - если в обоих источниках нет подтверждённой информации по вопросу, посоветуй обратиться в Центр карьеры и трудоустройства;
            - не говори, что у тебя нет базы, если FAQ или посты были переданы;
            - не упоминай Platonus, деканат, кафедру, кураторов или другие системы, если их нет в источниках;
            - если пользователь пишет на русском языке, то используй русский язык в ответе;
            - если пользователь пишет на английском языке, то используй английский язык в ответе;
            - если пользователь пишет на казахском языке, то используй казахский язык в ответе;
            - если вопрос про вакансии, не делай главным ответом производственную практику;
            - если вопрос про практику, не перечисляй вакансии без необходимости;
            - если вопрос про дедлайны, разделяй сроки по категориям;
            - не добавляй блок "Следующий шаг" в каждом ответе;
            - если совет действительно уместен, добавь его коротко в конце;
            - можно использовать только Telegram HTML-теги <b>, <i>;
            - не используй Markdown.
            
            Правила актуальности:
            - более новые посты считаются актуальнее старых;
            - если новый пост продлевает срок, текущим сроком является новый срок;
            - старый срок можно упомянуть только как предыдущий срок, например: "раньше было до 25 августа";
            - если новый пост отменяет условие старого поста, обязательно учитывай отмену. Например, если в каком-то мероприятии указывался бонус за присутствие, а в следующем посте пишут, что бонуса не будет, то так и пиши. Типа в одном посте писалось, что за присутствие будут раздавать напитки, например Hennessy, а в следующем посту пишут, что по нему отмена, то ты пишешь пользователю что раздача Hennessy на мероприятии отменена;
            - не представляй отменённые или заменённые условия как актуальные.
                
            Правила по дедлайнам:
            - дедлайны и важные даты выделяй HTML-тегами <b><i>...</i></b>;
            - если пользователь спрашивает про "актуальные дедлайны", "сейчас", "текущие сроки", сначала перечисляй только актуальные и будущие сроки;
            - истёкшие дедлайны выноси отдельно в блок "Истёкшие или неактуальные сроки";
            - если дедлайн уже раньше текущей даты, обязательно напиши, что срок уже истёк;
            - если дедлайн указан без года, например "30 июня", сравнивай его с текущим годом, если из текста поста не следует другой год;
            - если дата невозможная или странная, например "32 июня" или "33 июня", не считай её актуальной датой и обязательно напиши, что дату нужно перепроверить;
            - если дедлайн указан неточно, например "не скоро", "в любое время", "как душа пожелает", не превращай его в точную дату;
            - если дедлайн не указан, так и напиши: "дедлайн не указан".
                
            Правила по производственной практике:
            - если в постах есть старый срок сдачи документов и более новый продлённый срок, главным указывай продлённый срок;
            - для документов по практике формулируй так: "актуальный срок — <b><i>до 7 сентября</i></b>, ранее указывалось до 25 августа";
            - не начинай ответ со старой даты, если есть более новое объявление о продлении;
            - документы, отчётность и порядок оформления бери из FAQ, если они там есть;
            - сроки, дедлайны и свежие изменения бери из Telegram-постов, если они там есть;
            - период прохождения практики и срок сдачи документов — это разные вещи, не смешивай их.
            """.formatted(currentDateText));

        prompt.append("\nРезультат анализа запроса:\n");
        prompt.append("intent: ").append(analysis.intent()).append("\n");
        prompt.append("topic: ").append(analysis.topic()).append("\n");
        prompt.append("needsChannelPosts: ").append(analysis.needsChannelPosts()).append("\n");
        prompt.append("needsFaq: ").append(analysis.needsFaq()).append("\n");
        prompt.append("needsDeadlines: ").append(analysis.needsDeadlines()).append("\n");

        prompt.append("\nВопрос пользователя:\n");
        prompt.append(userMessage).append("\n\n");

        appendFaqContext(prompt, faqEntries);
        appendTelegramPostsContext(prompt, posts);

        prompt.append("""
                
                Сформируй итоговый ответ пользователю.
                Ответ должен быть полезным, но без выдуманных данных.
                Если часть информации не найдена, прямо скажи об этом.
                """);

        return prompt.toString();
    }

    private void appendFaqContext(StringBuilder prompt, List<FaqEntry> faqEntries) {
        prompt.append("FAQ_ЦКИТ:\n");

        if (faqEntries.isEmpty()) {
            prompt.append("FAQ-записи не найдены или не требуются для этого вопроса.\n\n");
            return;
        }

        for (int i = 0; i < faqEntries.size(); i++) {
            FaqEntry entry = faqEntries.get(i);

            prompt.append("FAQ ").append(i + 1).append(":\n");
            prompt.append("Категория: ").append(entry.getCategory()).append("\n");
            prompt.append("Вопрос: ").append(entry.getQuestion()).append("\n");
            prompt.append("Краткий ответ: ").append(entry.getShortAnswer()).append("\n");
            prompt.append("Полный ответ:\n");
            prompt.append(entry.getFullAnswer()).append("\n\n");
        }
    }

    private void appendTelegramPostsContext(StringBuilder prompt, List<TelegramChannelPost> posts) {
        prompt.append("ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА:\n");

        if (posts.isEmpty()) {
            prompt.append("Релевантные посты Telegram-канала не найдены или не требуются для этого вопроса.\n\n");
            return;
        }

        for (int i = 0; i < posts.size(); i++) {
            TelegramChannelPost post = posts.get(i);

            prompt.append("ПОСТ ").append(i + 1).append(":\n");
            prompt.append("Дата: ").append(formatPostDate(post)).append("\n");
            prompt.append("Текст:\n");
            prompt.append(post.getText()).append("\n\n");
        }
    }

    private String buildNoConfirmedInformationAnswer() {
        return """
                У меня нет подтверждённой информации по этому вопросу в FAQ и в сохранённых постах Telegram-канала ЦКиТ.

                Лучше обратиться в Центр карьеры и трудоустройства для уточнения.
                """.trim();
    }

    private String buildDirectFallbackAnswer(List<FaqEntry> faqEntries, List<TelegramChannelPost> posts) {
        StringBuilder answer = new StringBuilder();

        answer.append("AI-модель сейчас не смогла обработать найденные источники.\n\n");

        if (!faqEntries.isEmpty()) {
            answer.append("<b>Найденные FAQ-записи:</b>\n");

            for (int i = 0; i < Math.min(faqEntries.size(), 5); i++) {
                FaqEntry entry = faqEntries.get(i);

                answer.append("<b>")
                        .append(i + 1)
                        .append(". ")
                        .append(escapeTelegramHtml(entry.getQuestion()))
                        .append("</b>\n");

                answer.append(escapeTelegramHtml(entry.getShortAnswer()))
                        .append("\n\n");
            }
        }

        if (!posts.isEmpty()) {
            answer.append("<b>Найденные посты Telegram-канала:</b>\n");

            for (int i = 0; i < Math.min(posts.size(), 5); i++) {
                TelegramChannelPost post = posts.get(i);

                answer.append("<b>").append(i + 1).append(". Объявление</b>\n");
                answer.append(escapeTelegramHtml(shortenText(post.getText()))).append("\n");

                String dateText = formatPostDate(post);

                if (dateText != null) {
                    answer.append("<i>Дата: ").append(dateText).append("</i>\n");
                }

                answer.append("\n");
            }
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

    private boolean shouldUseFaqEntries(ChannelQueryAnalysis analysis) {
        if (analysis == null) {
            return false;
        }

        if (analysis.needsFaq()) {
            return true;
        }

        return switch (analysis.intent()) {
            case FAQ, PRACTICE -> true;
            case VACANCY, DEADLINE, GENERAL_UPDATES, GENERAL_CHAT, UNKNOWN -> false;
        };
    }

    private boolean shouldUseChannelPosts(ChannelQueryAnalysis analysis) {
        if (analysis == null) {
            return false;
        }

        if (analysis.needsChannelPosts() || analysis.needsDeadlines()) {
            return true;
        }

        return switch (analysis.intent()) {
            case VACANCY, DEADLINE, GENERAL_UPDATES -> true;
            case PRACTICE, FAQ, GENERAL_CHAT, UNKNOWN -> false;
        };
    }
}