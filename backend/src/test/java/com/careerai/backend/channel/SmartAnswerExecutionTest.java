package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import com.careerai.backend.answer.*;
import com.careerai.backend.faq.*;
import com.careerai.backend.semantic.*;
import org.junit.jupiter.api.Test;
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
    private final TelegramChannelPostAnswerService service = new TelegramChannelPostAnswerService(posts, analyzer, llm,
            hybrid, faq, faqSearch, embeddings, new AnswerExecutionPlanner(), builder,
            new TelegramChannelPostSearchEligibility(Clock.systemUTC()), timeline, Clock.systemUTC());

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
        when(timeline.search(analysis, 30)).thenReturn(ChannelPostSearchResult.empty());
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
        when(timeline.search(analysis, 8)).thenReturn(new ChannelPostSearchResult(
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
        when(timeline.search(analysis, 8)).thenReturn(new ChannelPostSearchResult(
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
        when(timeline.search(analysis, 8)).thenReturn(new ChannelPostSearchResult(
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
