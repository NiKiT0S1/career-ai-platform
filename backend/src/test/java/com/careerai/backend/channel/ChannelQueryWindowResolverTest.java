package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChannelQueryWindowResolverTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T20:00:00Z"), ZoneId.of("Asia/Almaty"));
    private final ChannelQueryWindowResolver resolver = new ChannelQueryWindowResolver(clock);

    @Test
    void todayUsesBusinessDayRatherThanUtcAndHalfOpenBoundaries() {
        var window = resolver.resolve(query(ChannelTimeScope.TODAY, null, null)).orElseThrow();
        assertEquals(OffsetDateTime.parse("2026-09-19T00:00:00+05:00"), window.fromInclusive());
        assertTrue(window.contains(OffsetDateTime.parse("2026-09-18T19:00:00Z")));
        assertFalse(window.contains(OffsetDateTime.parse("2026-09-19T19:00:00Z")));
        assertFalse(window.contains(OffsetDateTime.parse("2026-09-18T18:59:59Z")));
    }

    @Test
    void yesterdayAndLastSevenDaysHaveExactDayCounts() {
        var yesterday = resolver.resolve(query(ChannelTimeScope.YESTERDAY, null, null)).orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 18), yesterday.fromInclusive().toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 19), yesterday.toExclusive().toLocalDate());
        var week = resolver.resolve(query(ChannelTimeScope.LAST_7_DAYS, null, null)).orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 13), week.fromInclusive().toLocalDate());
        assertEquals(LocalDate.of(2026, 9, 20), week.toExclusive().toLocalDate());
    }

    @Test
    void customDateRangeIncludesWholeLastDayButRejectsInvalidRanges() {
        var window = resolver.resolve(query(ChannelTimeScope.CUSTOM_RANGE,
                LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12))).orElseThrow();
        assertTrue(window.contains(OffsetDateTime.parse("2026-09-12T23:59:59+05:00")));
        assertFalse(window.contains(OffsetDateTime.parse("2026-09-13T00:00:00+05:00")));
        assertTrue(resolver.resolve(query(ChannelTimeScope.CUSTOM_RANGE, null, null)).isEmpty());
        assertTrue(resolver.resolve(query(ChannelTimeScope.CUSTOM_RANGE,
                LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 10))).isEmpty());
    }

    @Test
    void customRangeFollowsDstRatherThanAssumingTwentyFourHours() {
        var dstResolver = new ChannelQueryWindowResolver(Clock.fixed(clock.instant(), ZoneId.of("Europe/Berlin")));
        var window = dstResolver.resolve(query(ChannelTimeScope.CUSTOM_RANGE,
                LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29))).orElseThrow();
        assertEquals(23, Duration.between(window.fromInclusive(), window.toExclusive()).toHours());
    }

    static ChannelQueryAnalysis query(ChannelTimeScope scope, LocalDate from, LocalDate to) {
        return new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null, List.of(ChannelContentScope.ALL_UPDATES),
                ChannelResultMode.ALL_MATCHING, true, false, false, null, scope, ChannelFreshnessScope.ALL, from, to);
    }
}
