package com.careerai.backend.channel;

/**
 * Результат поиска корневой публикации цепочки.
 */

public record TelegramChannelPostRootResolution(
        TelegramChannelPost rootPost,
        TelegramChannelPostRootResolutionStatus status,
        String reason
) {
    public boolean resolved() {
        return status ==
                TelegramChannelPostRootResolutionStatus.RESOLVED && rootPost != null;
    }
}
