package com.securechat.gateway;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Authenticated connections on THIS gateway instance (at most one per device). */
@Component
public class LocalSessions {

    public static final class Conn {
        final WebSocketSession session;
        final String connectionId;
        volatile UUID userId;
        volatile UUID deviceId;
        volatile Instant tokenExpiresAt;
        volatile Set<UUID> peers = Set.of();
        volatile boolean closed;
        volatile long lastFrameAt = System.currentTimeMillis();
        final RateLimiter limiter;

        Conn(WebSocketSession session, String connectionId, int perSecond) {
            this.session = session;
            this.connectionId = connectionId;
            this.limiter = new RateLimiter(perSecond);
        }

        boolean authenticated() {
            return deviceId != null;
        }

        public void send(String json) {
            if (closed || !session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(json));
            } catch (IOException | IllegalStateException e) {
                closed = true;
            }
        }

        public void close(CloseStatus status) {
            closed = true;
            try {
                session.close(status);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    /** Simple per-connection token bucket. */
    static final class RateLimiter {
        private final int perSecond;
        private double tokens;
        private long last = System.nanoTime();

        RateLimiter(int perSecond) {
            this.perSecond = perSecond;
            this.tokens = perSecond;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(perSecond, tokens + (now - last) / 1e9 * perSecond);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private final Map<String, Conn> bySession = new ConcurrentHashMap<>();
    private final Map<UUID, Conn> byDevice = new ConcurrentHashMap<>();

    public void add(Conn c) {
        bySession.put(c.session.getId(), c);
    }

    public Conn bySession(String sessionId) {
        return bySession.get(sessionId);
    }

    /** Binds a device; returns the previous local connection of the device, if any. */
    public Conn bind(Conn c) {
        Conn prev = byDevice.put(c.deviceId, c);
        return prev == c ? null : prev;
    }

    public Conn device(UUID deviceId) {
        return byDevice.get(deviceId);
    }

    public void remove(Conn c) {
        bySession.remove(c.session.getId());
        if (c.deviceId != null) {
            byDevice.remove(c.deviceId, c);
        }
    }

    public List<Conn> ofUser(UUID userId) {
        return byDevice.values().stream().filter(c -> userId.equals(c.userId)).toList();
    }

    public Collection<Conn> authenticated() {
        return byDevice.values();
    }

    public Collection<Conn> all() {
        return bySession.values();
    }

    public int count() {
        return byDevice.size();
    }
}
