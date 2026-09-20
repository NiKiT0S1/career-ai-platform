package com.careerai.backend.telegram;

import com.careerai.backend.admin.AdminProperties;
import com.careerai.backend.ai.LlmProvider;
import com.careerai.backend.channel.TelegramChannelPostAnswerService;
import com.careerai.backend.channel.TelegramChannelPostService;
import com.careerai.backend.faq.FaqEntryService;
import com.careerai.backend.message.ChatMessageService;
import com.careerai.backend.runtime.BotRuntimeStateService;
import com.careerai.backend.user.TelegramUser;
import com.careerai.backend.user.TelegramUserService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramQuickCommandPollingTest {
    @Test
    void quickButtonsAndAdminFallbackAreHandledLocallyWithUnchangedAuthorization() {
        var properties = new AdminProperties();
        properties.setEnabled(true);
        properties.setTelegramUserIds(Set.of(123L));
        properties.setPublicUrl("https://career.example.org/admin/");
        var bot = mock(TelegramBotService.class);
        var mapper = new ObjectMapper();
        var llm = mock(LlmProvider.class);
        var typing = mock(TelegramTypingService.class);
        var users = mock(TelegramUserService.class);
        var messages = mock(ChatMessageService.class);
        var runtime = mock(BotRuntimeStateService.class);
        var sanitizer = mock(TelegramHtmlSanitizer.class);
        var posts = mock(TelegramChannelPostService.class);
        var answers = mock(TelegramChannelPostAnswerService.class);
        var faq = mock(FaqEntryService.class);
        var launch = new TelegramAdminLaunchService(properties);
        var menus = new TelegramAdminMenuService(properties, launch, bot, runtime);
        var polling = new TelegramPollingService(bot, mapper, llm, typing, users, messages, runtime,
                sanitizer, posts, answers, faq, launch, menus);
        var user = new TelegramUser();
        when(users.findOrCreateFromMessage(any())).thenReturn(user);
        when(sanitizer.sanitizeHtml(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(faq.buildFaqListMessage()).thenReturn("FAQ без генерации");
        var updates = new ArrayList<Map<String, Object>>();
        String[] labels = {"/start", "📋 Частые вопросы", "ℹ️ Помощь", "🤖 О боте", "🆔 Мой ID", "/admin"};
        for (int i = 0; i < labels.length; i++) updates.add(update(i, 123L, labels[i]));
        updates.add(update(6, 456L, "/admin"));
        when(bot.getUpdates(0)).thenReturn(mapper.writeValueAsString(Map.of("ok", true, "result", updates)));

        polling.initializeOffset();
        polling.pollUpdates();

        var keyboard = menus.replyMarkup("private", 123L, 123L);
        verify(bot).sendHtmlMessage(123L, TelegramMessageTemplates.startMessage(), keyboard);
        verify(bot).sendHtmlMessage(123L, "FAQ без генерации", keyboard);
        verify(bot).sendHtmlMessage(123L, TelegramMessageTemplates.helpMessage(), keyboard);
        verify(bot).sendHtmlMessage(123L, TelegramMessageTemplates.aboutMessage(), keyboard);
        verify(bot).sendMessage(123L, "Твой Telegram ID: 123", keyboard);
        verify(bot).sendWebAppMessage(123L, "Открыть панель управления CareerAI:", properties.getPublicUrl());
        verify(bot).sendMessage(456L, "Панель администратора недоступна для этого аккаунта.",
                Map.of("remove_keyboard", true));
        verify(bot, never()).sendWebAppMessage(eq(456L), anyString(), anyString());
        verify(messages).saveUserMessage(user, 123L, "📋 Частые вопросы", 1L);
        verify(runtime).setValue("telegram_update_offset", "7");
        verifyNoInteractions(llm, answers, typing, posts);
    }

    @Test
    void exactButtonAliasesDoNotRewriteAnOrdinaryQuestion() {
        assertEquals("/faq", TelegramAdminMenuService.commandForButton("📋 Частые вопросы"));
        assertEquals("📋 Частые вопросы про практику", TelegramAdminMenuService.commandForButton("📋 Частые вопросы про практику"));
        assertEquals("Помоги с практикой", TelegramAdminMenuService.commandForButton("Помоги с практикой"));
        assertEquals("/admin", TelegramAdminMenuService.commandForButton("/admin"));
    }

    private Map<String, Object> update(long id, long userId, String text) {
        return Map.of("update_id", id, "message", Map.of("message_id", id, "text", text,
                "chat", Map.of("id", userId, "type", "private"),
                "from", Map.of("id", userId, "is_bot", false)));
    }
}
