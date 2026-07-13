package com.careerai.backend.semantic;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Рассчитывает SHA-256 хэш текста, из которого создаётся embedding.
 *
 * Хэш позволяет не отправлять текст в Gemini повторно,
 * если содержимое исходной записи не изменилось.
 */

@Service
public class SemanticContentHashService {

    public String calculateHash(String content) {
        if (content == null) {
            throw new IllegalArgumentException("Content for hash calculation cannot be null");
        }

        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");

            byte[] hashBytes = messageDigest.digest(
                    content.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hashBytes);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    e
            );
        }
    }
}
