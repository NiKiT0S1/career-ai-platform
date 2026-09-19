package com.careerai.backend.channel;

import com.careerai.backend.ai.*;
import com.careerai.backend.answer.AnswerCacheProperties;
import org.junit.jupiter.api.Test;
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
}
