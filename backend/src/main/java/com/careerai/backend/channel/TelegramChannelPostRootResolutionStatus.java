package com.careerai.backend.channel;

/**
 * Статус поиска корневой публикации цепочки.
 */

public enum TelegramChannelPostRootResolutionStatus {
    RESOLVED,  // Корневой пост найден
    POST_MISSING,  // Переданный пост отсутствует
    AMBIGUOUS,  // Пост связан сразу с несколькими разными исходными публикациями
    CYCLE_DETECTED,  // В цепочке обнаружена циклическая связь
    DEPTH_LIMIT_REACHED  // Цепочка оказалась подозрительно глубокой
}
