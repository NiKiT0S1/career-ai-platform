package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import com.careerai.backend.answer.AnswerCacheProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChannelQueryAnalyzerTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-19T10:00:00Z"), ZoneId.of("Asia/Almaty"));
    private final LlmProvider provider = mock(LlmProvider.class);
    private final ChannelQueryAnalyzer analyzer = new ChannelQueryAnalyzer(provider, new ObjectMapper(),
            new ChannelQueryAnalysisRequestFactory(clock), new AnswerCacheProperties(10, 60, 10, 60), clock);

    @Test
    void parsesDirectAnswerAndReusesSuccessfulAnalysis() {
        when(provider.execute(any())).thenReturn(LlmResponse.success("""
                {"intent":"GENERAL_CHAT", "contentScopes":["NONE"], "directAnswer":"Привет!"}
                """, "test", "test", 0));
        assertEquals("Привет!", analyzer.analyze("Привет").directAnswer());
        assertEquals("Привет!", analyzer.analyze("Привет").directAnswer());
        verify(provider, times(1)).execute(any());
        analyzer.invalidateAll();
        analyzer.analyze("Привет");
        verify(provider, times(2)).execute(any());
    }

    @Test
    void malformedOutputAndProviderExceptionsAreRetriedNotCached() {
        when(provider.execute(any())).thenReturn(LlmResponse.success("broken JSON", "test", "test", 0))
                .thenThrow(new IllegalStateException("outage"))
                .thenReturn(LlmResponse.success("{\"intent\":\"FAQ\",\"needsFaq\":true}", "test", "test", 0));
        assertEquals(ChannelSearchIntent.UNKNOWN, analyzer.analyze("practice").intent());
        assertEquals(ChannelSearchIntent.UNKNOWN, analyzer.analyze("practice").intent());
        assertEquals(ChannelSearchIntent.FAQ, analyzer.analyze("practice").intent());
        verify(provider, times(3)).execute(any());
    }

    @Test
    void timelineFieldsAreParsedAndForceGroundedChannelSearch() {
        when(provider.execute(any())).thenReturn(LlmResponse.success("""
                {"intent":"GENERAL_UPDATES", "contentScopes":["NONE"], "needsChannelPosts":false,
                 "timeScope":"CUSTOM_RANGE", "freshnessScope":"ALL", "dateFrom":"2026-09-01", "dateTo":"2026-09-18"}
                """, "test", "test", 0));
        var analysis = analyzer.analyze("Что публиковали с 1 по 18 сентября?");
        assertTrue(analysis.needsChannelPosts());
        assertTrue(analysis.requiresTimelineSearch());
        assertEquals(ChannelTimeScope.CUSTOM_RANGE, analysis.timeScope());
        assertEquals(ChannelFreshnessScope.ALL, analysis.freshnessScope());
        assertEquals(LocalDate.of(2026, 9, 1), analysis.dateFrom());
        assertTrue(analysis.hasScope(ChannelContentScope.ALL_UPDATES));
    }

    @Test
    void optionalEventFieldsAreParsedSeparatelyAndForceEventSearch() {
        when(provider.execute(any())).thenReturn(LlmResponse.success("""
                {"intent":"GENERAL_UPDATES", "contentScopes":["NONE"], "needsChannelPosts":false,
                 "timeScope":"ANY_TIME", "eventDateFrom":"2026-09-20", "eventDateTo":"2026-09-20"}
                """, "test", "test", 0));
        var analysis = analyzer.analyze("What events are happening tomorrow?");
        assertTrue(analysis.needsChannelPosts());
        assertTrue(analysis.hasScope(ChannelContentScope.EVENTS));
        assertEquals(ChannelTimeScope.ANY_TIME, analysis.timeScope());
        assertNull(analysis.dateFrom()); assertNull(analysis.dateTo());
        assertEquals(LocalDate.of(2026, 9, 20), analysis.eventDateFrom());
        assertTrue(analysis.hasValidEventDateRange());
    }

    @Test
    void malformedEventDatesDoNotSilentlyRemoveTheRequestedRestriction() {
        when(provider.execute(any())).thenReturn(LlmResponse.success("""
                {"intent":"GENERAL_UPDATES", "contentScopes":["EVENTS"], "needsChannelPosts":true,
                 "timeScope":"ANY_TIME", "eventDateFrom":"2026-02-31", "eventDateTo":"nonsense"}
                """, "test", "test", 0));
        var analysis = analyzer.analyze("Мероприятия за указанный период");
        assertEquals(ChannelTimeScope.CUSTOM_RANGE, analysis.timeScope());
        assertTrue(new ChannelQueryWindowResolver(clock).resolve(analysis).isEmpty());
    }

    @Test
    void recoversExactLiveProviderEventAliasAndRemovesInventedTodayOnlyWindow() {
        // Exact synthetic live-smoke response, kept as a deterministic regression without an API call.
        when(provider.execute(any())).thenReturn(LlmResponse.success("""
                {"intent":"EVENTS","topic":"events_now_or_upcoming","contentScopes":["EVENTS"],"resultMode":"RELEVANT","needsChannelPosts":true,"needsFaq":false,"needsDeadlines":false,"directAnswer":null,"timeScope":"ANY_TIME","freshnessScope":"CURRENT","dateFrom":null,"dateTo":null,"eventDateFrom":"2026-09-19","eventDateTo":"2026-09-19"}
                """, "test", "test", 0));
        var analysis = analyzer.analyze("What evnts are happening now or coming up?");
        assertEquals(ChannelSearchIntent.GENERAL_UPDATES, analysis.intent());
        assertEquals(java.util.List.of(ChannelContentScope.EVENTS), analysis.contentScopes());
        assertTrue(analysis.needsChannelPosts());
        assertEquals(ChannelTimeScope.ANY_TIME, analysis.timeScope());
        assertEquals(ChannelFreshnessScope.CURRENT, analysis.freshnessScope());
        assertNull(analysis.eventDateFrom()); assertNull(analysis.eventDateTo());
        assertNull(analysis.dateFrom()); assertNull(analysis.dateTo());
        assertNull(analysis.directAnswer());
    }

    @ParameterizedTest
    @ValueSource(strings={"EVENT", "EVENTS"})
    void onlyKnownEventAliasesRecoverTheCorrectScopeWhenMissing(String alias) {
        when(provider.execute(any())).thenReturn(LlmResponse.success("{\"intent\":\"" + alias + "\"}", "test", "test", 0));
        var analysis = analyzer.analyze("What is coming up?");
        assertEquals(ChannelSearchIntent.GENERAL_UPDATES, analysis.intent());
        assertEquals(java.util.List.of(ChannelContentScope.EVENTS), analysis.contentScopes());
        assertTrue(analysis.needsChannelPosts());
    }

    @Test
    void unrelatedUnsupportedIntentIsStillRejectedEvenWithPlausibleScope() {
        when(provider.execute(any())).thenReturn(LlmResponse.success("""
                {"intent":"UNSUPPORTED_ARBITRARY_VALUE", "contentScopes":["EVENTS"], "needsChannelPosts":true}
                """, "test", "test", 0));
        assertEquals(ChannelSearchIntent.UNKNOWN, analyzer.analyze("What is coming up?").intent());
    }

    @ParameterizedTest
    @ValueSource(strings={"Open Space іс-шарасы қашан өтті? Күні қандай болды?",
            "Когда проходило мероприятие в Open Space?", "Когда состоялся мастер-класс?",
            "What events took place in Open Space?", "When did the workshop take place?"})
    void routerOutageStillRecoversHistoricalEventIntentAcrossLanguages(String question) {
        when(provider.execute(any())).thenThrow(new IllegalStateException("Synthetic router outage"));
        var analysis = analyzer.analyze(question);
        assertEquals(ChannelSearchIntent.GENERAL_UPDATES, analysis.intent());
        assertEquals(java.util.List.of(ChannelContentScope.EVENTS), analysis.contentScopes());
        assertTrue(analysis.needsChannelPosts());
        assertEquals(ChannelFreshnessScope.ALL, analysis.freshnessScope());
        assertEquals(ChannelTimeScope.ANY_TIME, analysis.timeScope());
        assertFalse(analysis.hasEventDateRange());
    }
}
