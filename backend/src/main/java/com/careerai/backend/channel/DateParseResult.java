package com.careerai.backend.channel;

import java.time.LocalDate;

/**
 * Результат разбора текстовой даты.
 */

public record DateParseResult(
        DateParseStatus status,
        LocalDate date,
        DateBoundaryType boundaryType,
        String reason
) {

    public DateParseResult {
        if (status == null) {
            throw new IllegalArgumentException("Статус разбора даты не может быть null");
        }

        if (status == DateParseStatus.PARSED) {
            if (date == null) {
                throw new IllegalArgumentException("Для успешно разобранной даты требуется LocalDate");
            }

            if (boundaryType == null) {
                boundaryType = DateBoundaryType.UNSPECIFIED;
            }
        }
    }

    public static DateParseResult parsed(LocalDate date, DateBoundaryType boundaryType) {
        return new DateParseResult(
                DateParseStatus.PARSED,
                date,
                boundaryType,
                null
        );
    }

    public static DateParseResult unknown(String reason) {
        return new DateParseResult(
                DateParseStatus.UNKNOWN,
                null,
                null,
                reason
        );
    }

    public static DateParseResult invalid(String reason) {
        return new DateParseResult(
                DateParseStatus.INVALID,
                null,
                null,
                reason
        );
    }
}