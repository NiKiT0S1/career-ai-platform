package com.careerai.backend.semantic;

import com.careerai.backend.faq.FaqEntry;

/**
 * FAQ-запись и степень её смысловой близости к вопросу пользователя.
 */

public record FaqSemanticMatch(
        FaqEntry entry,
        double similarity
) {
}
