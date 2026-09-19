package com.careerai.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.careerai.backend.semantic.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

/** Opt-in only: TEST_DB_URL must point to a disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named="CAREERAI_INTEGRATION_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=${TEST_DB_URL}","spring.datasource.username=${TEST_DB_USERNAME:postgres}",
    "spring.datasource.password=${TEST_DB_PASSWORD}","telegram.bot.token=integration-token",
    "gemini.api.keys=integration-key","groq.api.key=integration-key",
    "telegram.bot.polling-enabled=false","careerai.background.enabled=false","semantic-search.enabled=false",
    "careerai.admin.enabled=true","careerai.admin.telegram-user-ids=424242",
    "channel-post-freshness.recalculate-on-startup=false"
})
class BackendApplicationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired SemanticLifecycleRepository sources;
    @Autowired SemanticEmbeddingRepository embeddings;
    final HttpClient http=HttpClient.newHttpClient();

    @Test void migrationAndEveryAdminListRunAgainstPostgres() throws Exception {
        assertEquals("15",jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",String.class));
        for(String path:List.of("me","overview","posts","faqs","relations","candidates","audit","jobs")) {
            var response=send("GET",path,null,true);
            assertEquals(200,response.statusCode(),path+": "+response.body());
            json.readTree(response.body());
        }
    }
    @Test void requiresSignedAllowlistedTelegramIdentity() throws Exception {
        assertEquals(401,send("GET","overview",null,false).statusCode());
        assertEquals(401,send("POST","faqs","{}",false).statusCode());
        var forged=HttpRequest.newBuilder(URI.create(base()+"me")).header("Authorization","tma user=%7B%22id%22%3A424242%7D&hash=invalid").GET().build();
        assertEquals(401,http.send(forged,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void faqCrudAuditsActorAndRejectsStaleRevision() throws Exception {
        String slug="integration-"+UUID.randomUUID();
        String create=json.writeValueAsString(Map.of("category","practice","slug",slug,"question","Как найти практику?",
            "shortAnswer","Обратитесь в ЦКиТ","fullAnswer","Проверенный тестовый ответ","keywords","практика","priority",100,"active",true));
        var response=send("POST","faqs",create,true);
        assertEquals(201,response.statusCode(),response.body());
        long id=json.readTree(response.body()).get("id").asLong();
        try {
            var update=new LinkedHashMap<String,Object>();
            update.put("revision",0);update.put("category","practice");update.put("slug",slug);
            update.put("question","Уточнённый вопрос");update.put("shortAnswer","Короткий ответ");update.put("fullAnswer","Новый полный ответ");
            update.put("keywords","");update.put("priority",10);update.put("active",false);
            String body=json.writeValueAsString(update);
            assertEquals(200,send("PUT","faqs/"+id,body,true).statusCode());
            assertEquals(409,send("PUT","faqs/"+id,body,true).statusCode());
            assertEquals(false,jdbc.queryForObject("SELECT is_active FROM faq_entries WHERE id=?",Boolean.class,id));
            assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM admin_audit_events WHERE entity_type='faq' AND entity_id=? AND actor_telegram_id=424242",Long.class,id));
        } finally {jdbc.update("DELETE FROM faq_entries WHERE id=?",id);}
    }
    @Test void archiveAndRestorePersistRevisionAndMetadata() throws Exception {
        long message=Math.abs(UUID.randomUUID().getMostSignificantBits());
        long id=jdbc.queryForObject("INSERT INTO telegram_channel_posts (telegram_chat_id,telegram_message_id,text,posted_at) VALUES (-424242,?,'Integration event',CURRENT_TIMESTAMP) RETURNING id",Long.class,message);
        jdbc.update("INSERT INTO telegram_channel_post_metadata(post_id,post_type,title,summary,extraction_status) VALUES (?,'OTHER','Test','Test summary','SUCCESS')",id);
        try {
            assertEquals(200,send("GET","posts/"+id,null,true).statusCode());
            var archived=send("POST","posts/"+id+"/archive","{\"revision\":0,\"reason\":\"Integration check\"}",true);
            assertEquals(200,archived.statusCode(),archived.body());
            JsonNode data=json.readTree(archived.body());assertTrue(data.get("is_archived").asBoolean());
            assertEquals(409,send("POST","posts/"+id+"/restore","{\"revision\":0}",true).statusCode());
            assertEquals(200,send("POST","posts/"+id+"/restore","{\"revision\":"+data.get("revision").asLong()+"}",true).statusCode());
            assertFalse(jdbc.queryForObject("SELECT is_archived FROM telegram_channel_posts WHERE id=?",Boolean.class,id));
        } finally {jdbc.update("DELETE FROM telegram_channel_posts WHERE id=?",id);}
    }
    @Test void validatesFiltersAndLoadsPublicShell() throws Exception {
        assertEquals(400,send("GET","posts?from=2026-99-99",null,true).statusCode());
        assertEquals(400,send("GET","posts?from=2026-09-20&to=2026-09-01",null,true).statusCode());
        assertEquals(200,send("GET","posts?from=2026-09-01&to=2026-09-30",null,true).statusCode());
        assertEquals(400,send("GET","posts?page=-1",null,true).statusCode());
        var response=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/admin/index.html")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());assertTrue(response.body().contains("CareerAI"));
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("object-src 'none'"));
    }
    @Test void embeddingLifecyclePersistsEditsRetriesAndDeactivationInPostgres() {
        long id=jdbc.queryForObject("INSERT INTO faq_entries(category,slug,question,short_answer,full_answer) VALUES ('integration',?,'Question','Short','Original') RETURNING id",Long.class,"lifecycle-"+UUID.randomUUID());
        var settings=new SemanticSearchProperties();
        ReflectionTestUtils.setField(settings,"enabled",true);
        ReflectionTestUtils.setField(settings,"embeddingModel","integration-model");
        ReflectionTestUtils.setField(settings,"outputDimensions",2);
        var provider=mock(EmbeddingProvider.class);
        when(provider.embedDocument(anyString(),anyString())).thenReturn(EmbeddingResult.success(new double[]{1,2},"integration-model",1));
        var lifecycle=new SemanticEmbeddingLifecycleService(settings,new EmbeddingLifecycleProperties(),sources,embeddings,
                new SemanticContentHashService(),provider,mock(org.springframework.context.ApplicationEventPublisher.class),java.time.Clock.systemUTC());
        try {
            assertEquals(SemanticIndexingOutcome.INDEXED,lifecycle.indexFaqEntry(id,false));
            assertEquals(SemanticIndexingOutcome.UNCHANGED,lifecycle.indexFaqEntry(id,false));
            verify(provider,times(1)).embedDocument(anyString(),anyString());
            jdbc.update("UPDATE faq_entries SET full_answer='Edited' WHERE id=?",id);
            when(provider.embedDocument(anyString(),anyString())).thenReturn(EmbeddingResult.failure("integration-model",1,"simulated outage"));
            assertEquals(SemanticIndexingOutcome.FAILED,lifecycle.indexFaqEntry(id,false));
            assertTrue(sources.findRetry(SemanticSourceType.FAQ,id).isPresent());
            assertEquals(SemanticIndexingOutcome.BACKOFF,lifecycle.indexFaqEntry(id,false));
            when(provider.embedDocument(anyString(),anyString())).thenReturn(EmbeddingResult.success(new double[]{2,3},"integration-model",1));
            assertEquals(SemanticIndexingOutcome.INDEXED,lifecycle.indexFaqEntry(id,true));
            assertTrue(sources.findRetry(SemanticSourceType.FAQ,id).isEmpty());
            jdbc.update("UPDATE faq_entries SET is_active=false WHERE id=?",id);
            assertEquals(SemanticIndexingOutcome.DELETED,lifecycle.indexFaqEntry(id,false));
            assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM semantic_embeddings WHERE source_type='FAQ' AND source_id=?",Long.class,id));
            jdbc.update("UPDATE faq_entries SET is_active=true WHERE id=?",id);
            assertEquals(SemanticIndexingOutcome.INDEXED,lifecycle.indexFaqEntry(id,false));
        } finally {
            embeddings.delete(SemanticSourceType.FAQ,id);sources.clearRetry(SemanticSourceType.FAQ,id);
            jdbc.update("DELETE FROM faq_entries WHERE id=?",id);
        }
    }
    String base(){return "http://localhost:"+port+"/api/admin/";}
    HttpResponse<String> send(String method,String path,String body,boolean auth) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base()+path)).header("Content-Type","application/json");
        if(auth)request.header("Authorization","tma "+signedData());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    static String signedData() throws Exception {
        String date=Long.toString(Instant.now().getEpochSecond());String user="{\"id\":424242,\"first_name\":\"Integration\"}";
        byte[] secret=hmac("WebAppData".getBytes(StandardCharsets.UTF_8),"integration-token");
        String hash=HexFormat.of().formatHex(hmac(secret,"auth_date="+date+"\nuser="+user));
        return "auth_date="+date+"&user="+URLEncoder.encode(user,StandardCharsets.UTF_8)+"&hash="+hash;
    }
    static byte[] hmac(byte[] key,String value)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
}
