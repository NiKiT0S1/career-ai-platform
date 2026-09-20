package com.careerai.backend.admin;

import com.careerai.backend.telegram.TelegramBotProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Validates Telegram initData on the server; browser-supplied user objects are never trusted. */
@Service
public class AdminAuthenticationService {
    private final AdminProperties properties;
    private final TelegramBotProperties bot;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AdminAuthenticationService(AdminProperties properties, TelegramBotProperties bot,
                                      ObjectMapper mapper, Clock clock) {
        this.properties = properties; this.bot = bot; this.mapper = mapper; this.clock = clock;
    }

    public AdminIdentity authenticate(String initData) {
        try {
            if (!properties.isEnabled() || initData == null || initData.isBlank() || initData.length() > 16384)
                throw denied();
            TreeMap<String, String> fields = new TreeMap<>();
            for (String item : initData.split("&", -1)) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2) throw denied();
                String key = decode(pair[0]);
                String value = decode(pair[1]);
                if (!key.matches("[a-z_]+") || fields.putIfAbsent(key, value) != null) throw denied();
            }
            String hash = fields.remove("hash");
            if (hash == null || !hash.matches("[0-9a-fA-F]{64}") || bot.getToken() == null || bot.getToken().isBlank())
                throw denied();
            String check = fields.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("\n"));
            byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), bot.getToken());
            if (!MessageDigest.isEqual(hmac(secret, check), HexFormat.of().parseHex(hash))) throw denied();
            long authDate = Long.parseLong(fields.getOrDefault("auth_date", "0"));
            long now = clock.instant().getEpochSecond();
            long maxAge = Math.max(60, Math.min(86400, properties.getAuthMaxAgeSeconds()));
            if (authDate <= 0 || authDate > now + 30 || authDate < now - maxAge) throw denied();
            var user = mapper.readTree(fields.getOrDefault("user", "{}"));
            var id = user.path("id");
            if (!id.isIntegralNumber() || !id.canConvertToLong()) throw denied();
            long userId = id.asLong();
            if (userId <= 0 || !properties.getTelegramUserIds().contains(userId)) throw denied();
            return new AdminIdentity(userId, user.path("first_name").asText("Администратор"));
        } catch (Exception e) {
            // Neither signed initData nor the bot token belongs in exception logs.
            throw denied();
        }
    }

    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private static SecurityException denied() { return new SecurityException("Admin authentication required"); }
    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
