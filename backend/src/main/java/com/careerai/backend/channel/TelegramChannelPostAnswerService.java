package com.careerai.backend.channel;

import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.ai.LlmResponse;
import com.careerai.backend.answer.*;
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
import java.time.Clock;
import java.time.OffsetDateTime;
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
    private final AnswerExecutionPlanner executionPlanner;
    private final StructuredChannelAnswerBuilder structuredAnswerBuilder;
    private final TelegramChannelPostSearchEligibility searchEligibility;
    private final ChannelPostTimelineSearchService timelineSearchService;
    private final Clock clock;
    private final EventTemporalEvidenceService temporalEvidence;
    private final EventContextFilter eventContextFilter;
    private final AnswerCalendarGrounding calendarGrounding;

    public TelegramChannelPostAnswerService(
            TelegramChannelPostRepository repository,
            ChannelQueryAnalyzer queryAnalyzer,
            LlmProvider llmProvider,
//            TelegramChannelPostStructuredSearchService structuredSearchService,
            TelegramChannelPostHybridSearchService hybridSearchService,
            FaqEntryService faqEntryService,
            FaqSemanticSearchService faqSemanticSearchService,
            SemanticQueryEmbeddingService semanticQueryEmbeddingService,
            AnswerExecutionPlanner executionPlanner,
            StructuredChannelAnswerBuilder structuredAnswerBuilder,
            TelegramChannelPostSearchEligibility searchEligibility,
            ChannelPostTimelineSearchService timelineSearchService,
            Clock clock,
            EventTemporalEvidenceService temporalEvidence,
            EventContextFilter eventContextFilter,
            AnswerCalendarGrounding calendarGrounding
    ) {
        this.repository = repository;
        this.queryAnalyzer = queryAnalyzer;
        this.llmProvider = llmProvider;
//        this.structuredSearchService = structuredSearchService;
        this.hybridSearchService = hybridSearchService;
        this.faqEntryService = faqEntryService;
        this.faqSemanticSearchService = faqSemanticSearchService;
        this.semanticQueryEmbeddingService = semanticQueryEmbeddingService;
        this.executionPlanner = executionPlanner;
        this.structuredAnswerBuilder = structuredAnswerBuilder;
        this.searchEligibility = searchEligibility;
        this.timelineSearchService = timelineSearchService;
        this.clock = clock;
        this.temporalEvidence = temporalEvidence;
        this.eventContextFilter = eventContextFilter;
        this.calendarGrounding = calendarGrounding;
    }

    public Optional<String> buildAnswerIfRelevant(String userMessage) {
        long startedAt = System.nanoTime();
        ChannelQueryAnalysis analysis = queryAnalyzer.analyze(userMessage);

        if (analysis.hasEventDateRange() && !analysis.hasValidEventDateRange()) {
            return completed(AnswerExecutionMode.GENERATIVE_RAG, AnswerLanguage.detect(userMessage).select(
                    "Укажи корректный период проведения мероприятий: начальная дата должна быть не позже конечной.",
                    "Іс-шаралар кезеңін дұрыс көрсетіңіз: басталу күні аяқталу күнінен кейін болмауы керек.",
                    "Please specify a valid event date range: the start must not be after the end."), startedAt);
        }

        if (analysis.timeScope() == ChannelTimeScope.CUSTOM_RANGE
                && new ChannelQueryWindowResolver(clock).resolve(analysis).isEmpty()) {
            return completed(AnswerExecutionMode.GENERATIVE_RAG, AnswerLanguage.detect(userMessage).select(
                    "Укажи корректный период публикаций: начальную и конечную дату, например с 1 по 15 сентября 2026 года.",
                    "Жарияланымдар кезеңінің басталу және аяқталу күнін көрсетіңіз, мысалы 2026 жылғы 1–15 қыркүйек.",
                    "Please specify a valid publication date range, for example September 1–15, 2026."), startedAt);
        }

        Optional<String> direct = executionPlanner.directAnswer(userMessage, analysis);
        if (direct.isPresent()) return completed(AnswerExecutionMode.DIRECT_ANSWER, direct.get(), startedAt);

        if (analysis.intent() == ChannelSearchIntent.UNKNOWN) {
            // A router outage must not turn a career question into an ungrounded general answer.
            analysis = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                    List.of(ChannelContentScope.ALL_UPDATES), ChannelResultMode.RELEVANT, true, true, false);
        }

        boolean shouldUseFaq = shouldUseFaqEntries(analysis);
        boolean shouldUseChannelPosts = shouldUseChannelPosts(analysis);

        if (!shouldUseFaq && !shouldUseChannelPosts) {
            return Optional.empty();
        }

        if (shouldUseFaq && !shouldUseChannelPosts) {
            Optional<FaqEntry> exactFaq = executionPlanner.exactFaq(userMessage, analysis, faqEntryService.findActiveEntries());
            if (exactFaq.isPresent()) {
                return completed(AnswerExecutionMode.DIRECT_FAQ,
                        escapeTelegramHtml(exactFaq.get().getFullAnswer()), startedAt);
            }
        }

        if (executionPlanner.canTryStructuredChannel(userMessage, analysis)) {
            // The whitelist ensures no keyword constraint; clear the LLM topic to avoid accidental filtering.
            ChannelQueryAnalysis simpleList = new ChannelQueryAnalysis(analysis.intent(), null,
                    analysis.contentScopes(), analysis.resultMode(), true, false, false);
            ChannelPostSearchResult structured = eventContextFilter.filter(findRelevantPosts(simpleList, Optional.empty(), userMessage), simpleList);
            if (!structured.relationContextComplete()) {
                return completed(AnswerExecutionMode.GENERATIVE_RAG,
                        buildIncompleteRelationsAnswer(userMessage, structured), startedAt);
            }
            boolean everyScopePresent = structured.groups().stream().map(ChannelPostSearchGroup::scope).toList()
                    .containsAll(simpleList.contentScopes());
            Optional<String> rendered = everyScopePresent
                    ? structuredAnswerBuilder.build(userMessage, structured, MAX_EXHAUSTIVE_CONTEXT_POSTS)
                    : Optional.empty();
            if (rendered.isPresent()) return completed(AnswerExecutionMode.STRUCTURED_CHANNEL, rendered.get(), startedAt);
        }

        Optional<EmbeddingResult> queryEmbedding = analysis.requiresTimelineSearch() && !shouldUseFaq
                ? Optional.empty() : semanticQueryEmbeddingService.createQueryEmbedding(userMessage);

        List<FaqEntry> faqEntries = shouldUseFaq
                ? findRelevantFaqEntries(queryEmbedding)
                : List.of();

        ChannelPostSearchResult postSearchResult =
                shouldUseChannelPosts
                        ? findRelevantPosts(
                        analysis,
                        queryEmbedding, userMessage
                )
                        : ChannelPostSearchResult.empty();

        postSearchResult = eventContextFilter.filter(postSearchResult, analysis);

        if (!postSearchResult.relationContextComplete()) {
            return completed(AnswerExecutionMode.GENERATIVE_RAG,
                    buildIncompleteRelationsAnswer(userMessage, postSearchResult), startedAt);
        }

        if (faqEntries.isEmpty() && postSearchResult.isEmpty()) {
            return completed(AnswerExecutionMode.GENERATIVE_RAG, buildNoConfirmedInformationAnswer(userMessage), startedAt);
        }

        String ragPrompt = buildCombinedRagPrompt(userMessage, analysis, faqEntries, postSearchResult);

        LlmResponse llmResponse;
        try {
            llmResponse = llmProvider.generateAnswer(ragPrompt);
        } catch (RuntimeException exception) {
            log.warn("RAG generation unavailable. type={}", exception.getClass().getSimpleName());
            llmResponse = null;
        }

        if (llmResponse != null && llmResponse.success()
                && llmResponse.text() != null && !llmResponse.text().isBlank()) {
            String answer = llmResponse.text();
            if ((analysis.hasScope(ChannelContentScope.EVENTS) || analysis.needsDeadlines())
                    && !calendarGrounding.supported(answer, postSearchResult.allPosts(), faqEntries)) {
                log.warn("Generated calendar date was not grounded in source content; returning dated source evidence");
                answer = calendarGrounding.sourceDates(userMessage, postSearchResult.allPosts());
            }
            return completed(AnswerExecutionMode.GENERATIVE_RAG,
                    timelineHeading(userMessage, analysis)
                            + AnswerSourceFormatter.append(userMessage, answer, postSearchResult), startedAt);
        }

        log.warn(
                "Combined RAG answer generation failed. Returning caution and source links. provider={}, model={}, errorType={}",
                llmResponse == null ? "unknown" : llmResponse.provider(),
                llmResponse == null ? "unknown" : llmResponse.model(),
                llmResponse == null ? "unknown" : llmResponse.errorType()
        );

        return completed(AnswerExecutionMode.GENERATIVE_RAG,
                buildProviderFailureAnswer(userMessage, analysis, faqEntries, postSearchResult.allPosts()), startedAt);
    }

    private Optional<String> completed(AnswerExecutionMode mode, String answer, long startedAt) {
        log.info("Answer execution completed. mode={}, elapsedMs={}", mode, (System.nanoTime() - startedAt) / 1_000_000);
        return Optional.of(answer);
    }

    private String buildIncompleteRelationsAnswer(String question, ChannelPostSearchResult sources) {
        AnswerLanguage language = AnswerLanguage.detect(question);
        StringBuilder answer = new StringBuilder(language.select(
                "Не удалось собрать все связанные уточнения и отмены. Поэтому я не могу подтвердить условия этих публикаций. Проверь полную историю в канале или уточни информацию в Центре карьеры и трудоустройства.",
                "Барлық байланысты нақтылаулар мен күшін жою хабарламаларын жинау мүмкін болмады. Сондықтан бұл жарияланымдардың шарттарын растай алмаймын. Арнадағы толық тарихты тексеріңіз немесе Мансап және жұмыспен қамту орталығына хабарласыңыз.",
                "Some related updates or cancellations could not be included. I cannot confirm these publications' conditions. Please check the full channel history or contact the Career and Employment Center."));
        List<String> links = sources.allPosts().stream().map(StructuredChannelAnswerBuilder::sourceUrl)
                .flatMap(Optional::stream).distinct().limit(10).toList();
        if (!links.isEmpty()) {
            answer.append("\n\n").append(language.select("Ссылки на найденные источники:",
                    "Табылған дереккөздерге сілтемелер:", "Links to the retrieved sources:")).append("\n")
                    .append(String.join("\n", links));
        }
        return answer.toString();
    }

    private ChannelPostSearchResult findRelevantPosts(
            ChannelQueryAnalysis analysis,
            Optional<EmbeddingResult> queryEmbedding,
            String userQuestion
    ) {
        int limit = analysis.resultMode()
                == ChannelResultMode.ALL_MATCHING
                ? MAX_EXHAUSTIVE_CONTEXT_POSTS
                : MAX_CONTEXT_POSTS;

        if (analysis.requiresTimelineSearch()) {
            // Never widen an empty date/freshness result to the normal latest-post fallback.
            return timelineSearchService.search(analysis, limit, userQuestion);
        }

        ChannelPostSearchResult result =
                hybridSearchService.findRelevantPosts(
                        analysis,
                        queryEmbedding,
                        limit
                );

        if (analysis.needsDeadlines() || analysis.intent() == ChannelSearchIntent.DEADLINE) {
            result = ChannelPostContextMerger.merge(result,
                    timelineSearchService.searchDeadlineKnowledge(analysis, limit, userQuestion));
        }

        if (!result.isEmpty() || !result.relationContextComplete()) {
            return result;
        }

        if (analysis.hasScope(
                ChannelContentScope.ALL_UPDATES
        )) {
            List<TelegramChannelPost> latestPosts =
                    repository.findLatestSearchableTextPosts(
                            PageRequest.of(0, limit)
                    ).stream().filter(searchEligibility::isSearchable).toList();

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
                .now(clock)
                .format(CURRENT_DATE_FORMATTER);

        prompt.append("""
            Пользователь задал вопрос по актуальным объявлениям Telegram-канала ЦКиТа.
    
            Тебе переданы внутренние источники знаний:
            1. FAQ_ЦКИТ — стабильная база частых вопросов и официальных ответов.
            2. ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА — свежие объявления, дедлайны, вакансии и новости из Telegram-канала.
            
            Текущая дата: %s.
            Часовой пояс: %s.
    
            Строгие правила:
            - отвечай только на основе FAQ_ЦКИТ и ПОСТЫ_ТЕЛЕГРАМ_КАНАЛА;
            - не добавляй факты из своих общих знаний;
            - не выдумывай сроки, кабинеты, контакты, вакансии, компании, дедлайны, даты, форматы, требования или процедуры;
            - если информация есть в FAQ, используй её как стабильное официальное объяснение;
            - сведения из Telegram-постов используй с учётом даты и статуса; сохранённый пост не доказывает актуальность предложения;
            - если вопрос состоит из нескольких частей, отвечай по каждой части отдельно;
            - если по одной части вопроса информация есть, а по другой нет, честно раздели это в ответе;
            - если в переданной выборке нет ответа, напиши: "В найденных источниках не удалось подтвердить эту информацию";
            - отсутствие факта в выборке не доказывает, что его нет во всём канале; не делай такого вывода;
            - рядом с конкретной датой или изменённым условием указывай переданную ссылку на подтверждающий пост;
            - используй только ссылки из источников, не выдумывай URL и номера сообщений;
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
            - истёкшие публикации могут быть переданы как свидетельство ранее установленного срока;
            - для вопросов "до какого числа", "я опоздал", "какой срок был" сообщай найденную дату, даже если она прошла;
            - учитывай подтверждённые продления в той же цепочке; прошедший срок не означает отсутствие информации;
            - явно различай последний объявленный срок и возможность подать документы сейчас; не обещай приём после срока;
            - общее объявление о документах без указания группы/программы не доказывает единый срок для всех студентов;
            - дедлайны и важные даты выделяй HTML-тегами <b><i>...</i></b>;
            - если пользователь спрашивает про "актуальные дедлайны", "сейчас", "текущие сроки", сначала перечисляй только актуальные и будущие сроки;
            - истёкшие дедлайны выноси отдельно в блок "Истёкшие или неактуальные сроки";
            - только если полная дата дедлайна с подтверждённым годом раньше текущей даты, напиши, что срок уже истёк;
            - год нельзя дописывать из даты публикации или текущего года; если источник не уточняет год, скажи об этом;
            - без подтверждённого года нельзя утверждать ни истечение срока, ни возможность подать сейчас: сравнение только дня и месяца с сегодняшней датой запрещено;
            - подтверждённая администратором дата передаётся отдельно и относится только к указанному типу даты;
            - дата публикации и дата изменения никогда не являются датой события или дедлайном;
            - для списка предстоящих мероприятий используй только подтверждённые даты проведения; записи с UNKNOWN/INVALID выноси отдельно как требующие уточнения;
            - если у мероприятия указан период, не считай его закончившимся по дате начала;
            - регистрационный дедлайн и дата проведения мероприятия не взаимозаменяемы;
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
            - последний объявленный срок может уже пройти; в этом случае назови его последним объявленным и явно сообщи об истечении;
            - не начинай ответ со старой даты, если есть более новое объявление о продлении;
            - документы, отчётность и порядок оформления бери из FAQ, если они там есть;
            - сроки, дедлайны и свежие изменения бери из Telegram-постов, если они там есть;
            - период прохождения практики и срок сдачи документов — это разные вещи, не смешивай их.
            """.formatted(currentDateText, clock.getZone().getId()));

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
        prompt.append("timeScope: ").append(analysis.timeScope()).append("\n");
        prompt.append("freshnessScope: ").append(analysis.freshnessScope()).append("\n");
        prompt.append("publicationDateFromInclusive: ").append(analysis.dateFrom()).append("\n");
        prompt.append("publicationDateToInclusive: ").append(analysis.dateTo()).append("\n");
        prompt.append("eventDateFromInclusive: ").append(analysis.eventDateFrom()).append("\n");
        prompt.append("eventDateToInclusive: ").append(analysis.eventDateTo()).append("\n");
        if (analysis.requiresTimelineSearch()) {
            prompt.append("""
                    Это явная выборка по дате ПУБЛИКАЦИИ/истории, а не список действующих предложений.
                    У каждой истёкшей записи объясни завершение срока полностью на языке пользователя, без русских служебных меток в переводном ответе.
                    Не приглашай откликаться на истёкшие предложения или регистрироваться на прошедшие события.
                    Связанные публикации могут быть вне выбранного периода или статуса: это только контекст уточнения,
                    их нельзя выдавать как отдельные совпадения выбранного периода.
                    CURRENT/EXPIRED/ALL описывает статус на текущую дату, а не на момент публикации.
                    Учитывай исходную дату публикации, не называй редактирование новой публикацией.
                    Не утверждай полноту истории: передано ограниченное число последних совпадений.
                    """);
        }

        prompt.append("\nВопрос пользователя:\n");
        prompt.append(userMessage).append("\n\n");

        appendFaqContext(prompt, faqEntries);
        appendTelegramPostsContext(prompt, postSearchResult);

        prompt.append("""
                
                Сформируй итоговый ответ пользователю.
                Ответ должен быть полезным, но без выдуманных данных.
                Если часть информации не найдена, прямо скажи об этом.
                Служебные даты публикации и изменения не повторяй в ответе, если пользователь не спрашивал именно о времени публикации.
                """);

        prompt.append(AnswerLanguage.detect(userMessage).select(
                "Язык итогового ответа: русский. Переводи служебные метки в обычные понятные формулировки.\n",
                "Жауапты толығымен қазақ тілінде жазыңыз. Дереккөздегі орысша қызметтік белгілерді, соның ішінде мерзім күйін, қазақша түсіндіріңіз.\n",
                "Write the final answer entirely in English. Translate source details and internal status labels; retain only proper names and links in their original form.\n"));

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

                prompt.append("Дата публикации (не дата мероприятия): ")
                        .append(formatPostDate(post))
                        .append("\n");

                prompt.append("Текст:\n");
                appendPublicationStatus(prompt, post);
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

    private String buildNoConfirmedInformationAnswer(String userMessage) {
        return AnswerLanguage.detect(userMessage).select("""
                В найденных источниках не удалось подтвердить ответ на этот вопрос. Это не означает, что объявления по теме не было.

                Лучше обратиться в Центр карьеры и трудоустройства для уточнения.
                """.trim(), "Табылған дереккөздерден бұл сұрақтың жауабын растай алмадым. Бұл тақырып бойынша жарияланым болмағанын білдірмейді. Мансап және жұмыспен қамту орталығына хабарласыңыз.",
                "I found no confirmed information answering this question in the retrieved sources. This does not mean that no announcement exists. Please contact the Career and Employment Center.");
    }

    private String buildProviderFailureAnswer(String question, ChannelQueryAnalysis analysis,
                                              List<FaqEntry> faqEntries, List<TelegramChannelPost> posts) {
        AnswerLanguage language = AnswerLanguage.detect(question);
        StringBuilder answer = new StringBuilder(timelineHeading(question, analysis));
        answer.append(language.select(
                "Сейчас не удалось обработать найденные источники и сопоставить уточнения. Я не могу подтвердить условия по этому вопросу. Попробуй позже или уточни информацию в Центре карьеры и трудоустройства.",
                "Қазір табылған дереккөздерді өңдеу және нақтылауларды салыстыру мүмкін болмады. Бұл сұрақ бойынша шарттарды растай алмаймын. Кейінірек қайталаңыз немесе Мансап және жұмыспен қамту орталығына хабарласыңыз.",
                "The answer service could not process the sources and compare their updates. I cannot confirm the conditions for this question. Please try again later or contact the Career and Employment Center."));
        if (!faqEntries.isEmpty()) {
            answer.append("\n\n").append(language.select(
                    "Официальный список частых вопросов доступен через /faq.",
                    "Ресми жиі қойылатын сұрақтар тізімі /faq арқылы қолжетімді.",
                    "The official frequently asked questions are available through /faq."));
        }
        boolean headingAdded = false;
        for (TelegramChannelPost post : posts.stream().limit(10).toList()) {
            Optional<String> url = StructuredChannelAnswerBuilder.sourceUrl(post);
            if (url.isEmpty()) continue;
            if (!headingAdded) {
                answer.append("\n\n").append(language.select("Источники для проверки:",
                        "Тексеруге арналған дереккөздер:", "Sources to review:"));
                headingAdded = true;
            }
            answer.append("\n").append(url.get());
            boolean expired = post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.EXPIRED
                    || (post.getExpiresAt() != null && !post.getExpiresAt().isAfter(OffsetDateTime.now(clock)));
            if (expired) answer.append(" — ").append(language.select("срок истёк", "мерзімі аяқталған", "expired"));
        }
        return answer.toString();
    }

    private String formatPostDate(TelegramChannelPost post) {
        OffsetDateTime dateTime = getEffectiveDate(post);

        if (dateTime == null) {
            return "не указана";
        }

        return dateTime
                .atZoneSameInstant(clock.getZone())
                .format(DATE_TIME_FORMATTER);
    }

    private OffsetDateTime getEffectiveDate(TelegramChannelPost post) {
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

                String relationReason =
                        relation.getReason();

                if (relationReason == null
                        || relationReason.isBlank()) {
                    prompt.append(
                            "Точная область изменения ещё "
                                    + "не классифицирована.\n"
                    );
                }
                else {
                    prompt.append("Точная область изменения: ")
                            .append(relationReason)
                            .append("\n");
                }

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
        appendPublicationStatus(prompt, post);
    }

    private void appendPublicationStatus(StringBuilder prompt, TelegramChannelPost post) {
        boolean expired = post.getFreshnessStatus() == TelegramChannelPostFreshnessStatus.EXPIRED
                || (post.getExpiresAt() != null && !post.getExpiresAt().isAfter(OffsetDateTime.now(clock)));
        prompt.append("Статус на текущую дату: ").append(expired ? "СРОК ИСТЁК" : post.getFreshnessStatus()).append("\n");
        prompt.append("Исходная дата публикации: ").append(post.getPostedAt() != null ? post.getPostedAt() : post.getCreatedAt()).append("\n");
        prompt.append("Дата изменения (не дата мероприятия): ").append(post.getEditedAt()).append("\n");
        prompt.append("Причина статуса: ").append(post.getFreshnessReason()).append("\n");
        EventTemporalEvidenceService.Evidence evidence = temporalEvidence.inspect(post);
        if (evidence != null) {
            prompt.append("Дата проведения из текста: ").append(evidence.eventDateText()).append("\n");
            prompt.append("Проверка даты проведения: ").append(evidence.eventDate()).append("\n");
            prompt.append("Срок подачи/регистрации из текста: ").append(evidence.deadlineText()).append("\n");
            prompt.append("Проверка срока подачи/регистрации: ").append(evidence.deadlineDate()).append("\n");
        }
        if (post.hasCurrentDateConfirmation()) prompt.append("Подтверждение администратора: ")
                .append(post.getConfirmedDatePurpose()).append(" = ").append(post.getConfirmedDate())
                .append("; граница: ").append(post.getConfirmedDateBoundary()).append("\n");
        StructuredChannelAnswerBuilder.sourceUrl(post).ifPresent(url -> prompt.append("Ссылка на источник: ").append(url).append("\n"));
    }

    private String timelineHeading(String question, ChannelQueryAnalysis analysis) {
        if (!analysis.requiresTimelineSearch()) return "";
        AnswerLanguage language = AnswerLanguage.detect(question);
        String freshness = switch (analysis.freshnessScope()) {
            case CURRENT -> language.select("только текущие публикации", "тек қолданыстағы жарияланымдар", "current publications only");
            case EXPIRED -> language.select("срок предложений истёк", "ұсыныстардың мерзімі аяқталған", "offers have expired");
            case ALL -> language.select("текущие и истёкшие публикации", "қолданыстағы және мерзімі аяқталған жарияланымдар", "current and expired publications");
        };
        return language.select("Выборка по публикациям: ", "Жарияланымдар іріктемесі: ", "Publication selection: ")
                + freshness + ".\n\n";
    }

    private boolean hasValidRelationPosts(TelegramChannelPostRelation relation) {
        return relation != null
                && relation.getSourcePost() != null
                && relation.getSourcePost().getId() != null
                && relation.getTargetPost() != null
                && relation.getTargetPost().getId() != null;
    }

    /**
     * Объясняет LLM, как применять
     * подтверждённую связь постов.
     */
    private void appendRelationApplicationRule(
            StringBuilder prompt,
            TelegramChannelPostRelation relation
    ) {
        TelegramChannelPostRelationType relationType =
                relation.getRelationType();

        if (relationType == null) {
            relationType =
                    TelegramChannelPostRelationType.UNCLASSIFIED;
        }

        switch (relationType) {
            case UNCLASSIFIED -> prompt.append(
                    "Структурная связь между публикациями подтверждена, "
                            + "но точный тип изменения ещё не классифицирован. "
                            + "Сопоставь тексты осторожно и не придумывай "
                            + "отсутствующие изменения.\n"
            );

            case UPDATE -> prompt.append(
                    "Это дополнение. Добавь новые сведения "
                            + "к исходной публикации, не создавая "
                            + "отдельное объявление.\n"
            );

            case CORRECTION -> prompt.append(
                    "Это исправление. Замени только соответствующие "
                            + "старые сведения новыми. Остальную "
                            + "подтверждённую информацию сохрани.\n"
            );

            case CANCELLATION -> prompt.append(
                    "Это отмена. Отмени только то, что прямо "
                            + "подтверждено более новой публикацией. "
                            + "Не расширяй область отмены.\n"
            );

            case MIXED -> prompt.append(
                    "Публикация содержит несколько изменений "
                            + "разных типов. Примени каждое изменение "
                            + "отдельно и только в пределах информации, "
                            + "подтверждённой текстами этой цепочки.\n"
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
