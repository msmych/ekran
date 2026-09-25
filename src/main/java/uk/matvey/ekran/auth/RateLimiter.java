package uk.matvey.ekran.auth;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fixed-window rate limiter (per key). Single-instance deployment: in-memory is enough. */
public final class RateLimiter {

    private record Window(int count, long resetAtMs) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final long windowMs;

    public RateLimiter(int limit, Duration window) {
        this.limit = limit;
        this.windowMs = window.toMillis();
    }

    public boolean allow(String key) {
        var now = System.currentTimeMillis();
        var window = windows.compute(key, (k, current) ->
            current == null || now >= current.resetAtMs()
                ? new Window(1, now + windowMs)
                : new Window(current.count() + 1, current.resetAtMs()));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now >= e.getValue().resetAtMs());
        }
        return window.count() <= limit;
    }
}