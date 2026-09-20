package com.careerai.backend.telegram;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelegramCommandRoutingTest {
    @Test
    void commandNamesMatchExactlyWithOptionalBotMentionAndArguments() {
        assertTrue(TelegramPollingService.isCommand("/start", "start"));
        assertTrue(TelegramPollingService.isCommand("/start@CareerAI payload", "start"));
        assertTrue(TelegramPollingService.isCommand("/myid", "myid"));
        assertFalse(TelegramPollingService.isCommand("/startled", "start"));
        assertFalse(TelegramPollingService.isCommand("/faqanything", "faq"));
        assertFalse(TelegramPollingService.isCommand("Tell me /faq", "faq"));
    }
}
