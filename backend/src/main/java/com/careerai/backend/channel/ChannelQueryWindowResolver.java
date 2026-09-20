package com.careerai.backend.channel;

import org.springframework.stereotype.Component;
import java.time.*;
import java.util.Optional;

@Component
public class ChannelQueryWindowResolver {
    private final Clock clock;

    public ChannelQueryWindowResolver(Clock clock) { this.clock = clock; }

    /** User date endpoints are inclusive; database timestamps use [start, end). */
    public Optional<Window> resolve(ChannelQueryAnalysis analysis) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from;
        LocalDate to;
        switch (analysis.timeScope()) {
            case TODAY -> { from = today; to = today; }
            case YESTERDAY -> { from = today.minusDays(1); to = from; }
            case LAST_7_DAYS -> { from = today.minusDays(6); to = today; }
            case CUSTOM_RANGE -> { from = analysis.dateFrom(); to = analysis.dateTo(); }
            case ANY_TIME -> { from = LocalDate.of(1970, 1, 1); to = LocalDate.of(9998, 12, 31); }
            default -> throw new IllegalStateException("Unexpected time scope");
        }
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1970 || to.getYear() > 9998) {
            return Optional.empty();
        }
        return Optional.of(new Window(from.atStartOfDay(clock.getZone()).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime()));
    }

    public record Window(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        public boolean contains(OffsetDateTime timestamp) {
            return timestamp != null && !timestamp.isBefore(fromInclusive) && timestamp.isBefore(toExclusive);
        }
    }
}
