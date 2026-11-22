package com.securechat.keys;

import com.securechat.support.web.ApiException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Limits bundle claims per requesting device (prekey-exhaustion protection), per instance. */
@Component
class ClaimRateLimiter {
    private record Window(long minute, AtomicInteger count) {
    }

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final KeyProperties props;

    ClaimRateLimiter(KeyProperties props) {
        this.props = props;
    }

    void check(UUID requesterDevice) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(requesterDevice, (k, v) -> v == null || v.minute() != minute ? new Window(minute, new AtomicInteger()) : v);
        if (w.count().incrementAndGet() > props.claimsPerMinute()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "too many key bundle claims");
        }
    }
}
