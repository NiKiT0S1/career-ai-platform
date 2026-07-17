package com.careerai.backend.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramChannelPostSearchEligibilityTest {

    @Test
    void allowsActivePost() {
        TelegramChannelPost post =
                createPost(
                        TelegramChannelPostFreshnessStatus.ACTIVE,
                        false
                );

        assertTrue(post.isSearchable());
    }

    @Test
    void allowsUnknownPost() {
        TelegramChannelPost post =
                createPost(
                        TelegramChannelPostFreshnessStatus.UNKNOWN,
                        false
                );

        assertTrue(post.isSearchable());
    }

    @Test
    void rejectsExpiredPost() {
        TelegramChannelPost post =
                createPost(
                        TelegramChannelPostFreshnessStatus.EXPIRED,
                        false
                );

        assertFalse(post.isSearchable());
    }

    @Test
    void rejectsInvalidPost() {
        TelegramChannelPost post =
                createPost(
                        TelegramChannelPostFreshnessStatus.INVALID,
                        false
                );

        assertFalse(post.isSearchable());
    }

    @Test
    void rejectsArchivedActivePost() {
        TelegramChannelPost post =
                createPost(
                        TelegramChannelPostFreshnessStatus.ACTIVE,
                        true
                );

        assertFalse(post.isSearchable());
    }

    private TelegramChannelPost createPost(
            TelegramChannelPostFreshnessStatus status,
            boolean archived
    ) {
        TelegramChannelPost post =
                new TelegramChannelPost();

        post.setFreshnessStatus(status);
        post.setArchived(archived);

        return post;
    }
}