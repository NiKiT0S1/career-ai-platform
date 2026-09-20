package com.careerai.backend.answer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum AnswerLanguage {
    RU, KZ, EN;

    private static final Pattern PROTECTED_CONTENT = Pattern.compile("(?is)<code\\b[^>]*>.*?</code>|<pre\\b[^>]*>.*?</pre>|<[^>]*>|https?://[^\\s<>]+");
    private static final Pattern INTERNAL_STATUS = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:срок ист[её]к|срок не подтвержд[её]н|год не указан)(?![\\p{L}\\p{N}_])");

    public static AnswerLanguage detect(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.matches("(?s).*[әғқңөұүһі].*")
                || normalized.matches("(?s).*(салем|рахмет|барлык|вакансиялар|жаңалықтар).*") ) {
            return KZ;
        }
        return normalized.matches("(?s).*[а-яё].*") ? RU : EN;
    }

    public String select(String ru, String kz, String en) {
        return switch (this) {
            case RU -> ru;
            case KZ -> kz;
            case EN -> en;
        };
    }

    /** Localizes only known internal status labels; this is not a general answer translator. */
    public String localizeStatusLabels(String text) {
        if(this==RU||text==null||text.isEmpty())return text;
        StringBuilder result=new StringBuilder();
        Matcher protectedContent=PROTECTED_CONTENT.matcher(text);
        int start=0;
        while(protectedContent.find()) {
            result.append(localizePlainStatus(text.substring(start,protectedContent.start())));
            result.append(protectedContent.group());
            start=protectedContent.end();
        }
        return result.append(localizePlainStatus(text.substring(start))).toString();
    }

    private String localizePlainStatus(String text) {
        Matcher labels=INTERNAL_STATUS.matcher(text);
        return labels.replaceAll(match -> {
            String source=match.group();
            String translated=switch(source.toLowerCase(Locale.ROOT).replace('ё','е')) {
                case "срок истек" -> select(source,"мерзімі өткен","deadline has passed");
                case "срок не подтвержден" -> select(source,"мерзімі расталмаған","deadline not confirmed");
                case "год не указан" -> select(source,"жылы көрсетілмеген","year not specified");
                default -> source;
            };
            if(Character.isUpperCase(source.charAt(0))) translated=Character.toUpperCase(translated.charAt(0))+translated.substring(1);
            return Matcher.quoteReplacement(translated);
        });
    }
}
