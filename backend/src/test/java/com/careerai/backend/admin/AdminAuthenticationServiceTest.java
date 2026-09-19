package com.careerai.backend.admin;

import com.careerai.backend.telegram.TelegramBotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthenticationServiceTest {
    private static final String TOKEN = "123456:TEST_ONLY_NOT_A_REAL_BOT_TOKEN";
    private static final long NOW = 1789812000L;
    // Independently generated with .NET HMACSHA256; no production signing helper is reused.
    private static final String FIXTURE = "auth_date=1789812000&query_id=AAE-test-query&user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22%D0%90%D0%B9%D0%B4%D0%B0%D0%BD%D0%B0%20%2B%20QA%22%7D&hash=a19898b3251870b4ced0961b9cbafdb0fb28d2a2e198fd163ca7a7d562b1ca88";
    private AdminProperties properties;
    private TelegramBotProperties bot;
    private AdminAuthenticationService service;

    @BeforeEach
    void setUp() {
        properties = new AdminProperties();
        properties.setEnabled(true);
        properties.setTelegramUserIds(Set.of(424242L));
        properties.setAuthMaxAgeSeconds(3600);
        bot = new TelegramBotProperties();
        ReflectionTestUtils.setField(bot, "token", TOKEN);
        service = new AdminAuthenticationService(properties, bot, new ObjectMapper(),
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    @Test
    void acceptsIndependentValidSignatureAndDecodesUnicodeAndPlusOnce() {
        assertEquals(new AdminIdentity(424242, "Айдана + QA"), service.authenticate(FIXTURE));
    }

    @Test
    void acceptsReorderedFieldsWithoutChangingSignedValues() {
        String[] parts = FIXTURE.split("&");
        assertEquals(424242, service.authenticate(parts[3] + "&" + parts[2] + "&" + parts[0] + "&" + parts[1]).userId());
    }

    @Test
    void rejectsTamperedUserEvenWhenNewIdIsWhitelisted() {
        properties.setTelegramUserIds(Set.of(424242L, 999999L));
        assertDenied(FIXTURE.replace("424242", "999999"));
    }

    @Test
    void rejectsTamperedDate() {
        assertDenied(FIXTURE.replace("1789812000", "1789811999"));
    }

    @Test
    void rejectsOldOrExcessivelyFutureSignedData() throws Exception {
        assertDenied(sign(fields(NOW - 3601, "{\"id\":424242}")));
        assertDenied(sign(fields(NOW + 31, "{\"id\":424242}")));
        assertDenied(sign(fields(0, "{\"id\":424242}")));
    }

    @Test
    void permitsDocumentedClockSkewAndLifetimeBoundary() throws Exception {
        assertEquals(424242, service.authenticate(sign(fields(NOW + 30, "{\"id\":424242}"))).userId());
        assertEquals(424242, service.authenticate(sign(fields(NOW - 3600, "{\"id\":424242}"))).userId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"&user=%7B%22id%22%3A424242%7D", "&u%73er=%7B%22id%22%3A424242%7D",
            "&auth_date=1789812000", "&auth%5fdate=1789812000", "&ha%73h="})
    void rejectsDuplicateKeysIncludingUrlEncodedNames(String duplicate) {
        assertDenied(FIXTURE + duplicate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "xyz", "00", "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffg"})
    void rejectsMissingOrMalformedHash(String hash) {
        assertDenied(FIXTURE.substring(0, FIXTURE.indexOf("&hash=")) + "&hash=" + hash);
    }

    @Test
    void requiresHashField() {
        assertDenied(FIXTURE.substring(0, FIXTURE.indexOf("&hash=")));
    }

    @Test
    void rejectsValidTelegramUserOutsideAllowlist() throws Exception {
        assertDenied(sign(fields(NOW, "{\"id\":999999,\"first_name\":\"Visitor\"}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"id\":\"424242\"}", "{\"id\":424242.0}",
            "{\"id\":-1}", "{\"id\":0}", "{\"id\":9223372036854775808}", "{broken"})
    void requiresPositiveIntegralSignedUserId(String user) throws Exception {
        assertDenied(sign(fields(NOW, user)));
    }

    @Test
    void doesNotTakeIdentityFromOtherFieldsEvenWhenTheyAreSigned() throws Exception {
        Map<String, String> values = fields(NOW, "{\"id\":999999}");
        values.put("user_id", "424242");
        values.put("id", "424242");
        assertDenied(sign(values));
    }

    @Test
    void rejectsUnsignedFrontendUserObject() {
        assertDenied("{\"id\":424242,\"first_name\":\"Administrator\"}");
        assertDenied("user=" + encode("{\"id\":424242}") + "&auth_date=" + NOW);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  \t\n"})
    void emptyInputIsDenied(String input) { assertDenied(input); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingBotTokenFailsClosed(String token) {
        ReflectionTestUtils.setField(bot, "token", token);
        assertDenied(FIXTURE);
    }

    @Test
    void disabledAdminFailsClosed() {
        properties.setEnabled(false);
        assertDenied(FIXTURE);
    }

    @Test
    void dataSignedForAnotherBotIsRejected() {
        ReflectionTestUtils.setField(bot, "token", "654321:ANOTHER_TEST_TOKEN");
        assertDenied(FIXTURE);
    }

    @Test
    void rejectsMalformedEncodingUnknownFieldsWithoutSignaturesAndOversizeInput() {
        assertDenied(FIXTURE + "&broken=%ZZ");
        assertDenied(FIXTURE + "&start_param=admin");
        assertDenied(FIXTURE + "&");
        assertDenied("x".repeat(16385));
    }

    private void assertDenied(String value) {
        SecurityException error = assertThrows(SecurityException.class, () -> service.authenticate(value));
        assertEquals("Admin authentication required", error.getMessage());
        assertNull(error.getCause(), "Parser details and tokens must not escape through exception causes");
    }

    private static Map<String, String> fields(long timestamp, String user) {
        return new TreeMap<>(Map.of("auth_date", Long.toString(timestamp), "user", user, "query_id", "test-query"));
    }

    private static String sign(Map<String, String> fields) throws Exception {
        String check = new TreeMap<>(fields).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("\n"));
        Mac derive = Mac.getInstance("HmacSHA256");
        derive.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Mac signing = Mac.getInstance("HmacSHA256");
        signing.init(new SecretKeySpec(derive.doFinal(TOKEN.getBytes(StandardCharsets.UTF_8)), "HmacSHA256"));
        String hash = HexFormat.of().formatHex(signing.doFinal(check.getBytes(StandardCharsets.UTF_8)));
        return fields.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&")) + "&hash=" + hash;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
