package com.careerai.backend.telegram;

import com.careerai.backend.admin.AdminProperties;
import org.springframework.stereotype.Service;
import java.net.URI;

/** Bot-side convenience only; the admin API independently validates signed Telegram initData. */
@Service
public class TelegramAdminLaunchService {
    private final AdminProperties properties;

    public TelegramAdminLaunchService(AdminProperties properties) { this.properties = properties; }

    public Launch prepare(String chatType, long senderId) {
        if (!"private".equals(chatType)) {
            return new Launch("Открой команду /admin в личном чате с ботом.", null);
        }
        if (!properties.isEnabled() || senderId <= 0 || properties.getTelegramUserIds() == null
                || !properties.getTelegramUserIds().contains(senderId)) {
            return new Launch("Панель администратора недоступна для этого аккаунта.", null);
        }
        if (!isSecureWebAppUrl(properties.getPublicUrl())) {
            return new Launch("Панель ещё не настроена: нужен публичный HTTPS-адрес. Обратись к разработчику.", null);
        }
        return new Launch("Открыть панель управления CareerAI:", URI.create(properties.getPublicUrl().strip()).toASCIIString());
    }

    static boolean isSecureWebAppUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.strip());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().isBlank() && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record Launch(String message, String webAppUrl) {
        public boolean allowed() { return webAppUrl != null; }
    }
}
