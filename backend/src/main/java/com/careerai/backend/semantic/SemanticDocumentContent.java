package com.careerai.backend.semantic;

import com.careerai.backend.channel.TelegramChannelPost;
import com.careerai.backend.faq.FaqEntry;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** One canonical representation shared by indexing and stale-vector filtering. */
@Component
public class SemanticDocumentContent {
    private final SemanticContentHashService hashes;

    public SemanticDocumentContent(SemanticContentHashService hashes) {
        this.hashes = hashes;
    }

    public String channelTitle(TelegramChannelPost post) {
        return text(post.getChannelTitle()).isBlank()
                ? "Telegram-пост Центра карьеры и трудоустройства AITU"
                : "Telegram-пост канала " + post.getChannelTitle();
    }

    public String channelContent(TelegramChannelPost post) {
        return """
                Источник: Telegram-канал Центра карьеры и трудоустройства AITU
                Канал: %s
                Имя канала: %s
                Дата публикации: %s
                Дата редактирования: %s
                Текст поста:
                %s
                """.formatted(text(post.getChannelTitle()), text(post.getChannelUsername()),
                date(post.getPostedAt()), date(post.getEditedAt()), text(post.getText())).trim();
    }

    public String faqContent(FaqEntry entry) {
        return """
                Источник: FAQ Центра карьеры и трудоустройства AITU
                Категория: %s
                Вопрос: %s
                Краткий ответ: %s
                Полный ответ: %s
                Ключевые слова: %s
                """.formatted(text(entry.getCategory()).trim(), text(entry.getQuestion()).trim(),
                text(entry.getShortAnswer()).trim(), text(entry.getFullAnswer()).trim(),
                text(entry.getKeywords()).trim()).trim();
    }

    public String channelHash(TelegramChannelPost post) {
        return hashes.calculateHash(channelContent(post));
    }

    public String faqHash(FaqEntry entry) {
        return hashes.calculateHash(faqContent(entry));
    }

    private String date(OffsetDateTime value) { return value == null ? "" : value.toString(); }
    private String text(String value) { return value == null ? "" : value; }
}
