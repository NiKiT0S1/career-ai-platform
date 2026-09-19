package com.careerai.backend.answer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Keys include the application day and timezone even for apparently timeless queries.
 * Failed loaders return null, which Caffeine does not cache. Concurrent loads are coalesced. */
public final class BoundedQueryCache<T> {
    private final Cache<String, T> cache;
    private final Clock clock;

    public BoundedQueryCache(int size, long ttlSeconds, Clock clock) {
        this(size, ttlSeconds, clock, Ticker.systemTicker());
    }

    BoundedQueryCache(int size, long ttlSeconds, Clock clock, Ticker ticker) {
        this.clock = clock;
        cache = Caffeine.newBuilder().maximumSize(size)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds)).ticker(ticker).recordStats().build();
    }

    public T get(String namespace, String text, Supplier<T> loader) {
        if (text == null || text.length() > 8000) {
            return loader.get();
        }
        String key = namespace + '|' + clock.getZone().getId() + '|' + LocalDate.now(clock)
                + '|' + sha256(text.strip());
        return cache.get(key, ignored -> loader.get());
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public long hitCount() {
        return cache.stats().hitCount();
    }

    public long estimatedSize() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
