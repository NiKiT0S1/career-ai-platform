package com.careerai.backend.channel;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Рассчитывает хеш данных,
 * использованных классификатором.
 */

@Service
public class TelegramRelationClassificationInputHashService {

    public String calculate(TelegramReplyRelationClassificationContext context, String classifierVersion) {
        String input = String.join(
                "\n",
                safe(context.sourcePostId()),
                safe(context.sourceText()),
                safe(context.sourceDate()),
                safe(context.targetPostId()),
                safe(context.targetText()),
                safe(context.targetDate()),
                safe(classifierVersion)
        );

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private String safe(Object value) {
        return value == null
                ? ""
                : value.toString();
    }
}