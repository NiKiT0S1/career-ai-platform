package com.careerai.backend.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelegramChannelPostFreshnessEvaluatorTest {

    private TelegramChannelPostFreshnessEvaluator evaluator;
    private TelegramChannelPost post;
    private TelegramChannelPostMetadata metadata;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-21T13:00:00Z"),
                ZoneId.of("Asia/Almaty")
        );

        MultilingualDateTextParser parser = new MultilingualDateTextParser(
                new MultilingualMonthDictionary(),
                new MultilingualDateBoundaryDetector()
        );

        evaluator = new TelegramChannelPostFreshnessEvaluator(parser, clock);

        post = new TelegramChannelPost();
        post.setId(19L);
        post.setPostedAt(OffsetDateTime.parse("2026-07-21T10:00:00+05:00"));

        metadata = new TelegramChannelPostMetadata();
        metadata.setPost(post);
        metadata.setPostType(TelegramChannelPostType.VACANCY);
        metadata.setExtractionStatus(TelegramChannelPostExtractionStatus.SUCCESS);
    }

    @Test
    void expiresExclusiveDeadlineAtStartOfSpecifiedDate() {
        metadata.setDeadlineText("до 21 июля");

        TelegramChannelPostFreshnessEvaluation result = evaluator.evaluate(post, metadata);

        assertEquals(TelegramChannelPostFreshnessStatus.EXPIRED, result.status());
        assertEquals(
                OffsetDateTime.parse("2026-07-21T00:00:00+05:00"),
                result.expiresAt()
        );
    }

    @Test
    void keepsBareDeadlineActiveDuringSpecifiedDate() {
        metadata.setDeadlineText("21 июля");

        TelegramChannelPostFreshnessEvaluation result = evaluator.evaluate(post, metadata);

        assertEquals(TelegramChannelPostFreshnessStatus.ACTIVE, result.status());
        assertEquals(
                OffsetDateTime.parse("2026-07-22T00:00:00+05:00"),
                result.expiresAt()
        );
    }

    @Test
    void keepsExplicitInclusiveDeadlineActiveDuringSpecifiedDate() {
        metadata.setDeadlineText("до 21 июля включительно");

        TelegramChannelPostFreshnessEvaluation result = evaluator.evaluate(post, metadata);

        assertEquals(TelegramChannelPostFreshnessStatus.ACTIVE, result.status());
        assertEquals(
                OffsetDateTime.parse("2026-07-22T00:00:00+05:00"),
                result.expiresAt()
        );
    }

    @Test
    void restoresExclusiveBoundaryFromOriginalPostText() {
        post.setText("""
            Тестовая вакансия №9

            SQL Dev

            Дедлайн: до 21 июля

            Формат: Гибрид
            Компания: Jusan Bank
            """);

        /*
         * Имитируем реальное поведение LLM:
         * дата извлечена правильно, но слово "до" потеряно.
         */
        metadata.setDeadlineText("21 июля");

        TelegramChannelPostFreshnessEvaluation result =
                evaluator.evaluate(post, metadata);

        assertEquals(
                TelegramChannelPostFreshnessStatus.EXPIRED,
                result.status()
        );

        assertEquals(
                OffsetDateTime.parse("2026-07-21T00:00:00+05:00"),
                result.expiresAt()
        );
    }

    @Test
    void keepsBareDateInclusiveWhenOriginalTextHasNoBoundary() {
        post.setText("""
            Тестовая вакансия №9

            SQL Dev

            Дедлайн: 21 июля
            """);

        metadata.setDeadlineText("21 июля");

        TelegramChannelPostFreshnessEvaluation result =
                evaluator.evaluate(post, metadata);

        assertEquals(
                TelegramChannelPostFreshnessStatus.ACTIVE,
                result.status()
        );

        assertEquals(
                OffsetDateTime.parse("2026-07-22T00:00:00+05:00"),
                result.expiresAt()
        );
    }
}