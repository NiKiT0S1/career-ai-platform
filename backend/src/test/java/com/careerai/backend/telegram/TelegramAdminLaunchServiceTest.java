package com.careerai.backend.telegram;

import com.careerai.backend.admin.AdminProperties;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TelegramAdminLaunchServiceTest {
    private final AdminProperties properties = new AdminProperties();
    private final TelegramAdminLaunchService service = new TelegramAdminLaunchService(properties);

    @Test
    void onlyEnabledAllowlistedPrivateSenderReceivesTheWebAppUrl() {
        properties.setEnabled(true);
        properties.setTelegramUserIds(Set.of(123L));
        properties.setPublicUrl("https://career.example.org/admin/");
        assertTrue(service.prepare("private", 123L).allowed());
        assertEquals(properties.getPublicUrl(), service.prepare("private", 123L).webAppUrl());
        assertFalse(service.prepare("private", 456L).allowed());
        assertFalse(service.prepare("private", 0L).allowed());
        assertFalse(service.prepare("group", 123L).allowed());
        assertFalse(service.prepare("supergroup", 123L).allowed());
        properties.setEnabled(false);
        assertFalse(service.prepare("private", 123L).allowed());
    }

    @Test
    void refusesMissingInsecureOrCredentialBearingUrls() {
        properties.setEnabled(true);
        properties.setTelegramUserIds(Set.of(123L));
        for (String url : new String[]{"", "http://career.example.org/admin/", "javascript:alert(1)",
                "https://user:password@career.example.org/admin/", "https://career.example.org/#fragment", "https:///admin"}) {
            properties.setPublicUrl(url);
            assertFalse(service.prepare("private", 123L).allowed(), url);
        }
    }
}
