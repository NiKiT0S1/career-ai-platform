package com.careerai.backend.telegram;

import com.careerai.backend.admin.AdminProperties;
import com.careerai.backend.runtime.BotRuntimeStateService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Chat UI is convenience only. Admin API authorization remains independent of visible buttons. */
@Service
@ConditionalOnProperty(name = "telegram.bot.polling-enabled", havingValue = "true", matchIfMissing = true)
public class TelegramAdminMenuService {
    static final String TRACKED_CHATS_KEY = "telegram_admin_menu_chat_ids";
    static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(TelegramAdminMenuService.class);
    private static final String COMMANDS = "commands";
    private static final Map<String, Object> QUICK_COMMANDS = Map.of(
            "keyboard", List.of(
                    List.of(Map.of("text", "📋 Частые вопросы"), Map.of("text", "ℹ️ Помощь")),
                    List.of(Map.of("text", "🤖 О боте"), Map.of("text", "🆔 Мой ID"))),
            "resize_keyboard", true, "is_persistent", true, "one_time_keyboard", false);
    private static final Map<String, Object> REMOVE_KEYBOARD = Map.of("remove_keyboard", true);

    private final AdminProperties properties;
    private final TelegramAdminLaunchService launchService;
    private final TelegramBotService botService;
    private final BotRuntimeStateService runtimeState;
    private final Clock clock;
    private final Cache<Long, Attempt> attempts = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterAccess(Duration.ofDays(1)).build();
    private final Set<Long> trackedChats = new HashSet<>();
    private boolean initialized;
    private boolean registryLoaded;
    private boolean defaultsConfigured;
    private Instant retryDefaultsAt = Instant.MIN;

    @Autowired
    public TelegramAdminMenuService(AdminProperties properties, TelegramAdminLaunchService launchService,
                                    TelegramBotService botService, BotRuntimeStateService runtimeState) {
        this(properties, launchService, botService, runtimeState, Clock.systemUTC());
    }

    TelegramAdminMenuService(AdminProperties properties, TelegramAdminLaunchService launchService,
                             TelegramBotService botService, BotRuntimeStateService runtimeState, Clock clock) {
        this.properties = properties;
        this.launchService = launchService;
        this.botService = botService;
        this.runtimeState = runtimeState;
        this.clock = clock;
    }

    /** Called after the polling offset is restored. Does not send messages to users. */
    public synchronized void initialize() {
        initialized = true;
        reconcileConfiguredMenus();
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void reconcileConfiguredMenus() {
        if (!initialized) return;
        configureDefaults();
        if (!loadRegistry()) return;
        Set<Long> chats = new TreeSet<>(trackedChats);
        if (properties.getTelegramUserIds() != null) {
            properties.getTelegramUserIds().stream().filter(id -> id != null && id > 0).forEach(chats::add);
        }
        for (long chatId : chats) synchronizePrivateMenu(chatId);
    }

    /** Reconcile on interaction too, including people who were removed from the allowlist. */
    public synchronized void onInteraction(String chatType, long chatId, long senderId) {
        if (!isPrivateSender(chatType, chatId, senderId)) return;
        if (loadRegistry()) synchronizePrivateMenu(chatId);
    }

    public Map<String, Object> replyMarkup(String chatType, long chatId, long senderId) {
        if (!isPrivateSender(chatType, chatId, senderId)) return null;
        return launchService.prepare(chatType, senderId).allowed() ? QUICK_COMMANDS : REMOVE_KEYBOARD;
    }

    /** Exact button labels only; ordinary student questions must keep their original text. */
    static String commandForButton(String text) {
        if (text == null) return null;
        return switch (text) {
            case "📋 Частые вопросы" -> "/faq";
            case "ℹ️ Помощь" -> "/help";
            case "🤖 О боте" -> "/about";
            case "🆔 Мой ID" -> "/myid";
            default -> text;
        };
    }

    private void synchronizePrivateMenu(long chatId) {
        var launch = launchService.prepare("private", chatId);
        String desired = launch.allowed() ? launch.webAppUrl() : COMMANDS;
        Attempt previous = attempts.getIfPresent(chatId);
        Instant now = clock.instant();
        if (previous != null && previous.desired().equals(desired)
                && (previous.success() || now.isBefore(previous.retryAt()))) return;

        try {
            if (launch.allowed()) {
                // Persist before the external change so revocation can be recovered after a restart/crash.
                if (!trackedChats.contains(chatId)) {
                    Set<Long> updated = new HashSet<>(trackedChats);
                    updated.add(chatId);
                    saveRegistry(updated);
                }
                botService.setAdminMenu(chatId, launch.webAppUrl());
            } else {
                botService.setCommandMenu(chatId);
                if (trackedChats.contains(chatId)) {
                    Set<Long> updated = new HashSet<>(trackedChats);
                    updated.remove(chatId);
                    saveRegistry(updated);
                }
            }
            attempts.put(chatId, new Attempt(desired, true, Instant.MAX));
        } catch (RuntimeException exception) {
            attempts.put(chatId, new Attempt(desired, false, now.plus(RETRY_DELAY)));
            log.warn("Could not update Telegram menu for chatId={}; will retry ({})",
                    chatId, exception.getClass().getSimpleName());
        }
    }

    private void configureDefaults() {
        if (defaultsConfigured || clock.instant().isBefore(retryDefaultsAt)) return;
        try {
            botService.setCommandMenu(null);
            botService.setDefaultCommands();
            defaultsConfigured = true;
        } catch (RuntimeException exception) {
            retryDefaultsAt = clock.instant().plus(RETRY_DELAY);
            log.warn("Could not configure Telegram command menu; will retry ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private boolean loadRegistry() {
        if (registryLoaded) return true;
        try {
            String value = runtimeState.getValue(TRACKED_CHATS_KEY).orElse("");
            Set<Long> loaded = new HashSet<>();
            for (String id : value.split(",")) {
                if (id.isBlank()) continue;
                long parsed = Long.parseLong(id.strip());
                if (parsed > 0) loaded.add(parsed);
            }
            trackedChats.addAll(loaded);
            registryLoaded = true;
            return true;
        } catch (RuntimeException exception) {
            log.warn("Could not load Telegram menu registry; configuration will retry ({})",
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private void saveRegistry(Set<Long> updated) {
        runtimeState.setValue(TRACKED_CHATS_KEY, updated.stream().sorted().map(String::valueOf)
                .collect(Collectors.joining(",")));
        trackedChats.clear();
        trackedChats.addAll(updated);
    }

    private static boolean isPrivateSender(String chatType, long chatId, long senderId) {
        return "private".equals(chatType) && senderId > 0 && senderId == chatId;
    }

    private record Attempt(String desired, boolean success, Instant retryAt) { }
}
