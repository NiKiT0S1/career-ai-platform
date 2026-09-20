package com.careerai.backend.answer;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class BoundedQueryCacheTest {
    private final MutableClock clock = new MutableClock();

    @Test
    void cacheExpiresAndDoesNotCacheFailures() {
        AtomicLong nanos = new AtomicLong();
        var cache = new BoundedQueryCache<String>(2, 60, clock, nanos::get);
        assertNull(cache.get("v1", "test", () -> null));
        assertEquals("ok", cache.get("v1", "test", () -> "ok"));
        assertEquals("ok", cache.get("v1", "test", () -> "unexpected"));
        nanos.set(Duration.ofSeconds(61).toNanos());
        assertEquals("new", cache.get("v1", "test", () -> "new"));
        for (int i = 0; i < 20; i++) cache.get("v1", "query" + i, () -> "value");
        assertTrue(cache.estimatedSize() <= 2);
    }

    @Test
    void newApplicationDayAndModelNamespaceForceReload() {
        var cache = new BoundedQueryCache<String>(10, 3600, clock);
        assertEquals("today", cache.get("v1", "tomorrow", () -> "today"));
        clock.instant = clock.instant.plus(Duration.ofDays(1));
        assertEquals("next day", cache.get("v1", "tomorrow", () -> "next day"));
        assertEquals("new model", cache.get("v2", "tomorrow", () -> "new model"));
        cache.invalidateAll();
        assertEquals("after invalidation", cache.get("v2", "tomorrow", () -> "after invalidation"));
    }

    @Test
    void simultaneousIdenticalQueriesShareOneLoad() throws Exception {
        var cache = new BoundedQueryCache<Integer>(10, 60, clock);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 10).mapToObj(i -> executor.submit(() -> {
                start.await();
                return cache.get("same", "query", calls::incrementAndGet);
            })).toList();
            start.countDown();
            for (Future<Integer> future : futures) assertEquals(1, future.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
    }

    static class MutableClock extends Clock {
        Instant instant = Instant.parse("2026-09-19T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Almaty"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
