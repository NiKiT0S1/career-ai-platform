package com.careerai.backend.channel;

/**
 * Показывает происхождение связи
 * между Telegram-публикациями.
 */

public enum TelegramChannelPostRelationOrigin {
    MANUAL,  // Связь создана вручную
    TELEGRAM_REPLY,  // Связь получена из Telegram reply_to_message
    ADMIN_CONFIRMED,  // Связь проверена и подтверждена администратором
    SYSTEM_BACKFILL  // Связь восстановлена системной фоновой обработкой
}
