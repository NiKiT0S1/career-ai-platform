package com.careerai.backend.message;

/**
 * Роль сообщения, сохранённого в истории чата.
 *
 * USER означает сообщение студента, ASSISTANT — ответ CareerAI,
 * а SYSTEM зарезервирован для будущих системных сообщений или внутреннего контекста.
 */

public enum MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
