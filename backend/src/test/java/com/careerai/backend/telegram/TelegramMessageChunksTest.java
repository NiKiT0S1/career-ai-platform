package com.careerai.backend.telegram;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelegramMessageChunksTest {
    @Test void keepsAllConditionsAndUnicodeAcrossTelegramLimits() {
        String original = "a".repeat(3999) + "🎓" + "\nУсловие: регистрация обязательна.\n".repeat(300);
        var chunks = TelegramMessageChunks.split(original);
        assertTrue(chunks.size() > 1);
        assertEquals(original, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 4000 && !Character.isHighSurrogate(c.charAt(c.length()-1))));
    }
    @Test void fallbackPreservesSourceLinksAndDecodedEntities() {
        assertEquals("Условие & срок\nИсточник (https://t.me/career/42?a=1&b=2)",
                TelegramMessageChunks.plain("<b>Условие &amp; срок</b><br><a href=\"https://t.me/career/42?a=1&amp;b=2\">Источник</a>"));
    }
}
