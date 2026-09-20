package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import com.careerai.backend.answer.*;
import com.careerai.backend.faq.*;
import com.careerai.backend.semantic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmartAnswerExecutionTest {
    private final TelegramChannelPostRepository posts = mock(TelegramChannelPostRepository.class);
    private final ChannelQueryAnalyzer analyzer = mock(ChannelQueryAnalyzer.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private final TelegramChannelPostHybridSearchService hybrid = mock(TelegramChannelPostHybridSearchService.class);
    private final FaqEntryService faq = mock(FaqEntryService.class);
    private final FaqSemanticSearchService faqSearch = mock(FaqSemanticSearchService.class);
    private final SemanticQueryEmbeddingService embeddings = mock(SemanticQueryEmbeddingService.class);
    private final StructuredChannelAnswerBuilder builder = mock(StructuredChannelAnswerBuilder.class);
    private final ChannelPostTimelineSearchService timeline = mock(ChannelPostTimelineSearchService.class);
    private final EventTemporalEvidenceService temporal = mock(EventTemporalEvidenceService.class);
    private final EventContextFilter eventFilter = mock(EventContextFilter.class);
    private final AnswerCalendarGrounding grounding = new AnswerCalendarGrounding(
            new MultilingualDateTextParser(new MultilingualMonthDictionary(), new MultilingualDateBoundaryDetector()), Clock.systemUTC());
    private final TelegramChannelPostAnswerService service = new TelegramChannelPostAnswerService(posts, analyzer, llm,
            hybrid, faq, faqSearch, embeddings, new AnswerExecutionPlanner(), builder,
            new TelegramChannelPostSearchEligibility(Clock.systemUTC()), timeline, Clock.systemUTC(), temporal, eventFilter, grounding);

    @BeforeEach void preserveMockSearchContext() {
        when(eventFilter.filter(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void generatedPublicationDateCannotReplaceEventDate() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.RELEVANT, true, false, false);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        var post = new TelegramChannelPost();
        post.setId(9L); post.setTelegramMessageId(9L); post.setChannelUsername("career_test");
        post.setPostedAt(java.time.OffsetDateTime.parse("2026-07-10T10:00:00Z"));
        post.setText("10 августа 2026 года состоится мероприятие в Open Space.");
        when(hybrid.findRelevantPosts(any(), any(), anyInt())).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.EVENTS, List.of(post)))));
        when(llm.generateAnswer(anyString())).thenReturn(LlmResponse.success("Мероприятие состоится 10 июля 2026 года.", "test", "test", 0));
        String answer = service.buildAnswerIfRelevant("Что известно о мероприятии Open Space?").orElseThrow();
        assertTrue(answer.contains("10 августа"), answer);
        assertFalse(answer.contains("10 июля"), answer);
        assertTrue(answer.contains("https://t.me/career_test/9"), answer);
    }

    @Test
    void incompleteEventRangeIsRejectedBeforeRetrieval() {
        when(analyzer.analyze(anyString())).thenReturn(new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.ALL_MATCHING, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.CURRENT, java.time.LocalDate.of(2026,9,1), null,
                java.time.LocalDate.of(2026,9,1), null));
        assertTrue(service.buildAnswerIfRelevant("Мероприятия за период").orElseThrow().contains("корректный период"));
        verifyNoInteractions(llm, hybrid, embeddings, timeline);
    }

    @Test
    void deadlineQuestionReceivesExpiredExtensionEvenWithoutSemanticVectors() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.PRACTICE, "practice_documents",
                List.of(ChannelContentScope.PRACTICE), ChannelResultMode.RELEVANT, true, true, true);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(hybrid.findRelevantPosts(any(), any(), anyInt())).thenReturn(ChannelPostSearchResult.empty());
        var old = new TelegramChannelPost();
        old.setId(3L); old.setText("Сдать документы до 25 августа 2026 года");
        old.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        var extension = new TelegramChannelPost();
        extension.setId(7L); extension.setText("Продлеваем срок сдачи документов до 7 сентября 2026 года");
        extension.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        var relation = new TelegramChannelPostRelation();
        relation.setSourcePost(extension); relation.setTargetPost(old);
        relation.setRelationType(TelegramChannelPostRelationType.UPDATE);
        when(timeline.searchDeadlineKnowledge(eq(analysis), eq(8), anyString())).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.PRACTICE, List.of(old, extension))), List.of(relation)));
        when(llm.generateAnswer(anyString())).thenReturn(LlmResponse.success("Срок был 7 сентября и уже прошёл.", "test", "test", 0));
        assertTrue(service.buildAnswerIfRelevant("Документы на практику до какого числа сдать?").orElseThrow().contains("7 сентября"));
        verify(llm).generateAnswer(argThat(prompt -> prompt.contains("25 августа") && prompt.contains("7 сентября")
                && prompt.contains("СРОК ИСТЁК") && prompt.contains("UPDATE")));
    }

    @Test
    void incompleteDeadlineHistoryNeverProducesConfidentAnswer() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.DEADLINE, null,
                List.of(ChannelContentScope.DEADLINES), ChannelResultMode.RELEVANT, true, false, true);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(hybrid.findRelevantPosts(any(), any(), anyInt())).thenReturn(ChannelPostSearchResult.empty());
        when(timeline.searchDeadlineKnowledge(eq(analysis), eq(8), anyString())).thenReturn(new ChannelPostSearchResult(List.of(), List.of(), false));
        assertTrue(service.buildAnswerIfRelevant("Я ещё успеваю документы подать?").orElseThrow().contains("не могу подтвердить"));
        verifyNoInteractions(llm);
    }

    @Test
    void greetingHasNoSecondLlmOrEmbeddingCall() {
        when(analyzer.analyze("Привет!")).thenReturn(new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_CHAT, null,
                List.of(ChannelContentScope.NONE), ChannelResultMode.RELEVANT, false, false, false, "Привет!"));
        assertEquals("Привет!", service.buildAnswerIfRelevant("Привет!").orElseThrow());
        verifyNoInteractions(llm, embeddings, hybrid, faq);
    }

    @Test
    void exactFaqReturnsOfficialAnswerWithoutGenerationOrEmbedding() {
        FaqEntry entry = new FaqEntry();
        entry.setQuestion("Как оформить практику?");
        entry.setFullAnswer("Заполните <заявление>.");
        when(faq.findActiveEntries()).thenReturn(List.of(entry));
        when(analyzer.analyze(anyString())).thenReturn(new ChannelQueryAnalysis(ChannelSearchIntent.FAQ, null,
                List.of(ChannelContentScope.NONE), ChannelResultMode.RELEVANT, false, true, false));
        assertEquals("Заполните &lt;заявление&gt;.", service.buildAnswerIfRelevant(entry.getQuestion()).orElseThrow());
        verifyNoInteractions(llm, embeddings, hybrid);
    }

    @Test
    void safeListSkipsEmbeddingAndGeneration() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, "vacancies", List.of(ChannelContentScope.VACANCIES),
                ChannelResultMode.ALL_MATCHING, true, false, false);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        var post = new TelegramChannelPost();
        post.setId(1L);
        post.setText("Java Intern");
        when(hybrid.findRelevantPosts(any(), eq(Optional.empty()), eq(30))).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.VACANCIES, List.of(post)))));
        when(builder.build(anyString(), any(), eq(30))).thenReturn(Optional.of("Список публикаций"));
        assertEquals("Список публикаций", service.buildAnswerIfRelevant("Покажи все вакансии").orElseThrow());
        verifyNoInteractions(llm, embeddings);
    }

    @Test
    void mixedQuestionKeepsFaqAndChannelContextAndGeneratesOneAnswer() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.PRACTICE, "practice", List.of(ChannelContentScope.PRACTICE),
                ChannelResultMode.RELEVANT, true, true, true);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(embeddings.createQueryEmbedding(anyString())).thenReturn(Optional.empty());
        FaqEntry entry = new FaqEntry();
        entry.setQuestion("Документы для практики");
        entry.setFullAnswer("Заявление и договор");
        when(faq.findActiveEntries()).thenReturn(List.of(entry));
        var post = new TelegramChannelPost();
        post.setId(1L);
        post.setText("Документы принимаются до пятницы");
        when(hybrid.findRelevantPosts(eq(analysis), any(), eq(8))).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.PRACTICE, List.of(post)))));
        when(llm.generateAnswer(anyString())).thenReturn(LlmResponse.success("Подтверждённый ответ", "test", "test", 0));
        assertEquals("Подтверждённый ответ", service.buildAnswerIfRelevant("Какие документы и сроки практики?").orElseThrow());
        verify(llm).generateAnswer(argThat(prompt -> prompt.contains("Заявление и договор") && prompt.contains("до пятницы")));
        verify(embeddings, times(1)).createQueryEmbedding(anyString());
        verifyNoInteractions(builder);
    }

    @Test
    void unknownRouterResultStillUsesGroundedSources() {
        when(analyzer.analyze(anyString())).thenReturn(ChannelQueryAnalysis.unknown());
        when(embeddings.createQueryEmbedding(anyString())).thenReturn(Optional.empty());
        when(hybrid.findRelevantPosts(any(), any(), anyInt())).thenReturn(ChannelPostSearchResult.empty());
        when(posts.findLatestSearchableTextPosts(any())).thenReturn(List.of());
        when(faq.findActiveEntries()).thenReturn(List.of());
        assertTrue(service.buildAnswerIfRelevant("What is the deadline?").orElseThrow().contains("no confirmed information"));
        verifyNoInteractions(llm);
    }

    @Test
    void emptyHistoryNeverFallsBackToCurrentPostsOrEmbedding() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, null, List.of(ChannelContentScope.VACANCIES),
                ChannelResultMode.ALL_MATCHING, true, false, false, null,
                ChannelTimeScope.YESTERDAY, ChannelFreshnessScope.EXPIRED, null, null);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(timeline.search(eq(analysis), eq(30), anyString())).thenReturn(ChannelPostSearchResult.empty());
        assertTrue(service.buildAnswerIfRelevant("Expired vacancies published yesterday").isPresent());
        verifyNoInteractions(hybrid, posts, embeddings, llm, builder);
    }

    @Test
    void historyPromptAndAnswerExplicitlyLabelExpiredSources() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, null, List.of(ChannelContentScope.VACANCIES),
                ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.EXPIRED, null, null);
        var expired = new TelegramChannelPost();
        expired.setId(1L);
        expired.setText("Old vacancy");
        expired.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(timeline.search(eq(analysis), eq(8), anyString())).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.VACANCIES, List.of(expired)))));
        when(llm.generateAnswer(anyString())).thenReturn(LlmResponse.success("The vacancy has expired.", "test", "test", 0));
        assertTrue(service.buildAnswerIfRelevant("Show expired vacancies").orElseThrow().contains("offers have expired"));
        verify(llm).generateAnswer(argThat(prompt -> prompt.contains("СРОК ИСТЁК") && prompt.contains("freshnessScope: EXPIRED")));
        verifyNoInteractions(embeddings, hybrid, builder);
    }

    @Test
    void truncatedHistoricalChainReturnsCautionWithoutConfidentGeneration() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null, List.of(ChannelContentScope.EVENTS),
                ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.ALL, null, null);
        var original = new TelegramChannelPost();
        original.setId(1L);
        original.setText("Free gifts at the event");
        original.setChannelUsername("career_channel");
        original.setTelegramMessageId(1L);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(timeline.search(eq(analysis), eq(8), anyString())).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.EVENTS, List.of(original))), List.of(), false));
        String answer = service.buildAnswerIfRelevant("Were there gifts at this event?").orElseThrow();
        assertTrue(answer.contains("cannot confirm"));
        assertTrue(answer.contains("https://t.me/career_channel/1"));
        assertFalse(answer.contains("Free gifts"));
        verifyNoInteractions(llm, embeddings, builder);
    }

    @Test
    void truncatedSimpleListNeverUsesDirectBuilderOrGenerativeFallback() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, null, List.of(ChannelContentScope.VACANCIES),
                ChannelResultMode.ALL_MATCHING, true, false, false);
        var post = new TelegramChannelPost();
        post.setId(1L);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(hybrid.findRelevantPosts(any(), any(), eq(30))).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.VACANCIES, List.of(post))), List.of(), false));
        assertTrue(service.buildAnswerIfRelevant("Покажи все вакансии").orElseThrow().contains("не могу подтвердить"));
        verifyNoInteractions(llm, embeddings, builder);
    }

    @ParameterizedTest
    @CsvSource({
            "Show expired vacancies, cannot confirm, expired",
            "Покажи истёкшие вакансии, не могу подтвердить, срок истёк",
            "Мерзімі аяқталған вакансияларды көрсет, растай алмаймын, мерзімі аяқталған"
    })
    void providerFailureUsesLocalizedCautionAndExpiryLabelWithoutOldOfferSnippets(
            String question, String caution, String expiry) {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, null, List.of(ChannelContentScope.VACANCIES),
                ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.EXPIRED, null, null);
        var expired = new TelegramChannelPost();
        expired.setId(1L);
        expired.setTelegramMessageId(1L);
        expired.setChannelUsername("career_channel");
        expired.setText("Apply today for this position!");
        expired.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);
        when(analyzer.analyze(question)).thenReturn(analysis);
        when(timeline.search(eq(analysis), eq(8), anyString())).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.VACANCIES, List.of(expired)))));
        when(llm.generateAnswer(anyString())).thenReturn(LlmResponse.failure("outage", "test", "test", LlmErrorType.SERVICE_UNAVAILABLE, 0));
        String answer = service.buildAnswerIfRelevant(question).orElseThrow();
        assertTrue(answer.contains(caution), answer);
        assertTrue(answer.contains(expiry), answer);
        assertTrue(answer.contains("https://t.me/career_channel/1"));
        assertFalse(answer.contains("Apply today"));
    }

    @Test
    void generationExceptionDoesNotFallBackToUnsynthesizedConditionsFromCompleteChain() {
        var analysis = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null, List.of(ChannelContentScope.EVENTS),
                ChannelResultMode.RELEVANT, true, false, false);
        var original = new TelegramChannelPost();
        original.setId(1L);
        original.setText("Free gifts at the event");
        original.setTelegramMessageId(1L);
        original.setChannelUsername("career_channel");
        var cancellation = new TelegramChannelPost();
        cancellation.setId(2L);
        cancellation.setText("Gift distribution has been cancelled");
        cancellation.setTelegramMessageId(2L);
        cancellation.setChannelUsername("career_channel");
        var relation = new TelegramChannelPostRelation();
        relation.setSourcePost(cancellation);
        relation.setTargetPost(original);
        relation.setRelationType(TelegramChannelPostRelationType.CANCELLATION);
        when(analyzer.analyze(anyString())).thenReturn(analysis);
        when(embeddings.createQueryEmbedding(anyString())).thenReturn(Optional.empty());
        when(hybrid.findRelevantPosts(eq(analysis), any(), eq(8))).thenReturn(new ChannelPostSearchResult(
                List.of(new ChannelPostSearchGroup(ChannelContentScope.EVENTS, List.of(original, cancellation))), List.of(relation)));
        when(llm.generateAnswer(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        String answer = service.buildAnswerIfRelevant("Will there be gifts?").orElseThrow();
        assertTrue(answer.contains("cannot confirm"));
        assertTrue(answer.contains("https://t.me/career_channel/1"));
        assertTrue(answer.contains("https://t.me/career_channel/2"));
        assertFalse(answer.contains("Free gifts"));
        assertFalse(answer.contains("Gift distribution has been cancelled"));
    }
}
