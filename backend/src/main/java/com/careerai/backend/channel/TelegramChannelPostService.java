package com.careerai.backend.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Сервис для сохранения Telegram-постов из канала ЦКиТа.
 *
 * При новом channel_post создаёт запись в БД. При edited_channel_post ищет уже
 * существующий пост и обновляет текст, дату редактирования и сырой JSON.
 */

@Service
public class TelegramChannelPostService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannelPostService.class);

    private final TelegramChannelPostRepository repository;
    private final TelegramChannelPostMetadataService metadataService;
    private final TelegramReplyReferenceExtractor replyReferenceExtractor;
    private final ApplicationEventPublisher eventPublisher;

    public TelegramChannelPostService(
            TelegramChannelPostRepository repository,
            TelegramChannelPostMetadataService metadataService,
            TelegramReplyReferenceExtractor replyReferenceExtractor,
            ApplicationEventPublisher eventPublisher
    ) {
        this.repository = repository;
        this.metadataService = metadataService;
        this.replyReferenceExtractor = replyReferenceExtractor;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void saveOrUpdateChannelPost(JsonNode message, String rawUpdateJson, boolean edited) {
        if (!message.has("chat") || !message.has("message_id")) {
            return;
        }

        JsonNode chat = message.get("chat");

        Long telegramChatId = chat.get("id").asLong();
        Long telegramMessageId = message.get("message_id").asLong();

        String text = extractText(message);

        /*
         * Если публикация является ответом на другой пост,
         * сохраняем Telegram message_id исходной публикации.
         */
        Long replyToTelegramMessageId = replyReferenceExtractor.extractReplyToMessageId(message);

        TelegramChannelPost post = repository
                .findByTelegramChatIdAndTelegramMessageId(telegramChatId, telegramMessageId)
                .orElseGet(TelegramChannelPost::new);

        post.setTelegramChatId(telegramChatId);
        post.setTelegramMessageId(telegramMessageId);
        post.setChannelTitle(extractNullableText(chat, "title"));
        post.setChannelUsername(extractNullableText(chat, "username"));
        post.setText(text);
        post.setRawUpdateJson(rawUpdateJson);
        post.setPostedAt(extractTelegramDate(message, "date"));

        /*
         * Reply-связь Telegram является неизменяемой.
         *
         * При редактировании Telegram иногда может не прислать
         * полный объект reply_to_message, поэтому уже сохранённое
         * значение нельзя случайно стереть.
         */
        if (post.getId() == null || replyToTelegramMessageId != null) {
            post.setReplyToTelegramMessageId(replyToTelegramMessageId);
        }

        if (edited) {
            post.setEditedAt(extractTelegramDate(message, "edit_date"));
        }

        TelegramChannelPost savedPost = repository.save(post);

        metadataService.createOrResetMetadata(savedPost, edited);

        eventPublisher.publishEvent(
                new TelegramChannelPostSavedEvent(savedPost.getId())
        );

        log.info(
                "Telegram channel post saved. postId={}, chatId={}, messageId={}, replyToMessageId={}, edited={}, textLength={}",
                savedPost.getId(),
                telegramChatId,
                telegramMessageId,
                savedPost.getReplyToTelegramMessageId(),
                edited,
                text == null ? 0 : text.length()
        );
    }

    private String extractText(JsonNode message) {
        if (message.has("text")) {
            return message.get("text").asText();
        }

        if (message.has("caption")) {
            return message.get("caption").asText();
        }

        return "";
    }

    private String extractNullableText(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }

        return node.get(fieldName).asText();
    }

    private OffsetDateTime extractTelegramDate(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }

        long epochSecond = node.get(fieldName).asLong();

        return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(epochSecond),
                ZoneOffset.UTC
        );
    }
}
