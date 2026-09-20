package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineEvidenceTermsTest {
    @Test void javaIsNotJavascriptAndUnknownTechnologyDoesNotInventAFilter() {
        var java = DeadlineEvidenceTerms.fromQuestion("Дедлайн Java-вакансии уже прошёл?");
        assertTrue(java.matches(post("Java Developer: applications until 7 September")));
        assertFalse(java.matches(post("Javascript Developer: applications until 7 September")));
        assertFalse(java.matches(post("Java Script Developer: applications until 7 September")));
        assertTrue(DeadlineEvidenceTerms.fromQuestion("Когда дедлайн практики?").isEmpty());
        assertTrue(DeadlineEvidenceTerms.fromQuestion("Когда дедлайн неизвестной технологии?").isEmpty());
    }

    @Test void languageAliasesAndQuotedCompanyAreGroundedInUserQuestion() {
        var query = DeadlineEvidenceTerms.fromQuestion("Когда дедлайн вакансии на питоне у компании «Acme»?");
        assertTrue(query.matches(post("Acme hires a Python developer; deadline September 7")));
        assertFalse(query.matches(post("Other company hires a Python developer")));
        assertFalse(query.matches(post("Acme hires a Java developer")));
        assertTrue(DeadlineEvidenceTerms.fromQuestion("Company name is not quoted; practice deadlines").isEmpty());
    }

    private TelegramChannelPost post(String text) {
        var post = new TelegramChannelPost(); post.setText(text); return post;
    }
}
