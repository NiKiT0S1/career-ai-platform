package com.careerai.backend.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramChannelPostSearchEligibilityTest {

    private TelegramChannelPostSearchEligibility eligibility;
    private TelegramChannelPost post;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-22T04:12:00Z"),
                ZoneId.of("Asia/Almaty")
        );

        eligibility = new TelegramChannelPostSearchEligibility(clock);

        post = new TelegramChannelPost();
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.ACTIVE);
    }

    @Test
    void allowsActivePostWithFutureExpiration() {
        post.setExpiresAt(OffsetDateTime.parse("2026-07-23T00:00:00+05:00"));

        assertTrue(eligibility.isSearchable(post));
    }

    @Test
    void rejectsActivePostWhoseExpirationAlreadyPassed() {
        post.setExpiresAt(OffsetDateTime.parse("2026-07-22T00:00:00+05:00"));

        assertFalse(eligibility.isSearchable(post));
    }

    @Test
    void rejectsPostExactlyAtExpirationMoment() {
        post.setExpiresAt(OffsetDateTime.parse("2026-07-22T09:12:00+05:00"));

        assertFalse(eligibility.isSearchable(post));
    }

    @Test
    void allowsUnknownPostWithoutExpiration() {
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.UNKNOWN);
        post.setExpiresAt(null);

        assertTrue(eligibility.isSearchable(post));
    }

    @Test
    void rejectsArchivedPost() {
        post.setArchived(true);

        assertFalse(eligibility.isSearchable(post));
    }

    @Test
    void rejectsExpiredStatus() {
        post.setFreshnessStatus(TelegramChannelPostFreshnessStatus.EXPIRED);

        assertFalse(eligibility.isSearchable(post));
    }
}