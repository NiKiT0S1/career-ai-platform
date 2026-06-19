package com.careerai.backend.telegram;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class TelegramTypingService {

    private final TelegramBotService telegramBotService;
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

    public TelegramTypingService(TelegramBotService telegramBotService) {
        this.telegramBotService = telegramBotService;
    }

    public TypingActionHandle startTyping(long chatId) {
        telegramBotService.sendTypingAction(chatId);

        ScheduledFuture<?> future = executorService.scheduleAtFixedRate(
                () -> telegramBotService.sendTypingAction(chatId),
                4,
                4,
                TimeUnit.SECONDS
        );

        return new TypingActionHandle(future);
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }
}
