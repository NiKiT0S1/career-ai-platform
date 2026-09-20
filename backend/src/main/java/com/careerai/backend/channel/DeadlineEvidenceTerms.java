package com.careerai.backend.channel;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** High-confidence constraints from the user's words, never from an invented router topic. */
final class DeadlineEvidenceTerms {
    private static final List<Pattern> TECHNOLOGIES = List.of(
            word("java(?!\\s*script)|джава|джав[аеыу]"), word("python|питон\\p{L}*"),
            word("javascript|java\\s*script|джаваскрипт"), word("typescript|type\\s*script"),
            word("kotlin|котлин"), word("swift"), word("c\\+\\+"), word("c#|c\\s*sharp"),
            word("php"), word("ruby"), word("rust"), word("golang"),
            word("react(?:\\.js)?"), word("angular"), word("vue(?:\\.js)?"),
            word("node(?:\\.js|js)"), word("spring(?:\\s+boot)?"), word("django"), word("fastapi"),
            word("postgresql|postgres"), word("mysql"), word("docker"), word("kubernetes"));
    private static final Pattern QUOTED_COMPANY = Pattern.compile(
            "(?iu)(?:компани\\p{L}*|работодател\\p{L}*|company|employer|компания\\p{L}*|жұмыс\\s+беруші\\p{L}*)"
                    + "\\s*[«\"“]([^»\"”]{2,100})[»\"”]");
    private final List<Pattern> technologies;
    private final List<String> companies;

    private DeadlineEvidenceTerms(List<Pattern> technologies, List<String> companies) {
        this.technologies = List.copyOf(technologies);
        this.companies = List.copyOf(companies);
    }

    static DeadlineEvidenceTerms fromQuestion(String question) {
        String text = normalize(question);
        List<Pattern> technologies = TECHNOLOGIES.stream().filter(pattern -> pattern.matcher(text).find()).toList();
        List<String> companies = new ArrayList<>();
        var matcher = QUOTED_COMPANY.matcher(text);
        while (matcher.find() && companies.size() < 3) companies.add(matcher.group(1).strip());
        return new DeadlineEvidenceTerms(technologies, companies);
    }

    boolean isEmpty() { return technologies.isEmpty() && companies.isEmpty(); }

    boolean matches(TelegramChannelPost post) {
        if (post == null) return false;
        String text = normalize(post.getText());
        return (technologies.isEmpty() || technologies.stream().anyMatch(pattern -> pattern.matcher(text).find()))
                && (companies.isEmpty() || companies.stream().anyMatch(text::contains));
    }

    private static Pattern word(String text) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:" + text + ")(?![\\p{L}\\p{N}])");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").strip();
    }
}
