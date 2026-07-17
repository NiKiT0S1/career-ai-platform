package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Периодически пересчитывает актуальность
 * сохранённых Telegram-постов.
 *
 * Планировщик является дополнительной страховкой:
 * он обновляет статусы без перезапуска backend.
 */


@Component
@ConditionalOnProperty(
        prefix = "channel-post-freshness",
        name = "scheduled-recalculation-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChannelPostFreshnessScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChannelPostFreshnessScheduler.class);

    private final TelegramChannelPostFreshnessService freshnessService;

    public ChannelPostFreshnessScheduler(TelegramChannelPostFreshnessService freshnessService) {
        this.freshnessService = freshnessService;
    }

    /**
     * Запускает периодический полный пересчёт.
     *
     * Время запуска и часовой пояс задаются
     * через application.properties.
     */
    @Scheduled(
            cron = "${channel-post-freshness.recalculation-cron:0 5 * * * *}",
            zone = "${careerai.time-zone:Asia/Almaty}"
    )
    public void recalculateFreshness() {
        log.info("Scheduled channel post freshness recalculation triggered");

        try {
            freshnessService.recalculateAll();
        }
        catch (Exception e) {
            /*
             * Ошибка одного запуска не должна останавливать
             * последующие запуски планировщика.
             */
            log.error(
                    "Scheduled channel post freshness recalculation failed",
                    e
            );
        }
    }
}
