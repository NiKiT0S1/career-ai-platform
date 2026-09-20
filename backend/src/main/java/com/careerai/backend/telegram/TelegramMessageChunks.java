package com.careerai.backend.telegram;

import org.springframework.web.util.HtmlUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Long answers retain every condition and source URL when converted to plain text. */
final class TelegramMessageChunks {
    static final int LIMIT = 4000;
    private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a>");
    private TelegramMessageChunks() {}
    static String plain(String html) {
        String links = LINK.matcher(html).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                match.group(3) + " (" + match.group(2) + ")"));
        return HtmlUtils.htmlUnescape(links.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n").replaceAll("<[^>]*>", ""));
    }
    static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + LIMIT, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline > start + LIMIT / 2) end = newline + 1;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }
}
