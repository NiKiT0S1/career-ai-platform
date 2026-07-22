package com.careerai.backend.channel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Единая точка проверки доступности Telegram-поста
 * для обычного пользовательского поиска.
 */

@Component
public class TelegramChannelPostSearchEligibility {

    private final Clock clock;

    public TelegramChannelPostSearchEligibility(Clock clock) {
        this.clock = clock;
    }

    public boolean isSearchable(TelegramChannelPost post) {
        return post != null && post.isSearchable(OffsetDateTime.now(clock));
    }

    public boolean isSearchable(TelegramChannelPost post, OffsetDateTime now) {
        return post != null && post.isSearchable(now);
    }
}