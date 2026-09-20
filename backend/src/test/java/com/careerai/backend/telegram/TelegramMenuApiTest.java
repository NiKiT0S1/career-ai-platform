package com.careerai.backend.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TelegramMenuApiTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private TelegramBotService bot;

    @BeforeEach
    void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        TelegramBotProperties properties = mock(TelegramBotProperties.class);
        when(properties.getToken()).thenReturn("local-test-token");
        bot = new TelegramBotService(properties, builder.build());
    }

    @Test
    void usesPerChatWebAppMenuWithNativeTelegramAuthLaunch() {
        server.expect(requestTo("https://api.telegram.org/botlocal-test-token/setChatMenuButton"))
                .andExpect(request -> {
                    JsonNode body = body(request);
                    assertEquals(123L, body.path("chat_id").asLong());
                    assertEquals("web_app", body.path("menu_button").path("type").asText());
                    assertEquals("Админ-панель", body.path("menu_button").path("text").asText());
                    assertEquals("https://career.example.org/admin/", body.path("menu_button").path("web_app").path("url").asText());
                }).andRespond(withSuccess("{\"ok\":true,\"result\":true}", MediaType.APPLICATION_JSON));
        bot.setAdminMenu(123L, "https://career.example.org/admin/");
        server.verify();
    }

    @Test
    void defaultAndRevokedUserMenusUseCommandsAndDoNotExposeAnAdminUrl() {
        for (Long chatId : new Long[]{null, 123L}) {
            server.expect(requestTo("https://api.telegram.org/botlocal-test-token/setChatMenuButton"))
                    .andExpect(request -> {
                        JsonNode body = body(request);
                        assertEquals(chatId != null, body.has("chat_id"));
                        assertEquals("commands", body.path("menu_button").path("type").asText());
                        assertFalse(body.toString().contains("web_app"));
                    }).andRespond(withSuccess("{\"ok\":true,\"result\":true}", MediaType.APPLICATION_JSON));
        }
        bot.setCommandMenu(null);
        bot.setCommandMenu(123L);
        server.verify();
    }

    @Test
    void apiRejectionIsNotMistakenForSuccessfulMenuInstallation() {
        server.expect(requestTo("https://api.telegram.org/botlocal-test-token/setChatMenuButton"))
                .andRespond(withSuccess("{\"ok\":false,\"description\":\"secret must not be logged\"}", MediaType.APPLICATION_JSON));
        var exception = assertThrows(IllegalStateException.class, () -> bot.setCommandMenu(123L));
        assertFalse(exception.getMessage().contains("secret"));
        server.verify();
    }

    @Test
    void rejectsInsecureWebAppUrlBeforeAnyApiCall() {
        assertThrows(IllegalArgumentException.class, () -> bot.setAdminMenu(123L, "http://localhost/admin/"));
        server.verify();
    }

    @Test
    void htmlFallbackPreservesKeyboardRemovalForRevokedAdmin() {
        String endpoint = "https://api.telegram.org/botlocal-test-token/sendMessage";
        server.expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        server.expect(requestTo(endpoint)).andExpect(request -> {
            JsonNode body = body(request);
            assertEquals("Помощь", body.path("text").asText());
            assertTrue(body.path("reply_markup").path("remove_keyboard").asBoolean());
            assertFalse(body.has("parse_mode"));
        }).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        bot.sendHtmlMessage(123L, "<b>Помощь</b>", Map.of("remove_keyboard", true));
        server.verify();
    }

    @Test
    void adminCommandStillUsesInlineWebAppButton() {
        server.expect(requestTo("https://api.telegram.org/botlocal-test-token/sendMessage"))
                .andExpect(request -> {
                    JsonNode body = body(request);
                    assertEquals("https://career.example.org/admin/", body.path("reply_markup")
                            .path("inline_keyboard").get(0).get(0).path("web_app").path("url").asText());
                    assertFalse(body.path("reply_markup").has("keyboard"));
                }).andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        bot.sendWebAppMessage(123L, "Открыть панель", "https://career.example.org/admin/");
        server.verify();
    }

    private JsonNode body(org.springframework.http.client.ClientHttpRequest request) {
        return mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
    }
}
