package com.careerai.backend.channel;

/**
 * Определяет, сколько результатов ожидает пользователь.
 */

public enum ChannelResultMode {
    RELEVANT,  // Нужны только наиболее подходящие публикации
    ALL_MATCHING  // Пользователь явно запросил полный список
}