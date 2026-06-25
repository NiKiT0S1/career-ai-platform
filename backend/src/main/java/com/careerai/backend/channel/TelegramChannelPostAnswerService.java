package com.careerai.backend.channel;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Формирует быстрые ответы на основе сохранённых постов Telegram-канала.
 *
 * Этот сервис нужен, чтобы на вопросы про вакансии, стажировки и свежие объявления
 * backend мог отвечать напрямую из PostgreSQL, без обращения к LLM.
 */

@Service
public class TelegramChannelPostAnswerService {

    private static final int MAX_POSTS_IN_RESPONSE = 5;

    private static final ZoneId ASTANA_ZONE_ID = ZoneId.of("Asia/Almaty");

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TelegramChannelPostRepository repository;

    public TelegramChannelPostAnswerService(TelegramChannelPostRepository repository) {
        this.repository = repository;
    }

    public Optional<String> buildAnswerIfRelevant(String userMessage) {
        if (!isOpportunitySearchRequest(userMessage)) {
            return Optional.empty();
        }

        List<TelegramChannelPost> posts = repository.findLatestOpportunityPosts(
                PageRequest.of(0, MAX_POSTS_IN_RESPONSE)
        );

        if (posts.isEmpty()) {
            return Optional.of("""
                    <b>Свежие вакансии</b>
                    Пока в сохранённой базе Telegram-канала нет постов, похожих на вакансии или стажировки.
                    
                    Если объявления уже были опубликованы, подожди немного: backend должен получить их через Telegram updates.
                    """);
        }

        return Optional.of(buildOpportunityAnswer(posts));
    }

    private boolean isOpportunitySearchRequest(String text) {
        String normalizedText = text.toLowerCase();

        return normalizedText.contains("ваканс")
                || normalizedText.contains("стажиров")
                || normalizedText.contains("intern")
                || normalizedText.contains("junior")
                || normalizedText.contains("работ")
                || normalizedText.contains("что нового")
                || normalizedText.contains("свеж")
                || normalizedText.contains("объявлен");
    }

    private String buildOpportunityAnswer(List<TelegramChannelPost> posts) {
        StringBuilder answer = new StringBuilder();

        answer.append("<b>Свежие посты из Telegram-канала ЦКиТа</b>\n");
        answer.append("Нашёл в базе последние объявления, похожие на вакансии или стажировки.\n\n");

        for (int i = 0; i < posts.size(); i++) {
            TelegramChannelPost post = posts.get(i);

            answer.append("<b>").append(i + 1).append(". Объявление</b>\n");
            answer.append(shortenText(post.getText())).append("\n");

            String dataText = formatPostDate(post);

            if (dataText != null) {
                answer.append("<i>Дата: ").append(dataText).append("</i>\n");
            }

            answer.append("\n");
        }

        answer.append("Хочешь — я могу помочь разобрать, на какую из этих позиций тебе лучше откликнуться.");

        return answer.toString().trim();
    }

    private String shortenText(String text) {
        if (text == null || text.isBlank()) {
            return "Текст объявления отсутствует.";
        }

        String normalizedText = text
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        int maxLength = 900;

        if (normalizedText.length() <= maxLength) {
            return normalizedText;
        }

        return normalizedText.substring(0, maxLength).trim() + "...";
    }

    private String formatPostDate(TelegramChannelPost post) {
        OffsetDateTime dateTime = post.getEditedAt() != null
                ? post.getEditedAt()
                : post.getPostedAt();

        if (dateTime == null) {
            return null;
        }

        return dateTime
                .atZoneSameInstant(ASTANA_ZONE_ID)
                .format(DATE_TIME_FORMATTER);
    }
}
