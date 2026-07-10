package com.careerai.backend.faq;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Сервис для работы с FAQ-базой ЦКиТ.
 *
 * На данном этапе сервис умеет формировать короткий список вопросов и ответов
 * для команды /faq.
 */

@Service
public class FaqEntryService {

    private final FaqEntryRepository repository;

    public FaqEntryService(FaqEntryRepository repository) {
        this.repository = repository;
    }

    public List<FaqEntry> findActiveEntries() {
        return repository.findAllActiveOrderByPriority();
    }

    public String buildFaqListMessage() {
        List<FaqEntry> entries = findActiveEntries();

        if (entries.isEmpty()) {
            return """
                    <b>Частые вопросы ЦКиТ</b>

                    FAQ-база пока пуста.
                    """;
        }

        StringBuilder message = new StringBuilder();

        message.append("<b>Частые вопросы ЦКиТ</b>\n\n");

        for (int i = 0; i < entries.size(); i++) {
            FaqEntry entry = entries.get(i);

            message.append("<b>")
                    .append(i + 1)
                    .append(". ")
                    .append(escapeTelegramHtml(entry.getQuestion()))
                    .append("</b>")
                    .append("\n");

            message.append(escapeTelegramHtml(entry.getShortAnswer()))
                    .append("\n\n");
        }

        message.append("Если нужен более подробный ответ — напиши вопрос обычным сообщением.");

        return message.toString().trim();
    }

    private String escapeTelegramHtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text
                .replaceAll("&", "&amp;")
                .replaceAll("<", "&lt;")
                .replaceAll(">", "&gt;");
    }
}
