package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class ChannelQueryPolicyTest {
    @ParameterizedTest
    @ValueSource(strings={"Какие ивенты ща проходят? Или какие будут?", "Какие мероприятия будут проходить?",
            "What events are happening today?", "Бүгін қандай іс-шаралар өтеді?"})
    void happeningDateNeverBecomesPublicationDate(String question) {
        var wrong = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.TODAY, ChannelFreshnessScope.CURRENT, null, null);
        var fixed = ChannelQueryPolicy.normalize(question, wrong);
        assertEquals(ChannelTimeScope.ANY_TIME, fixed.timeScope());
        assertTrue(fixed.hasScope(ChannelContentScope.EVENTS));
    }
    @ParameterizedTest
    @ValueSource(strings={"Посты о мероприятиях за сегодня", "Events published today", "Бүгін жарияланған іс-шаралар"})
    void explicitPublicationWindowRemainsRestricted(String question) {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.TODAY, ChannelFreshnessScope.ALL, null, null);
        assertEquals(ChannelTimeScope.TODAY, ChannelQueryPolicy.normalize(question, original).timeScope());
    }
    @ParameterizedTest
    @ValueSource(strings={"А вообще какая дата-то дедлайн по сдачи документов была?", "What was the document deadline?",
            "Құжаттарды тапсыру мерзімі қашан болған?"})
    void historicalDeadlineIsNotLimitedToCurrentOffers(String question) {
        var fixed = ChannelQueryPolicy.normalize(question, ChannelQueryAnalysis.unknown());
        assertTrue(fixed.needsDeadlines()); assertTrue(fixed.needsChannelPosts());
        assertEquals(ChannelFreshnessScope.ALL, fixed.freshnessScope());
    }
    @ParameterizedTest
    @ValueSource(strings={"Я еще успеваю документы подать?", "Am I too late to submit documents?", "Құжаттарды тапсыруға үлгеремін бе?"})
    void canIStillSubmitRequiresDeadlineEvidence(String question) {
        var fixed = ChannelQueryPolicy.normalize(question, ChannelQueryAnalysis.unknown());
        assertTrue(fixed.needsDeadlines()); assertTrue(fixed.needsFaq());
    }
    @Test void mixedQuestionPreservesAllPartsEvenAfterNarrowRouterResult() {
        var narrow = new ChannelQueryAnalysis(ChannelSearchIntent.VACANCY, "jobs",
                List.of(ChannelContentScope.VACANCIES), ChannelResultMode.RELEVANT, true, false, false);
        var fixed = ChannelQueryPolicy.normalize("Какие щас вакансии есть? Где можно пройти практику? Я еще успеваю документы подать?", narrow);
        assertEquals(List.of(ChannelContentScope.VACANCIES, ChannelContentScope.PRACTICE), fixed.contentScopes());
        assertTrue(fixed.needsFaq()); assertTrue(fixed.needsDeadlines());
    }
    @Test void smallTalkIsUnchanged() {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_CHAT, null, List.of(ChannelContentScope.NONE),
                ChannelResultMode.RELEVANT, false, false, false, "Сәлем!");
        assertEquals(original, ChannelQueryPolicy.normalize("Сәлем!", original));
    }

    @ParameterizedTest
    @ValueSource(strings={"Покажи истёкшие дедлайны вакансий", "Show expired job deadlines", "Аяқталған жұмыс мерзімдері"})
    void explicitExpiredRequestNeverBecomesAll(String question) {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.DEADLINE, null,
                List.of(ChannelContentScope.VACANCIES), ChannelResultMode.RELEVANT, true, false, true, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.EXPIRED, null, null);
        assertEquals(ChannelFreshnessScope.EXPIRED, ChannelQueryPolicy.normalize(question, original).freshnessScope());
    }

    @Test void occurrenceRangeIsPreservedIndependentlyOfPublicationDate() {
        LocalDate from = LocalDate.of(2026, 7, 10), to = LocalDate.of(2026, 8, 10);
        var original = eventRange(from, to);
        var fixed = ChannelQueryPolicy.normalize("Какие мероприятия были с 10 июля по 10 августа?", original);
        assertEquals(ChannelTimeScope.ANY_TIME, fixed.timeScope());
        assertNull(fixed.dateFrom()); assertNull(fixed.dateTo());
        assertEquals(from, fixed.eventDateFrom()); assertEquals(to, fixed.eventDateTo());
        assertTrue(fixed.hasValidEventDateRange());
        assertEquals(ChannelFreshnessScope.ALL, fixed.freshnessScope());
    }

    @Test void invalidOccurrenceRangeRemainsAvailableForValidation() {
        var original = eventRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 7, 10));
        var fixed = ChannelQueryPolicy.normalize("Мероприятия с 10 августа по 10 июля", original);
        assertTrue(fixed.hasEventDateRange());
        assertFalse(fixed.hasValidEventDateRange());
        assertEquals(original.dateFrom(), fixed.eventDateFrom());
        assertEquals(original.dateTo(), fixed.eventDateTo());
        var missing = ChannelQueryPolicy.normalize("Мероприятия за указанный период", eventRange(null, null));
        assertEquals(ChannelTimeScope.CUSTOM_RANGE, missing.timeScope());
    }

    @Test void publicationRangeDoesNotTurnIntoOccurrenceRange() {
        var original = eventRange(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 8, 10));
        var fixed = ChannelQueryPolicy.normalize("Посты о мероприятиях с 10 июля по 10 августа", original);
        assertEquals(original.timeScope(), fixed.timeScope());
        assertEquals(original.dateFrom(), fixed.dateFrom());
        assertFalse(fixed.hasEventDateRange());
    }

    @Test void explicitTodayUsesApplicationDateButColloquialNowAndFutureDoesNotLimitToToday() {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.TODAY, ChannelFreshnessScope.CURRENT, null, null);
        LocalDate today = LocalDate.of(2026, 9, 20);
        var explicit = ChannelQueryPolicy.normalize("What events are happening today?", original, today);
        assertEquals(today, explicit.eventDateFrom()); assertEquals(today, explicit.eventDateTo());
        var broad = ChannelQueryPolicy.normalize("Какие ивенты ща проходят? Или какие будут?", original, today);
        assertEquals(ChannelTimeScope.ANY_TIME, broad.timeScope());
        assertFalse(broad.hasEventDateRange());
    }

    @Test void explicitBroadNewsRequestKeepsAllCategories() {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.ALL_UPDATES), ChannelResultMode.ALL_MATCHING, true, false, false);
        var fixed = ChannelQueryPolicy.normalize("Все новости: вакансии, мероприятия и прочее", original);
        assertEquals(List.of(ChannelContentScope.ALL_UPDATES), fixed.contentScopes());
    }

    @Test void stablePracticeFaqDoesNotAcquireUnrelatedChannelSearch() {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.FAQ, "practice_documents",
                List.of(ChannelContentScope.NONE), ChannelResultMode.RELEVANT, false, true, false);
        assertEquals(original, ChannelQueryPolicy.normalize("Какие документы необходимы для оформления практики?", original));
    }

    @ParameterizedTest
    @ValueSource(strings={"Какой срок годности молока?", "Срок аренды квартиры?", "What is the deadline in programming?"})
    void weakDeadlineWordsDoNotOverrideConfidentOutOfScopeRoute(String question) {
        var original = new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_CHAT, null,
                List.of(ChannelContentScope.NONE), ChannelResultMode.RELEVANT, false, false, false);
        assertEquals(original, ChannelQueryPolicy.normalize(question, original));
    }

    private ChannelQueryAnalysis eventRange(LocalDate from, LocalDate to) {
        return new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.CUSTOM_RANGE, ChannelFreshnessScope.CURRENT, from, to);
    }

    @ParameterizedTest
    @ValueSource(strings={"What evnts are happening now or coming up?", "Какие ивенты ща проходят? Или какие будут?",
            "Қазір қандай іс-шаралар өтеді немесе алдағы іс-шаралар қандай?"})
    void openEndedCurrentOrUpcomingQuestionsRejectInventedTodayOnlyOccurrenceBounds(String question) {
        LocalDate today = LocalDate.of(2026, 9, 19);
        var original = occurrenceRange(today, today);
        var fixed = ChannelQueryPolicy.normalize(question, original, today);
        assertTrue(fixed.hasScope(ChannelContentScope.EVENTS));
        assertEquals(ChannelTimeScope.ANY_TIME, fixed.timeScope());
        assertFalse(fixed.hasEventDateRange());
    }

    @ParameterizedTest
    @ValueSource(strings={"What events are happening today or coming up?", "Какие ивенты будут завтра?",
            "Upcoming events this week", "Алдағы аптада қандай іс-шаралар өтеді?",
            "Какие мероприятия будут в ноябре?", "Upcoming events on Monday", "What events are coming up on 19 September?"})
    void explicitCalendarConstraintsAreNeverClearedByUpcomingWords(String question) {
        LocalDate today = LocalDate.of(2026, 9, 19);
        var original = occurrenceRange(today, today);
        var fixed = ChannelQueryPolicy.normalize(question, original, today);
        assertEquals(today, fixed.eventDateFrom());
        assertEquals(today, fixed.eventDateTo());
    }

    @Test void futureAndCustomOccurrenceRangesRemainIntactForBroadUpcomingWording() {
        LocalDate today = LocalDate.of(2026, 9, 19);
        var future = occurrenceRange(today.plusDays(1), today.plusDays(1));
        var fixedFuture = ChannelQueryPolicy.normalize("What evnts are coming up?", future, today);
        assertEquals(future.eventDateFrom(), fixedFuture.eventDateFrom());
        assertEquals(future.eventDateTo(), fixedFuture.eventDateTo());
        var custom = eventRange(today, today);
        var fixedCustom = ChannelQueryPolicy.normalize("What events are coming up in the selected period?", custom, today);
        assertEquals(today, fixedCustom.eventDateFrom());
        assertEquals(today, fixedCustom.eventDateTo());
    }

    private ChannelQueryAnalysis occurrenceRange(LocalDate from, LocalDate to) {
        return new ChannelQueryAnalysis(ChannelSearchIntent.GENERAL_UPDATES, null,
                List.of(ChannelContentScope.EVENTS), ChannelResultMode.RELEVANT, true, false, false, null,
                ChannelTimeScope.ANY_TIME, ChannelFreshnessScope.CURRENT, null, null, from, to);
    }

    @ParameterizedTest
    @ValueSource(strings={"Мероприятие будет проходить завтра?", "Когда будут проводить мастер-класс?",
            "What events will take place tomorrow?", "What workshops will be held next week?",
            "Алдағы іс-шаралар қашан өтеді?", "Іс-шара ертең болады ма?"})
    void pastVerbRecoveryDoesNotClassifyFutureEventsAsHistorical(String question) {
        var analysis = ChannelQueryPolicy.normalize(question, ChannelQueryAnalysis.unknown());
        assertTrue(analysis.hasScope(ChannelContentScope.EVENTS));
        assertEquals(ChannelFreshnessScope.CURRENT, analysis.freshnessScope());
    }
}
