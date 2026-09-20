package com.careerai.backend.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class TelegramChannelPostFreshnessEvaluatorTest {
    private TelegramChannelPostFreshnessEvaluator evaluator;
    private TelegramChannelPost post;
    private TelegramChannelPostMetadata metadata;

    @BeforeEach
    void setUp() {
        Clock clock=Clock.fixed(Instant.parse("2026-09-07T13:00:00Z"),ZoneId.of("Asia/Almaty"));
        evaluator=new TelegramChannelPostFreshnessEvaluator(new MultilingualDateTextParser(new MultilingualMonthDictionary(),new MultilingualDateBoundaryDetector()),clock);
        post=new TelegramChannelPost();post.setId(19L);post.setPostedAt(OffsetDateTime.parse("2026-07-09T10:00:00+05:00"));
        metadata=new TelegramChannelPostMetadata();metadata.setPost(post);metadata.setPostType(TelegramChannelPostType.DEADLINE);
        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
    }

    @Test
    void julyAnnouncementWithAprilDeadlineRequiresYearConfirmation() {
        post.setText("Дедлайн — 1 апреля");metadata.setDeadlineText("1 апреля");
        var result=evaluator.evaluate(post,metadata);
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,result.status());
        assertNull(result.expiresAt());assertTrue(result.reason().contains("Год"));
    }

    @Test
    void inventedMetadataYearCannotExpirePublication() {
        post.setText("Продлеваем срок сдачи документов до 7 сентября");metadata.setDeadlineText("7 сентября 2026");
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,evaluator.evaluate(post,metadata).status());
    }

    @Test
    void ambiguousDoKeepsLastDaySearchableAndLabelsUncertainty() {
        post.setText("Продлеваем срок сдачи документов до 7 сентября 2026");metadata.setDeadlineText("7 сентября");
        var result=evaluator.evaluate(post,metadata);
        assertEquals(TelegramChannelPostFreshnessStatus.ACTIVE,result.status());
        assertEquals(OffsetDateTime.parse("2026-09-08T00:00:00+05:00"),result.expiresAt());
        assertTrue(result.reason().contains("требует уточнения"));
    }

    @Test
    void explicitExclusionExpiresAtBeginningOfDay() {
        post.setText("Документы строго до 7 сентября 2026");metadata.setDeadlineText("7 сентября");
        var result=evaluator.evaluate(post,metadata);
        assertEquals(TelegramChannelPostFreshnessStatus.EXPIRED,result.status());
        assertEquals(OffsetDateTime.parse("2026-09-07T00:00:00+05:00"),result.expiresAt());
    }

    @Test
    void sourceExplicitYearOverridesLLMSummaryYear() {
        post.setText("Подать документы по 7 сентября 2027");metadata.setDeadlineText("7 сентября 2026");
        assertEquals(OffsetDateTime.parse("2027-09-08T00:00:00+05:00"),evaluator.evaluate(post,metadata).expiresAt());
    }

    @Test
    void missingOriginalSourceCannotValidateGeneratedMetadata() {
        metadata.setDeadlineText("7 сентября 2026");
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,evaluator.evaluate(post,metadata).status());
    }

    @Test
    void confirmedDateCanResolveYearEvenBeforeExtraction() {
        post.setText("Дедлайн 1 апреля");confirm(LocalDate.of(2027,4,1));
        var result=evaluator.evaluate(post,null);
        assertEquals(TelegramChannelPostFreshnessStatus.ACTIVE,result.status());
        assertEquals(OffsetDateTime.parse("2027-04-02T00:00:00+05:00"),result.expiresAt());
        assertTrue(result.reason().contains("подтверждено администратором"));
    }

    @Test
    void sourceEditInvalidatesConfirmationInsteadOfReusingStaleDate() {
        post.setText("Дедлайн 1 апреля");confirm(LocalDate.of(2027,4,1));
        post.setText("Дедлайн 1 мая");
        assertFalse(post.hasCurrentDateConfirmation());
        var result=evaluator.evaluate(post,metadata);
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,result.status());assertNull(result.expiresAt());
        assertTrue(result.reason().contains("изменена"));
    }

    @Test
    void telegramEditTimestampInvalidatesEvenSameTextButDatabaseRevisionDoesNot() {
        post.setText("Дедлайн 1 апреля");confirm(LocalDate.of(2027,4,1));
        post.setRevision(123);post.setUpdatedAt(OffsetDateTime.now());assertTrue(post.hasCurrentDateConfirmation());
        post.setEditedAt(post.getPostedAt().plusDays(1));assertFalse(post.hasCurrentDateConfirmation());
    }

    @Test
    void revocationRestoresSourceAmbiguityAndClearsAllConfirmationFields() {
        post.setText("Дедлайн 1 апреля");confirm(LocalDate.of(2027,4,1));post.clearDateConfirmation();
        assertFalse(post.hasCurrentDateConfirmation());assertNull(post.getConfirmedDate());assertNull(post.getConfirmedDateSourceHash());
        assertNull(post.getDateConfirmedBy());assertNull(post.getDateConfirmedAt());assertNull(post.getDateConfirmationReason());
        assertEquals(TelegramChannelPostFreshnessStatus.UNKNOWN,evaluator.evaluate(post,metadata).status());
    }

    private void confirm(LocalDate date) {
        post.setConfirmedDate(date);post.setConfirmedDateBoundary(DateBoundaryType.INCLUSIVE);
        post.setConfirmedDatePurpose(ChannelPostDatePurpose.APPLICATION_DEADLINE);post.setConfirmedDateSourceHash(post.dateConfirmationSourceHash());
        post.setDateConfirmedBy(123L);post.setDateConfirmedAt(post.getPostedAt());post.setDateConfirmationReason("Уточнено у менеджера");
    }
}
