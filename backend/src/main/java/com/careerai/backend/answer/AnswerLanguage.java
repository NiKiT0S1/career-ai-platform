package com.careerai.backend.answer;

import java.util.Locale;

public enum AnswerLanguage {
    RU, KZ, EN;

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
}
