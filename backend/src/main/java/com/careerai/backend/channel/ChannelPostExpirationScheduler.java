package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Быстро обновляет публикации,
 * чей expiresAt наступил после последней проверки.
 */

@Component
@ConditionalOnProperty(
        prefix = "channel-post-freshness",
        name = "expiration-sweep-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChannelPostExpirationScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ChannelPostExpirationScheduler.class);

    private final TelegramChannelPostFreshnessService freshnessService;

    public ChannelPostExpirationScheduler(
            TelegramChannelPostFreshnessService freshnessService
    ) {
        this.freshnessService = freshnessService;
    }

    @Scheduled(
            cron = "${channel-post-freshness.expiration-sweep-cron:0 * * * * *}",
            zone = "${careerai.time-zone:Asia/Almaty}"
    )
    public void expireDuePosts() {
        try {
            freshnessService.expireDuePosts();
        }
        catch (Exception exception) {
            log.error("Channel post expiration sweep failed", exception);
        }
    }
}