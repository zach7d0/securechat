package com.securechat.gateway;

import com.securechat.contracts.EventCodec;
import com.securechat.support.security.AuthenticatedUser;
import com.securechat.support.web.Correlation;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Client connection lifecycle. Payloads are opaque ciphertext: the gateway parses only the
 * transport envelope and never decrypts. See backend/platform/contracts/README.md for frames.
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);
    public static final CloseStatus UNAUTHENTICATED = new CloseStatus(4401, "unauthenticated");
    public static final CloseStatus REVOKED = new CloseStatus(4403, "revoked");
    public static final CloseStatus REPLACED = new CloseStatus(4409, "replaced by newer connection");
    public static final CloseStatus RATE_LIMITED = new CloseStatus(4429, "rate limited");

    private final JwtDecoder jwt;
    private final ConnectionRegistry registry;
    private final LocalSessions sessions;
    private final Downstream downstream;
    private final GatewayProperties props;
    private final MeterRegistry meters;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

    ChatSocketHandler(JwtDecoder jwt, ConnectionRegistry registry, LocalSessions sessions, Downstream downstream,
            GatewayProperties props, MeterRegistry meters) {
        this.jwt = jwt;
        this.registry = registry;
        this.sessions = sessions;
        this.downstream = downstream;
        this.props = props;
        this.meters = meters;
        Gauge.builder("websocket.connections.active", sessions, LocalSessions::count).register(meters);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        WebSocketSession s = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 1024 * 1024,
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        LocalSessions.Conn c = new LocalSessions.Conn(s, UUID.randomUUID().toString(), props.messagesPerSecond());
        sessions.add(c);
        timers.schedule(() -> {
            if (!c.authenticated() && !c.closed) {
                meters.counter("websocket.auth.failures", "reason", "timeout").increment();
                c.close(UNAUTHENTICATED);
            }
        }, props.authTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        LocalSessions.Conn c = sessions.bySession(raw.getId());
        if (c == null || c.closed) {
            return;
        }
        c.lastFrameAt = System.currentTimeMillis();
        Correlation.set(UUID.randomUUID().toString());
        try {
            JsonNode f;
            try {
                f = EventCodec.mapper().readTree(message.getPayload());
            } catch (RuntimeException e) {
                c.send(error(null, "VALIDATION_FAILED", "invalid frame"));
                return;
            }
            String type = f.path("type").asString("");
            if (!c.authenticated() && !"auth".equals(type)) {
                c.close(UNAUTHENTICATED);
                return;
            }
            switch (type) {
                case "auth" -> auth(c, f);
                case "ping" -> {
                    registry.heartbeat(c.deviceId, c.connectionId);
                    c.send("{\"type\":\"pong\"}");
                }
                case "send" -> send(c, f);
                case "ack" -> ack(c, f);
                case "typing" -> typing(c, f);
                case "sync" -> Thread.ofVirtual().start(() -> safeReplay(c));
                default -> c.send(error(null, "VALIDATION_FAILED", "unknown frame type"));
            }
        } finally {
            Correlation.clear();
        }
    }

    private void auth(LocalSessions.Conn c, JsonNode f) {
        Jwt token;
        try {
            token = jwt.decode(f.path("token").asString(""));
        } catch (JwtException e) {
            meters.counter("websocket.auth.failures", "reason", "invalid_token").increment();
            c.close(UNAUTHENTICATED);
            return;
        }
        AuthenticatedUser who = AuthenticatedUser.from(token);
        if (who.deviceId() == null) {
            meters.counter("websocket.auth.failures", "reason", "no_device").increment();
            c.close(UNAUTHENTICATED);
            return;
        }
        if (c.authenticated()) {
            // token refresh on a live connection: must be the same user and device
            if (!who.userId().equals(c.userId) || !who.deviceId().equals(c.deviceId)) {
                c.close(UNAUTHENTICATED);
                return;
            }
            c.tokenExpiresAt = who.expiresAt();
            c.send(ready(c));
            return;
        }
        Downstream.DeviceStatus d;
        try {
            d = downstream.device(who.deviceId());
        } catch (RuntimeException e) {
            c.close(new CloseStatus(1013, "try again later"));    // fail closed
            return;
        }
        if (d == null || !who.userId().equals(d.userId()) || !"ACTIVE".equals(d.status())) {
            meters.counter("websocket.auth.failures", "reason", "device").increment();
            c.send("{\"type\":\"revoked\",\"reason\":\"device is not active\"}");
            c.close(REVOKED);
            return;
        }
        c.userId = who.userId();
        c.deviceId = who.deviceId();
        c.tokenExpiresAt = who.expiresAt();
        LocalSessions.Conn prevLocal = sessions.bind(c);
        if (prevLocal != null) {
            prevLocal.close(REPLACED);
        }
        ConnectionRegistry.Owner prev = registry.register(c.deviceId, c.userId, c.connectionId);
        if (prev != null && !prev.gatewayId().equals(props.id())) {
            // newest connection wins across gateways: tell the previous owner to drop its socket
            ObjectNode evict = EventCodec.mapper().createObjectNode();
            evict.put("type", "evict").put("deviceId", c.deviceId.toString()).put("connectionId", prev.connectionId());
            registry.publish(prev.gatewayId(), evict.toString());
        }
        try {
            c.peers = new java.util.HashSet<>(downstream.peers(c.userId));
        } catch (RuntimeException e) {
            log.warn("peer list unavailable for userId={}", c.userId);
        }
        c.send(ready(c));
        log.info("device connected userId={} deviceId={} gatewayId={}", c.userId, c.deviceId, props.id());
        Thread.ofVirtual().start(() -> {
            try {
                downstream.presenceConnect(c.userId, c.deviceId, props.id());
            } catch (RuntimeException e) {
                log.warn("presence connect failed deviceId={}", c.deviceId);
                safeReplay(c);   // presence emits device.connected → replay; if it is down, replay directly
            }
            try {
                downstream.deviceSeen(c.deviceId);
            } catch (RuntimeException ignored) {
                // best effort metadata
            }
        });
    }

    private String ready(LocalSessions.Conn c) {
        ObjectNode n = EventCodec.mapper().createObjectNode();
        n.put("type", "ready").put("userId", c.userId.toString()).put("deviceId", c.deviceId.toString()).put("gatewayId", props.id())
                .put("tokenExpiresAt", c.tokenExpiresAt == null ? null : c.tokenExpiresAt.toString());
        return n.toString();
    }

    private void send(LocalSessions.Conn c, JsonNode f) {
        String clientMessageId = f.path("clientMessageId").asString(null);
        if (!c.limiter.tryAcquire()) {
            meters.counter("websocket.rate_limited").increment();
            c.send(error(clientMessageId, "RATE_LIMITED", "slow down"));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderUserId", c.userId);
        body.put("senderDeviceId", c.deviceId);
        for (String k : List.of("conversationId", "clientMessageId", "protocol", "messageType", "ciphertext", "epoch", "expiresInSeconds")) {
            if (f.has(k) && !f.get(k).isNull()) {
                body.put(k, EventCodec.mapper().treeToValue(f.get(k), Object.class));
            }
        }
        if (f.has("envelopes")) {
            body.put("envelopes", EventCodec.mapper().treeToValue(f.get("envelopes"), Object.class));
        }
        try {
            JsonNode accepted = downstream.submit(body);
            ObjectNode out = EventCodec.mapper().createObjectNode();
            out.put("type", "accepted").put("clientMessageId", clientMessageId)
                    .put("messageId", accepted.path("messageId").asString())
                    .put("createdAt", accepted.path("createdAt").asString()).put("status", accepted.path("status").asString("ACCEPTED"));
            c.send(out.toString());
        } catch (Downstream.DownstreamError e) {
            ObjectNode err = (ObjectNode) EventCodec.mapper().readTree(error(clientMessageId, e.code, e.getMessage()));
            if (e.details != null) {
                for (String k : List.of("currentEpoch", "missingDeviceIds")) {
                    if (e.details.has(k)) {
                        err.set(k, e.details.get(k));
                    }
                }
            }
            c.send(err.toString());
        }
    }

    private void ack(LocalSessions.Conn c, JsonNode f) {
        String status = f.path("status").asString("");
        if (!List.of("DELIVERED", "READ").contains(status) || !f.path("messageIds").isArray()) {
            c.send(error(null, "VALIDATION_FAILED", "ack needs status and messageIds"));
            return;
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode n : f.get("messageIds")) {
            try {
                ids.add(UUID.fromString(n.asString()));
            } catch (IllegalArgumentException e) {
                c.send(error(null, "VALIDATION_FAILED", "invalid message id"));
                return;
            }
            if (ids.size() > 500) {
                break;
            }
        }
        try {
            downstream.ack(c.userId, c.deviceId, status, ids);
        } catch (Downstream.DownstreamError e) {
            c.send(error(null, e.code, "ack not recorded, retry"));
        }
    }

    private final Map<String, CachedAuth> typingAuth = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedAuth(List<UUID> members, Instant at) {
    }

    private void typing(LocalSessions.Conn c, JsonNode f) {
        UUID conv;
        try {
            conv = UUID.fromString(f.path("conversationId").asString(""));
        } catch (IllegalArgumentException e) {
            return;
        }
        String key = conv + ":" + c.userId;
        CachedAuth a = typingAuth.get(key);
        if (a == null || a.at().isBefore(Instant.now().minusSeconds(30))) {
            try {
                Downstream.Authorization auth = downstream.authorize(conv, c.userId);
                a = new CachedAuth(auth.member() ? auth.memberUserIds() : List.of(), Instant.now());
                typingAuth.put(key, a);
            } catch (RuntimeException e) {
                return;   // ephemeral: drop
            }
        }
        if (a.members().isEmpty()) {
            return;
        }
        ObjectNode n = EventCodec.mapper().createObjectNode();
        n.put("type", "typing").put("conversationId", conv.toString()).put("userId", c.userId.toString())
                .put("state", "start".equals(f.path("state").asString()) ? "start" : "stop");
        n.set("members", EventCodec.mapper().valueToTree(a.members()));
        registry.publishTyping(n.toString());
    }

    private void safeReplay(LocalSessions.Conn c) {
        try {
            downstream.replay(c.userId, c.deviceId);
        } catch (RuntimeException e) {
            log.warn("replay request failed deviceId={}", c.deviceId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        LocalSessions.Conn c = sessions.bySession(raw.getId());
        if (c == null) {
            return;
        }
        c.closed = true;
        sessions.remove(c);
        if (c.deviceId != null) {
            registry.unregister(c.deviceId, c.connectionId);
            if (status.getCode() != REPLACED.getCode()) {
                Thread.ofVirtual().start(() -> {
                    try {
                        downstream.presenceDisconnect(c.userId, c.deviceId, props.id());
                    } catch (RuntimeException e) {
                        log.debug("presence disconnect failed; TTL will expire it");
                    }
                });
            }
            log.info("device disconnected deviceId={} code={}", c.deviceId, status.getCode());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error {}", exception.getClass().getSimpleName());
    }

    static String error(String clientMessageId, String code, String message) {
        ObjectNode n = EventCodec.mapper().createObjectNode();
        n.put("type", "error").put("code", code).put("message", message);
        if (clientMessageId != null) {
            n.put("clientMessageId", clientMessageId);
        }
        return n.toString();
    }
}
