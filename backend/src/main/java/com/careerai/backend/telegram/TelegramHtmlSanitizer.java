package com.careerai.backend.telegram;

import org.springframework.stereotype.Service;

/**
 * Очищает AI-ответ перед отправкой в Telegram.
 *
 * Некоторые LLM могут возвращать запрещённые Telegram HTML-теги:
 * <think>, <h3>, <p>, <ul>, <li> и другие. Telegram из-за этого отклоняет
 * сообщение с ошибкой "can't parse entities". Этот сервис оставляет только
 * безопасные теги: <b>, <i>, <code>.
 */

@Service
public class TelegramHtmlSanitizer {

    public String sanitizeHtml(String text) {
        if (text == null || text.isBlank()) {
            return "Я подготовил ответ, но он оказался пустым. Попробуй переформулировать вопрос.";
        }

        String sanitized = text;

        sanitized = removeThinkBlocks(sanitized);
        sanitized = normalizeMarkdownBold(sanitized);
        sanitized = removeMarkdownHeaders(sanitized);
        sanitized = normalizeAllowedTags(sanitized);
        sanitized = removeUnsupportedHtmlTags(sanitized);
        sanitized = normalizeEmptyLines(sanitized);

        if (sanitized.isBlank()) {
            return "Я подготовил ответ, но после очистки форматирования он оказался пустым.";
        }

        return sanitized.trim();
    }

    private String removeThinkBlocks(String text) {
        return text
                .replaceAll("(?is)<think>.*?</think>", "")
                .replaceAll("(?is)<thinking>.*?</thinking>", "");
    }

    private String normalizeMarkdownBold(String text) {
        return text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
    }

    private String removeMarkdownHeaders(String text) {
        return text.replaceAll("(?m)^#{1,6}\\s*", "");
    }

    private String normalizeAllowedTags(String text) {
        return text
                .replaceAll("(?i)<b\\s+[^>]*>", "<b>")
                .replaceAll("(?i)<i\\s+[^>]*>", "<i>")
                .replaceAll("(?i)<code\\s+[^>]*>", "<code>");
    }

    private String removeUnsupportedHtmlTags(String text) {
        return text.replaceAll(
                "(?i)</?(?!b\\b|i\\b|code\\b)[a-z][a-z0-9]*(?:\\s+[^>]*)?>",
                ""
        );
    }

    private String normalizeEmptyLines(String text) {
        return text
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
    }
}
