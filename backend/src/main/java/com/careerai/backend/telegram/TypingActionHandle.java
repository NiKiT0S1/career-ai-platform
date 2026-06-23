package com.careerai.backend.telegram;

import java.util.concurrent.ScheduledFuture;

/**
 * Небольшой объект-ручка для остановки повторяющегося typing-действия.
 *
 * Когда бот начинает долгую генерацию ответа, typing запускается в фоне.
 * После получения ответа этот handle отменяет повторяющуюся задачу.
 */

public class TypingActionHandle {

    private final ScheduledFuture<?> future;

    public TypingActionHandle(ScheduledFuture<?> future) {
        this.future = future;
    }

    public void stop() {
        future.cancel(false);
    }
}
