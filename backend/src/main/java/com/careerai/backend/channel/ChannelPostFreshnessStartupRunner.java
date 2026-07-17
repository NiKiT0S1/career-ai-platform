package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Позволяет вручную запустить массовый пересчёт
 * freshness-статусов при старте backend.
 */

@Component
public class ChannelPostFreshnessStartupRunner
        implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ChannelPostFreshnessStartupRunner.class
            );

    private final TelegramChannelPostFreshnessService
            freshnessService;

    private final boolean recalculateOnStartup;

    public ChannelPostFreshnessStartupRunner(
            TelegramChannelPostFreshnessService freshnessService,
            @Value(
                    "${channel-post-freshness."
                            + "recalculate-on-startup:false}"
            )
            boolean recalculateOnStartup
    ) {
        this.freshnessService = freshnessService;
        this.recalculateOnStartup =
                recalculateOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!recalculateOnStartup) {
            log.info(
                    "Channel post freshness recalculation "
                            + "skipped: startup recalculation is disabled"
            );
            return;
        }

        freshnessService.recalculateAll();
    }
}