package com.securechat.auth;

import com.securechat.support.web.ApiException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Rate-limiting hook for credential checks (sliding window per username and per client IP).
 * In-process: the API gateway applies a distributed Redis limiter in front of it; with several
 * auth instances this bounds attempts per instance. Replace with a shared store when scaling out.
 */
@Component
public class LoginRateLimiter {
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AuthProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    LoginRateLimiter(AuthProperties props) {
        this(props, Clock.systemUTC());
    }

    LoginRateLimiter(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public void check(String username, String ip) {
        if (!allow("u:" + username, props.loginAttemptsPerUser()) | !allow("ip:" + ip, props.loginAttemptsPerIp())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "too many attempts, try again later");
        }
    }

    public void reset(String username) {
        windows.remove("u:" + username);
    }

    private boolean allow(String key, int limit) {
        long now = clock.millis();
        long cutoff = now - props.loginWindow().toMillis();
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < cutoff) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
