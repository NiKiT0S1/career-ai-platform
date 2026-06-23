package com.careerai.backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class GeminiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(GeminiKeyPool.class);

    private final List<String> apiKeys;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Instant> blockedUntilByKey = new ConcurrentHashMap<>();

    public GeminiKeyPool(GeminiProperties properties) {
        this.apiKeys = Arrays.stream(properties.getApiKeys().split(","))
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .toList();

        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("No Gemini API keys configured. Check GEMINI_API_KEYS environment variable.");
        }

        log.info("GeminiKeyPool initialized with {} keys", apiKeys.size());
    }

    public Optional<String> getAvailableKey() {
        Instant now = Instant.now();

        for (int attempt = 0; attempt < apiKeys.size(); attempt++) {
            int index = Math.floorMod(currentIndex.getAndIncrement(), apiKeys.size());
            String key = apiKeys.get(index);

            Instant blockedUntil = blockedUntilByKey.get(key);

            if (blockedUntil == null || blockedUntil.isBefore(now)) {
                return Optional.of(key);
            }
        }

        return Optional.empty();
    }

    public void blockKey(String apiKey, Duration duration, String reason) {
        Instant blockedUntil = Instant.now().plus(duration);
        blockedUntilByKey.put(apiKey, blockedUntil);

        log.warn(
                "Gemini API key {} was temporaily blocked for {} seconds. Reason: {}",
                maskKey(apiKey),
                duration.toSeconds(),
                reason
        );
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "****";
        }

        String trimmedKey = apiKey.trim();

        if (trimmedKey.length() <= 8) {
            return "****";
        }

        String prefix = trimmedKey.substring(0, 4);
        String suffix = trimmedKey.substring(trimmedKey.length() - 4);

        return prefix + "..." +  suffix;
    }
}
