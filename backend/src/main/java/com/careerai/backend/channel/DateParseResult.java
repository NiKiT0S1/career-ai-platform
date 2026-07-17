package com.careerai.backend.channel;

import java.time.LocalDate;

/**
 * Результат разбора текстовой даты.
 */

public record DateParseResult(
        DateParseStatus status,
        LocalDate date,
        String reason

) {

    public static DateParseResult parsed(LocalDate date) {
        return new DateParseResult(
                DateParseStatus.PARSED,
                date,
                null
        );
    }

    public static DateParseResult unknown(String reason) {
        return new DateParseResult(
                DateParseStatus.UNKNOWN,
                null,
                reason
        );
    }

    public static DateParseResult invalid(String reason) {
        return new DateParseResult(
                DateParseStatus.INVALID,
                null,
                reason
        );
    }
}
