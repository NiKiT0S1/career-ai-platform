package com.careerai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Настраивает единое системное время приложения.
 */

@Configuration
public class ApplicationTimeConfig {

    @Bean
    public Clock applicationClock(
            @Value("${careerai.time-zone:Asia/Almaty}")
            String timeZone
    ) {
        return Clock.system(
                ZoneId.of(timeZone)
        );
    }
}
