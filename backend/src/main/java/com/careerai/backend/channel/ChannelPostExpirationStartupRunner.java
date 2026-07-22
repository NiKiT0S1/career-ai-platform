package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * При запуске быстро обновляет только публикации,
 * чей сохранённый expiresAt уже наступил.
 *
 * В отличие от полного recalculateAll(),
 * не загружает и не анализирует все metadata.
 */

@Component
@Order(10)
public class ChannelPostExpirationStartupRunner implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ChannelPostExpirationStartupRunner.class);

    private final TelegramChannelPostFreshnessService freshnessService;

    public ChannelPostExpirationStartupRunner(
            TelegramChannelPostFreshnessService freshnessService
    ) {
        this.freshnessService = freshnessService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int expiredCount = freshnessService.expireDuePosts();
        log.info("Startup channel post expiration sweep finished. expired={}", expiredCount);
    }
}