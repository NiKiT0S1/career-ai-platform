package com.careerai.backend.telegram;

import com.careerai.backend.admin.AdminProperties;
import com.careerai.backend.runtime.BotRuntimeStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramAdminMenuServiceTest {
    private final AdminProperties properties = new AdminProperties();
    private final TelegramBotService bot = mock(TelegramBotService.class);
    private final BotRuntimeStateService runtime = mock(BotRuntimeStateService.class);
    private final Clock clock = mock(Clock.class);
    private final AtomicReference<String> registry = new AtomicReference<>("");
    private TelegramAdminMenuService service;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        properties.setTelegramUserIds(Set.of(123L));
        properties.setPublicUrl("https://career.example.org/admin/");
        when(clock.instant()).thenReturn(Instant.parse("2026-09-20T00:00:00Z"));
        when(runtime.getValue(TelegramAdminMenuService.TRACKED_CHATS_KEY))
                .thenAnswer(ignored -> Optional.of(registry.get()));
        doAnswer(invocation -> { registry.set(invocation.getArgument(1)); return null; })
                .when(runtime).setValue(eq(TelegramAdminMenuService.TRACKED_CHATS_KEY), anyString());
        service = newService();
    }

    private TelegramAdminMenuService newService() {
        return new TelegramAdminMenuService(properties, new TelegramAdminLaunchService(properties), bot, runtime, clock);
    }

    @Test
    void startupInstallsPrivateAdminButtonAndResetsPreviouslyAllowedUsersWithoutMessages() {
        registry.set("456");
        service.initialize();

        verify(bot).setCommandMenu(null);
        verify(bot).setDefaultCommands();
        verify(bot).setAdminMenu(123L, properties.getPublicUrl());
        verify(bot).setCommandMenu(456L);
        verifyNoMoreInteractions(bot);
        assertEquals("123", registry.get());
    }

    @Test
    void unchangedMenusDoNotCallTelegramAgainOnEveryMessageOrScheduledSweep() {
        service.initialize();
        clearInvocations(bot, runtime);
        for (int i = 0; i < 5; i++) {
            service.onInteraction("private", 123L, 123L);
            service.onInteraction("private", 456L, 456L);
            service.reconcileConfiguredMenus();
        }
        verify(bot).setCommandMenu(456L);
        verifyNoMoreInteractions(bot);
        verifyNoInteractions(runtime);
    }

    @Test
    void changingTunnelUrlUpdatesOnlyTheAffectedMenuWithoutWaitingForRetryDelay() {
        service.initialize();
        clearInvocations(bot);
        properties.setPublicUrl("https://new-career.example.org/admin/");
        service.onInteraction("private", 123L, 123L);
        verify(bot).setAdminMenu(123L, properties.getPublicUrl());
        verifyNoMoreInteractions(bot);
    }

    @Test
    void revocationAndDisabledAdminRestoreCommandsAndRemoveKeyboard() {
        service.initialize();
        properties.setTelegramUserIds(Set.of());
        service.onInteraction("private", 123L, 123L);
        verify(bot).setCommandMenu(123L);
        assertEquals("", registry.get());
        assertEquals(Map.of("remove_keyboard", true), service.replyMarkup("private", 123L, 123L));

        properties.setTelegramUserIds(Set.of(123L));
        service.onInteraction("private", 123L, 123L);
        properties.setEnabled(false);
        service.reconcileConfiguredMenus();
        verify(bot, times(2)).setCommandMenu(123L);
        assertEquals("", registry.get());
    }

    @Test
    void restartAfterRevocationRecoversOldChatIdsFromPersistentRegistry() {
        service.initialize();
        properties.setTelegramUserIds(Set.of());
        clearInvocations(bot);
        newService().initialize();
        verify(bot).setCommandMenu(123L);
        verify(bot, never()).setAdminMenu(anyLong(), anyString());
        assertEquals("", registry.get());
    }

    @Test
    void invalidUrlFallsBackToCommandsInsteadOfInstallingBrokenWebApp() {
        registry.set("123");
        properties.setPublicUrl("http://localhost:8080/admin/");
        service.initialize();
        verify(bot).setCommandMenu(123L);
        verify(bot, never()).setAdminMenu(anyLong(), anyString());
        assertEquals(Map.of("remove_keyboard", true), service.replyMarkup("private", 123L, 123L));
    }

    @Test
    void onlyOwnPrivateChatReceivesPersistentQuickCommandKeyboard() {
        var keyboard = service.replyMarkup("private", 123L, 123L);
        assertEquals(true, keyboard.get("is_persistent"));
        assertEquals(false, keyboard.get("one_time_keyboard"));
        assertFalse(keyboard.toString().contains("web_app"));
        assertNull(service.replyMarkup("group", -1L, 123L));
        assertNull(service.replyMarkup("private", 456L, 123L));
        service.onInteraction("group", -1L, 123L);
        service.onInteraction("private", 456L, 123L);
        verifyNoInteractions(bot, runtime);
    }

    @Test
    void transientFailureRetriesAfterBackoffAndNeverDisruptsMessageProcessing() {
        doThrow(new IllegalStateException("simulated failure"))
                .doNothing().when(bot).setAdminMenu(123L, properties.getPublicUrl());
        assertDoesNotThrow(service::initialize);
        assertEquals("123", registry.get());
        service.onInteraction("private", 123L, 123L);
        verify(bot).setAdminMenu(123L, properties.getPublicUrl());

        when(clock.instant()).thenReturn(Instant.parse("2026-09-20T00:01:01Z"));
        service.reconcileConfiguredMenus();
        service.onInteraction("private", 123L, 123L);
        verify(bot, times(2)).setAdminMenu(123L, properties.getPublicUrl());
    }

    @Test
    void failedPersistenceDoesNotInstallAnUntrackedAdminMenu() {
        doThrow(new IllegalStateException("simulated database failure"))
                .when(runtime).setValue(TelegramAdminMenuService.TRACKED_CHATS_KEY, "123");
        assertDoesNotThrow(service::initialize);
        verify(bot, never()).setAdminMenu(anyLong(), anyString());
    }

    @Test
    void defaultMenuFailureRetriesWithoutRepeatedlyChangingSuccessfulPrivateMenus() {
        doThrow(new IllegalStateException("simulated failure")).doNothing().when(bot).setDefaultCommands();
        service.initialize();
        service.reconcileConfiguredMenus();
        verify(bot).setDefaultCommands();
        when(clock.instant()).thenReturn(Instant.parse("2026-09-20T00:01:01Z"));
        service.reconcileConfiguredMenus();
        verify(bot, times(2)).setDefaultCommands();
        verify(bot).setAdminMenu(123L, properties.getPublicUrl());
    }

    @Test
    void scheduledHookBeforePollingInitializationDoesNothing() {
        service.reconcileConfiguredMenus();
        verifyNoInteractions(bot, runtime);
    }
}
