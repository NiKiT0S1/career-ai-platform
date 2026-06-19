package com.careerai.backend.telegram;

import java.util.concurrent.ScheduledFuture;

public class TypingActionHandle {

    private final ScheduledFuture<?> future;

    public TypingActionHandle(ScheduledFuture<?> future) {
        this.future = future;
    }

    public void stop() {
        future.cancel(false);
    }
}
