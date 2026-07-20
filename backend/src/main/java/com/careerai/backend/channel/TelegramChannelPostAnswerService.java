package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import com.careerai.backend.faq.FaqEntry;
import com.careerai.backend.faq.FaqEntryService;
import com.careerai.backend.semantic.EmbeddingResult;
import com.careerai.backend.semantic.FaqSemanticSearchService;
import com.careerai.backend.semantic.SemanticQueryEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

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

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostAnswerService.class);

    private static final int MAX_CONTEXT_POSTS = 8;

    private static final int MAX_EXHAUSTIVE_CONTEXT_POSTS = 30;

    private static final ZoneId ASTANA_ZONE_ID = ZoneId.of("Asia/Almaty");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final DateTimeFormatter CURRENT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TelegramChannelPostRepository repository;
    private final ChannelQueryAnalyzer queryAnalyzer;
    private final LlmProvider llmProvider;
//    private final TelegramChannelPostStructuredSearchService structuredSearchService;
    private final TelegramChannelPostHybridSearchService hybridSearchService;
    private final FaqEntryService faqEntryService;
    private final FaqSemanticSearchService faqSemanticSearchService;
    private final SemanticQueryEmbeddingService semanticQueryEmbeddingService;

    public TelegramChannelPostAnswerService(
            TelegramChannelPostRepository repository,
            ChannelQueryAnalyzer queryAnalyzer,
            LlmProvider llmProvider,
//            TelegramChannelPostStructuredSearchService structuredSearchService,
            TelegramChannelPostHybridSearchService hybridSearchService,
            FaqEntryService faqEntryService,
            FaqSemanticSearchService faqSemanticSearchService,
            SemanticQueryEmbeddingService semanticQueryEmbeddingService
    ) {
        this.repository = repository;
        this.queryAnalyzer = queryAnalyzer;
        this.llmProvider = llmProvider;
//        this.structuredSearchService = structuredSearchService;
        this.hybridSearchService = hybridSearchService;
        this.faqEntryService = faqEntryService;
        this.faqSemanticSearchService = faqSemanticSearchService;
        this.semanticQueryEmbeddingService = semanticQueryEmbeddingService;
    }

    public Optional<String> buildAnswerIfRelevant(String userMessage) {
        ChannelQueryAnalysis analysis = queryAnalyzer.analyze(userMessage);

        boolean shouldUseFaq = shouldUseFaqEntries(analysis);
        boolean shouldUseChannelPosts = shouldUseChannelPosts(analysis);

        if (!shouldUseFaq && !shouldUseChannelPosts) {
            return Optional.empty();
        }

        Optional<EmbeddingResult> queryEmbedding = semanticQueryEmbeddingService.createQueryEmbedding(userMessage);

        List<FaqEntry> faqEntries = shouldUseFaq
                ? findRelevantFaqEntries(queryEmbedding)
                : List.of();

        ChannelPostSearchResult postSearchResult =
                shouldUseChannelPosts
                        ? findRelevantPosts(
                        analysis,
                        queryEmbedding
                )
                        : ChannelPostSearchResult.empty();

        if (faqEntries.isEmpty() && postSearchResult.isEmpty()) {
            return Optional.of(buildNoConfirmedInformationAnswer());
        }

        String ragPrompt = buildCombinedRagPrompt(userMessage, analysis, faqEntries, postSearchResult);

        LlmResponse llmResponse = llmProvider.generateAnswer(ragPrompt);

        if (llmResponse.success()) {
            return Optional.of(llmResponse.text());
        }

        log.warn(
                "Combined RAG answer generation failed. Using direct source fallback. provider={}, model={}, errorType={}",
                llmResponse.provider(),
                llmResponse.model(),
                llmResponse.errorType()
        );

        return Optional.of(buildDirectFallbackAnswer(faqEntries, postSearchResult.allPosts()));
    }

    private ChannelPostSearchResult findRelevantPosts(
            ChannelQueryAnalysis analysis,
            Optional<EmbeddingResult> queryEmbedding
    ) {
        int limit = analysis.resultMode()
                == ChannelResultMode.ALL_MATCHING
                ? MAX_EXHAUSTIVE_CONTEXT_POSTS
                : MAX_CONTEXT_POSTS;

        ChannelPostSearchResult result =
                hybridSearchService.findRelevantPosts(
                        analysis,
                        queryEmbedding,
                        limit
                );

        if (!result.isEmpty()) {
            return result;
        }

        if (analysis.hasScope(
                ChannelContentScope.ALL_UPDATES
        )) {
            List<TelegramChannelPost> latestPosts =
                    repository.findLatestSearchableTextPosts(
                            PageRequest.of(0, limit)
                    );

            if (!latestPosts.isEmpty()) {
                return new ChannelPostSearchResult(
                        List.of(
                                new ChannelPostSearchGroup(
                                        ChannelContentScope.ALL_UPDATES,
                                        latestPosts
                                )
                        )
                );
            }
        }

        return ChannelPostSearchResult.empty();
    }

    /**
     * Сначала пытается найти релевантные FAQ через Semantic Search.
     *
     * Если Semantic Search выключен, сломан, не проиндексирован
     * или не нашёл уверенных совпадений, возвращает все FAQ
     * по стабильной логике до Semantic.
     */
    private List<FaqEntry> findRelevantFaqEntries(Optional<EmbeddingResult> queryEmbedding) {
        Optional<List<FaqEntry>> semanticResult = queryEmbedding.flatMap(faqSemanticSearchService::findRelevantEntries);

        if (semanticResult.isPresent()
                && !semanticResult.get().isEmpty()) {
            return semanticResult.get();
        }

        List<FaqEntry> fallbackEntries = faqEntryService.findActiveEntries();

        log.info(
                "FAQ semantic search unavailable or empty. Using stable FAQ fallback. entries={}",
                fallbackEntries.size()
        );

        return fallbackEntries;
    }

    private String buildCombinedRagPrompt(String userMessage, ChannelQueryAnalysis analysis, List<FaqEntry> faqEntries, ChannelPostSearchResult postSearchResult) {
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
            - учитывай contentScope как строгое ограничение категорий;
            - при contentScope=EVENTS не упоминай вакансии;
            - при contentScope=VACANCIES не упоминай мероприятия;
            - при contentScope=PRACTICE не перечисляй несвязанные вакансии;
            - можно использовать только Telegram HTML-теги <b>, <i>;
            - не используй Markdown.
            
            Правила актуальности и противоречий:
            - Telegram-посты переданы от новых к старым;
            - более новые посты считаются актуальнее старых;
            - если несколько постов относятся к одному мероприятию, вакансии или сроку, более новый пост имеет приоритет;
            - если новый пост отменяет или исправляет отдельное условие старого поста, используй новое условие;
            - фразы по типу "не будет", "отменено", "отказались", "раздавать не будем", "больше не действует" отменяют соответствующее более старое обещание;
            - если новый пост продлевает срок, текущим сроком является новый срок;
            - старый срок можно упомянуть только как предыдущий срок, например: "раньше было до 25 августа";
            - не представляй отменённые или заменённые условия как актуальные;
            - если отменено только одно условие, не отменяй автоматически остальные условия;
            - например, если сначала обещали подарок или напиток, а затем сообщили, что его не будет, ответ должен прямо сказать, что раздача отменена;
            - остальные подтверждённые условия мероприятия сохраняются, если они отдельно не отменялись;
            - точную область изменения запрещено расширять до более широкой категории;
            - если отменена раздача конкретного предмета, напитка, подарка или бонуса, считай отменённым только этот конкретный объект;
            - например, отмена раздачи Hennessy не означает, что отменены все напитки, подарки или активности мероприятия;
            - или, например, отмена раздачи Coca-Cola не означает, что отменены все бонусы, включая SOCIAL GPA;
            - не заменяй формулировку "отменена раздача конкретного предмета" на "ничего раздавать не будут";
            - если пользователь спрашивает, будут ли что-либо раздавать, а подтверждена только отмена одного конкретного предмета, назови этот предмет и скажи, что по другим подаркам подтверждённой информации нет;
            - не утверждай отсутствие других подарков, напитков или бонусов, если этого прямо нет в источниках.
            - перед формированием ответа мысленно сопоставь посты об одном событии и разреши все найденные противоречия;
            - каждая ПОДТВЕРЖДЁННАЯ_ЦЕПОЧКА_ПУБЛИКАЦИЙ является отдельной независимой темой;
            - исходная публикация и более новое уточнение внутри одной цепочки относятся только друг к другу;
            - связь UPDATE означает дополнение исходной информации;
            - связь CORRECTION означает исправление ранее опубликованных данных;
            - связь CANCELLATION означает отмену всей публикации или отдельного условия, указанного в точной области изменения;
            - запрещено переносить уточнение, исправление или отмену из одной цепочки в другую;
            - если пользователь упомянул конкретный предмет, компанию, напиток, условие или название, используй только ту цепочку, где это упоминание присутствует в тексте или в точной области изменения;
            - не перечисляй другие цепочки, если пользователь задал вопрос только об одном конкретном мероприятии или условии;
            - связь CANCELLATION применяется только к исходному postId, указанному внутри той же цепочки;
            - при CANCELLATION не отменяй автоматически всё мероприятие, если в точной области изменения отменено только одно условие;
            - внутренние postId нужны только для сопоставления источников и не должны показываться пользователю.
            
            Правила по нескольким категориям:
            - contentScopes перечислены в порядке, который запросил пользователь;
            - отвечай отдельным разделом по каждой категории;
            - не смешивай вакансии и мероприятия в одном списке;
            - сначала покажи первую категорию, затем вторую;
            - при resultMode=ALL_MATCHING не пропускай найденные основные записи;
            - не представляй пост-уточнение как отдельную вакансию или отдельное мероприятие;
            - уточнения, отмены и исправления нужно присоединять к связанному основному объявлению. Например, отмену о раздаче напитка или еще чего-то относить только к мероприятию, к которому он относится;
            - не переноси отмену или условие с одного мероприятия на другое;
            - если связь уточнения с конкретным событием не подтверждена, укажи уточнение отдельно, а не приписывай его другому мероприятию.
                
            Правила по дедлайнам:
            - дедлайны и важные даты выделяй HTML-тегами <b><i>...</i></b>;
            - если пользователь спрашивает про "актуальные дедлайны", "сейчас", "текущие сроки", сначала перечисляй только актуальные и будущие сроки;
            - истёкшие дедлайны выноси отдельно в блок "Истёкшие или неактуальные сроки";
            - если дедлайн уже раньше текущей даты, обязательно напиши, что срок уже истёк;
            - если дедлайн указан без года, например "30 июня", сравнивай его с текущим годом, если из текста поста не следует другой год;
            - если дата невозможная или странная, например "32 июня" или "33 июня", не считай её актуальной датой и обязательно напиши, что дату нужно перепроверить;
            - если дедлайн указан неточно, например "не скоро", "в любое время", "как душа пожелает", не превращай его в точную дату;
            - если дедлайн не указан, так и напиши: "дедлайн не указан".
            
            Правила по спискам вакансий:
            - если пользователь просит все вакансии из канала, можно перечислить все найденные записи;
            - не называй весь список актуальным, если среди записей есть истёкшие, сомнительные или некорректные сроки;
            - разделяй вакансии на блоки:
              1. актуальные или с будущим дедлайном;
              2. вакансии без точного дедлайна;
              3. истёкшие или содержащие некорректную дату;
            - формулировки "в любое время", "не скоро", "как душа пожелает" не подтверждают актуальность вакансии;
            - невозможную дату нельзя считать действующим дедлайном.
                
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
        prompt.append("contentScopes: ")
                .append(analysis.contentScopes())
                .append("\n");

        prompt.append("resultMode: ")
                .append(analysis.resultMode())
                .append("\n");
        prompt.append("needsChannelPosts: ").append(analysis.needsChannelPosts()).append("\n");
        prompt.append("needsFaq: ").append(analysis.needsFaq()).append("\n");
        prompt.append("needsDeadlines: ").append(analysis.needsDeadlines()).append("\n");

        prompt.append("\nВопрос пользователя:\n");
        prompt.append(userMessage).append("\n\n");

        appendFaqContext(prompt, faqEntries);
        appendTelegramPostsContext(prompt, postSearchResult);

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

    private void appendTelegramPostsContext(
            StringBuilder prompt,
            ChannelPostSearchResult searchResult
    ) {
        prompt.append("ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА:\n");

        if (searchResult == null
                || searchResult.isEmpty()) {
            prompt.append(
                    "Релевантные посты Telegram-канала "
                            + "не найдены или не требуются.\n\n"
            );
            return;
        }

        Set<Long> relatedPostIds =
                collectRelatedPostIds(
                        searchResult.relations()
                );

        int globalPostNumber = 1;

        for (ChannelPostSearchGroup group
                : searchResult.groups()) {

            prompt.append("КАТЕГОРИЯ: ")
                    .append(group.scope())
                    .append("\n");

            prompt.append(
                    "Обычные публикации внутри категории "
                            + "расположены от новых к старым.\n\n"
            );

            int standalonePostCount = 0;

            for (TelegramChannelPost post
                    : group.posts()) {

                if (post == null
                        || post.getId() == null
                        || relatedPostIds.contains(
                        post.getId()
                )) {
                    continue;
                }

                prompt.append("ПОСТ ")
                        .append(globalPostNumber++)
                        .append(":\n");

                prompt.append("Внутренний postId: ")
                        .append(post.getId())
                        .append("\n");

                prompt.append("Дата: ")
                        .append(formatPostDate(post))
                        .append("\n");

                prompt.append("Текст:\n");
                prompt.append(post.getText())
                        .append("\n\n");

                standalonePostCount++;
            }

            if (standalonePostCount == 0) {
                prompt.append(
                        "Все найденные публикации этой категории "
                                + "входят в подтверждённые цепочки ниже.\n\n"
                );
            }
        }

        appendTelegramPostRelationThreadsContext(
                prompt,
                searchResult.relations()
        );
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

    /**
     * Передаёт связанные публикации как отдельные цепочки.
     *
     * Все обновления, исправления и отмены одного исходного
     * поста объединяются внутри одной цепочки.
     */
    private void appendTelegramPostRelationThreadsContext(
            StringBuilder prompt,
            List<TelegramChannelPostRelation> relations
    ) {
        if (relations == null || relations.isEmpty()) {
            return;
        }

        Map<Long, List<TelegramChannelPostRelation>> relationsByTargetPostId = new LinkedHashMap<>();

        for (TelegramChannelPostRelation relation : relations) {
            if (!hasValidRelationPosts(relation)) {
                continue;
            }

            Long targetPostId = relation.getTargetPost().getId();

            relationsByTargetPostId
                    .computeIfAbsent(
                            targetPostId,
                            ignored -> new ArrayList<>()
                    )
                    .add(relation);
        }

        if (relationsByTargetPostId.isEmpty()) {
            return;
        }

        prompt.append(
                "ПОДТВЕРЖДЁННЫЕ_ЦЕПОЧКИ_ПУБЛИКАЦИЙ:\n"
        );

        prompt.append(
                "Каждая цепочка относится к одному исходному "
                        + "объявлению. Все изменения внутри цепочки "
                        + "нужно объединить с исходной публикацией. "
                        + "Запрещено представлять изменения как "
                        + "самостоятельные мероприятия.\n\n"
        );

        int threadNumber = 1;

        for (List<TelegramChannelPostRelation> targetRelations
                : relationsByTargetPostId.values()) {

            TelegramChannelPost targetPost =
                    targetRelations
                            .getFirst()
                            .getTargetPost();

            prompt.append("ЦЕПОЧКА ")
                    .append(threadNumber++)
                    .append(":\n");

            prompt.append(
                    "Все изменения ниже относятся только "
                            + "к исходному postId="
            );

            prompt.append(targetPost.getId())
                    .append(".\n\n");

            appendTelegramPostContext(
                    prompt,
                    "ИСХОДНАЯ ПУБЛИКАЦИЯ",
                    targetPost
            );

            int changeNumber = 1;

            for (TelegramChannelPostRelation relation
                    : targetRelations) {

                TelegramChannelPost sourcePost =
                        relation.getSourcePost();

                prompt.append("ИЗМЕНЕНИЕ ")
                        .append(changeNumber++)
                        .append(":\n");

                appendTelegramPostContext(
                        prompt,
                        "БОЛЕЕ НОВАЯ ПУБЛИКАЦИЯ",
                        sourcePost
                );

                prompt.append("Тип связи: ")
                        .append(relation.getRelationType())
                        .append("\n");

                prompt.append("Точная область изменения: ")
                        .append(relation.getReason())
                        .append("\n");

                appendRelationApplicationRule(
                        prompt,
                        relation
                );

                prompt.append("\n");
            }

            prompt.append(
                    "Итог: представь эту цепочку как одно "
                            + "объявление с учётом всех перечисленных "
                            + "изменений. Не создавай отдельное "
                            + "мероприятие для каждого изменения.\n\n"
            );
        }
    }

    /**
     * Собирает идентификаторы постов,
     * входящих в подтверждённые связи.
     */
    private Set<Long> collectRelatedPostIds(
            List<TelegramChannelPostRelation> relations
    ) {
        Set<Long> postIds =
                new LinkedHashSet<>();

        if (relations == null) {
            return postIds;
        }

        for (TelegramChannelPostRelation relation
                : relations) {

            if (relation == null) {
                continue;
            }

            TelegramChannelPost sourcePost =
                    relation.getSourcePost();

            TelegramChannelPost targetPost =
                    relation.getTargetPost();

            if (sourcePost != null
                    && sourcePost.getId() != null) {
                postIds.add(sourcePost.getId());
            }

            if (targetPost != null
                    && targetPost.getId() != null) {
                postIds.add(targetPost.getId());
            }
        }

        return postIds;
    }

    /**
     * Добавляет одну Telegram-публикацию
     * во внутренний RAG-контекст.
     */
    private void appendTelegramPostContext(
            StringBuilder prompt,
            String heading,
            TelegramChannelPost post
    ) {
        prompt.append(heading)
                .append(":\n");

        prompt.append("postId: ")
                .append(post.getId())
                .append("\n");

        prompt.append("Дата публикации: ")
                .append(formatPostDate(post))
                .append("\n");

        prompt.append("Текст:\n")
                .append(
                        post.getText() == null
                                ? ""
                                : post.getText()
                )
                .append("\n\n");
    }

    private boolean hasValidRelationPosts(TelegramChannelPostRelation relation) {
        return relation != null
                && relation.getSourcePost() != null
                && relation.getSourcePost().getId() != null
                && relation.getTargetPost() != null
                && relation.getTargetPost().getId() != null;
    }

    /**
     * Объясняет LLM, как применять конкретный тип связи.
     */
    private void appendRelationApplicationRule(
            StringBuilder prompt,
            TelegramChannelPostRelation relation
    ) {
        TelegramChannelPostRelationType relationType =
                relation.getRelationType();

        if (relationType == null) {
            prompt.append(
                    "Примени изменение только к исходной "
                            + "публикации этой цепочки.\n"
            );
            return;
        }

        switch (relationType) {
            case UPDATE -> prompt.append(
                    "Это дополнение. Добавь новые сведения "
                            + "к исходному объявлению, не создавая "
                            + "отдельное мероприятие.\n"
            );

            case CORRECTION -> prompt.append(
                    "Это исправление. Замени соответствующие "
                            + "старые сведения новыми, остальные "
                            + "условия сохрани.\n"
            );

            case CANCELLATION -> prompt.append(
                    "Это точечная отмена. Отмени только то, "
                            + "что прямо указано в области изменения. "
                            + "Не отменяй другие подарки, бонусы, "
                            + "условия или само мероприятие.\n"
            );
        }

        prompt.append(
                "Применяй изменение только к исходному postId="
        );

        prompt.append(
                relation.getTargetPost().getId()
        ).append(".\n");
    }
}