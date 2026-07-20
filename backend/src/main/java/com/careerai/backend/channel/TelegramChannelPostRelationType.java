package com.careerai.backend.channel;

/**
 * Показывает, как более новый Telegram-пост
 * связан с исходной публикацией.
 */

public enum TelegramChannelPostRelationType {

    UPDATE,  // Пост дополняет исходную публикацию новой информацией
    CORRECTION,  // Пост исправляет ранее опубликованную информацию
    CANCELLATION  // Пост отменяет всю публикацию или отдельное указанное в ней условие
}