package com.careerai.backend.channel;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Извлекает ссылку Telegram Reply
 * из channel_post или edited_channel_post.
 */

@Component
public class TelegramReplyReferenceExtractor {

    /**
     * Возвращает message_id публикации,
     * на которую отвечает текущий пост.
     */
    public Long extractReplyToMessageId(JsonNode message) {
        if (message == null) {
            return null;
        }

        JsonNode replyToMessage = message.get("reply_to_message");

        if (replyToMessage == null || replyToMessage.isNull()) {
            return null;
        }

        JsonNode messageId = replyToMessage.get("message_id");

        if (messageId == null || messageId.isNull() || !messageId.isIntegralNumber()) {
            return null;
        }

        return messageId.asLong();
    }
}
