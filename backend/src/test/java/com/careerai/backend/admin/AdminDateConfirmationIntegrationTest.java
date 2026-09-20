package com.careerai.backend.admin;

import com.careerai.backend.channel.TelegramChannelPostFreshnessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: only the disposable database from TEST_DB_URL, with all external services disabled. */
@EnabledIfEnvironmentVariable(named="CAREERAI_INTEGRATION_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=${TEST_DB_URL}","spring.datasource.username=${TEST_DB_USERNAME:postgres}",
        "spring.datasource.password=${TEST_DB_PASSWORD}","telegram.bot.token=integration-token",
        "gemini.api.keys=integration-key","groq.api.key=integration-key",
        "telegram.bot.polling-enabled=false","careerai.background.enabled=false","semantic-search.enabled=false",
        "careerai.admin.enabled=true","careerai.admin.telegram-user-ids=424242",
        "channel-post-freshness.recalculate-on-startup=false"
})
class AdminDateConfirmationIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TelegramChannelPostFreshnessService freshness;
    private final HttpClient http=HttpClient.newHttpClient();

    @Test
    void signedAdminCanConfirmRevokeAndReconfirmEditedSourceWithoutLosingAudit() throws Exception {
        long id=createPost();
        try {
            freshness.recalculateOne(id);
            JsonNode original=get(id);long initialRevision=original.get("revision").asLong();
            assertEquals("UNKNOWN",original.get("freshness_status").asText());
            String input=confirmation(initialRevision,"INCLUSIVE");
            assertEquals(401,send("PUT",route(id),input,null).statusCode());
            assertEquals(401,send("PUT",route(id),input,999999L).statusCode());
            assertEquals(0L,auditCount(id,"date-confirm"));

            var confirmed=send("PUT",route(id),input,424242L);
            assertEquals(200,confirmed.statusCode(),confirmed.body());
            JsonNode current=json.readTree(confirmed.body());
            assertTrue(current.get("date_confirmation_current").asBoolean());
            assertEquals("ACTIVE",current.get("freshness_status").asText());
            assertEquals(424242,current.get("date_confirmed_by").asLong());
            assertEquals("10 августа состоится мастер-класс",current.get("text").asText());
            assertFalse(current.get("expires_at").isNull());
            assertEquals(1L,auditCount(id,"date-confirm"));
            assertEquals(409,send("PUT",route(id),input,424242L).statusCode());

            // A Telegram edit changes source provenance even if its visible text is unchanged.
            jdbc.update("UPDATE telegram_channel_posts SET edited_at=CURRENT_TIMESTAMP,revision=revision+1 WHERE id=?",id);
            freshness.recalculateOne(id);
            JsonNode edited=get(id);
            assertFalse(edited.get("date_confirmation_current").asBoolean());
            assertEquals("UNKNOWN",edited.get("freshness_status").asText());
            assertTrue(edited.get("expires_at").isNull());
            assertEquals(409,send("POST",route(id)+"/revoke",json.writeValueAsString(Map.of(
                    "revision",current.get("revision").asLong(),"reason","Stale page")),424242L).statusCode());

            var reconfirmed=send("PUT",route(id),confirmation(edited.get("revision").asLong(),"INCLUSIVE"),424242L);
            assertEquals(200,reconfirmed.statusCode(),reconfirmed.body());
            JsonNode newest=json.readTree(reconfirmed.body());
            assertTrue(newest.get("date_confirmation_current").asBoolean());
            var revoked=send("POST",route(id)+"/revoke",json.writeValueAsString(Map.of(
                    "revision",newest.get("revision").asLong(),"reason","Автор уточняет год")),424242L);
            assertEquals(200,revoked.statusCode(),revoked.body());
            JsonNode after=json.readTree(revoked.body());
            assertTrue(after.get("confirmed_date").isNull());assertFalse(after.get("date_confirmation_current").asBoolean());
            assertEquals("UNKNOWN",after.get("freshness_status").asText());assertTrue(after.get("expires_at").isNull());
            assertEquals(2L,auditCount(id,"date-confirm"));assertEquals(1L,auditCount(id,"date-revoke"));
        } finally { cleanup(id); }
    }

    @Test
    void invalidDateBoundaryAndReasonAreRejectedWithoutPersistingAnOverride() throws Exception {
        long id=createPost();
        try {
            assertEquals(400,send("PUT",route(id),confirmation(0,"UNSPECIFIED"),424242L).statusCode());
            assertEquals(400,send("PUT",route(id),confirmation(0,"EXCLUSIVE"),424242L).statusCode());
            assertEquals(400,send("PUT",route(id),confirmation(0,"INCLUSIVE").replace("Уточнено у менеджера",""),424242L).statusCode());
            assertEquals(400,send("PUT",route(id),"{\"revision\":0,\"date\":\"2027-99-01\",\"boundary\":\"INCLUSIVE\",\"purpose\":\"EVENT_DATE\",\"reason\":\"Checked\"}",424242L).statusCode());
            assertNull(jdbc.queryForObject("SELECT confirmed_date FROM telegram_channel_posts WHERE id=?",LocalDate.class,id));
            assertEquals(0L,auditCount(id,"date-confirm"));
        } finally { cleanup(id); }
    }

    private long createPost() {
        long id=jdbc.queryForObject("INSERT INTO telegram_channel_posts(telegram_chat_id,telegram_message_id,text,posted_at) VALUES (?,1,'10 августа состоится мастер-класс','2026-07-10T10:00:00Z') RETURNING id",
                Long.class,-Math.abs(UUID.randomUUID().getMostSignificantBits()));
        jdbc.update("INSERT INTO telegram_channel_post_metadata(post_id,post_type,extraction_status) VALUES (?,'EVENT','SUCCESS')",id);
        return id;
    }
    private void cleanup(long id) {
        jdbc.update("DELETE FROM admin_audit_events WHERE entity_type='post' AND entity_id=?",id);
        jdbc.update("DELETE FROM telegram_channel_posts WHERE id=?",id);
    }
    private long auditCount(long id,String action) {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE entity_type='post' AND entity_id=? AND action=? AND actor_telegram_id=424242",Long.class,id,action);
    }
    private JsonNode get(long id) throws Exception {
        var response=send("GET","posts/"+id,null,424242L);assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());
    }
    private String confirmation(long revision,String boundary) {
        return json.writeValueAsString(Map.of("revision",revision,"date",LocalDate.now(ZoneId.of("Asia/Almaty")).plusYears(1).withMonth(8).withDayOfMonth(10).toString(),
                "boundary",boundary,"purpose","EVENT_DATE","reason","Уточнено у менеджера"));
    }
    private String route(long id) { return "posts/"+id+"/date-confirmation"; }
    private HttpResponse<String> send(String method,String path,String body,Long userId) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/admin/"+path));
        if(userId!=null)request.header("Authorization","tma "+sign(userId));
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String sign(long userId) throws Exception {
        var values=new TreeMap<String,String>();values.put("auth_date",Long.toString(Instant.now().getEpochSecond()));
        values.put("user",json.writeValueAsString(Map.of("id",userId,"first_name","Integration")));
        String checked=values.entrySet().stream().map(entry->entry.getKey()+"="+entry.getValue()).collect(Collectors.joining("\n"));
        Mac secret=Mac.getInstance("HmacSHA256");secret.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        byte[] key=secret.doFinal("integration-token".getBytes(StandardCharsets.UTF_8));
        Mac signature=Mac.getInstance("HmacSHA256");signature.init(new SecretKeySpec(key,"HmacSHA256"));
        values.put("hash",HexFormat.of().formatHex(signature.doFinal(checked.getBytes(StandardCharsets.UTF_8))));
        return values.entrySet().stream().map(entry->URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(entry.getValue(),StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
}
