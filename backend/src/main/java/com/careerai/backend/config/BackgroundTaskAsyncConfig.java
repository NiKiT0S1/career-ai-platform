package com.careerai.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Настраивает отдельные пулы для фоновых задач.
 */

@Configuration
@EnableAsync
public class BackgroundTaskAsyncConfig {

    @Bean(name = "channelRelationExecutor")
    public Executor channelRelationExecutor() {
        return createExecutor("channel-relation-", 1, 2, 100);
    }

    @Bean(name = "channelPostIndexingExecutor")
    public Executor channelPostIndexingExecutor() {
        return createExecutor("channel-index-", 1, 2, 200);
    }

    private Executor createExecutor(String threadPrefix, int corePoolSize, int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadPrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}