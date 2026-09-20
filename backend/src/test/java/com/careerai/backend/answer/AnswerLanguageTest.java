package com.careerai.backend.answer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnswerLanguageTest {
    @Test void russianOutputRemainsByteForByteUnchanged() {
        String text="<b>Срок истёк</b> (срок не подтверждён; год не указан) — AITU CareerAI";
        assertEquals(text,AnswerLanguage.RU.localizeStatusLabels(text));
    }

    @Test void kazakhOutputOnlyReplacesKnownStatusPhrases() {
        String result=AnswerLanguage.KZ.localizeStatusLabels("AITU: 7 қыркүйек (срок истёк). Срок не подтвержден; год не указан.");
        assertEquals("AITU: 7 қыркүйек (мерзімі өткен). Мерзімі расталмаған; жылы көрсетілмеген.",result);
    }

    @Test void englishOutputHandlesYoAndPlainEWithoutTranslatingNames() {
        assertEquals("Java Intern: deadline has passed; Deadline not confirmed; year not specified.",
                AnswerLanguage.EN.localizeStatusLabels("Java Intern: срок истек; Срок не подтверждён; год не указан."));
    }

    @Test void markupAttributesLinksAndCodeArePreserved() {
        String input="<b title=\"срок истёк\">Срок истёк</b> <a href=\"https://t.me/career_channel/17\">Astana IT University</a>"
                +" https://example.org/год%20не%20указан <code>год не указан</code>";
        String expected="<b title=\"срок истёк\">Deadline has passed</b> <a href=\"https://t.me/career_channel/17\">Astana IT University</a>"
                +" https://example.org/год%20не%20указан <code>год не указан</code>";
        assertEquals(expected,AnswerLanguage.EN.localizeStatusLabels(input));
    }

    @Test void unrelatedTextAndPartialWordsAreNotTranslated() {
        String original="CareerAI AITU срок истёкший; год не указанного события";
        assertEquals(original,AnswerLanguage.KZ.localizeStatusLabels(original));
        assertNull(AnswerLanguage.EN.localizeStatusLabels(null));
    }
}
