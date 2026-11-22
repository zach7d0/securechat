package com.securechat.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * Periodic upkeep: refresh connection leases for live sockets, close idle sockets and sockets whose
 * access token expired (plus grace) without a refresh, batch heartbeats to Presence, and advertise
 * gateway liveness. Graceful shutdown closes sockets with 1012 (clients reconnect elsewhere).
 */
@Component
class Maintenance {
    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private final LocalSessions sessions;
    private final ConnectionRegistry registry;
    private final Downstream downstream;
    private final GatewayProperties props;

    Maintenance(LocalSessions sessions, ConnectionRegistry registry, Downstream downstream, GatewayProperties props) {
        this.sessions = sessions;
        this.registry = registry;
        this.downstream = downstream;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${securechat.gateway.maintenance-interval:5s}")
    void sweep() {
        long now = System.currentTimeMillis();
        long idleLimit = props.connectionTtl().toMillis();
        for (LocalSessions.Conn c : List.copyOf(sessions.authenticated())) {
            if (now - c.lastFrameAt > idleLimit) {
                c.close(new CloseStatus(4408, "heartbeat timeout"));
                continue;
            }
            if (c.tokenExpiresAt != null && c.tokenExpiresAt.plus(props.tokenGrace()).isBefore(Instant.now())) {
                c.send(ChatSocketHandler.error(null, "UNAUTHENTICATED", "access token expired"));
                c.close(ChatSocketHandler.UNAUTHENTICATED);
                continue;
            }
            if (!registry.heartbeat(c.deviceId, c.connectionId)) {
                // mapping lost (Redis restart/eviction) or taken by a newer connection elsewhere
                ConnectionRegistry.Owner o = registry.owner(c.deviceId);
                if (o == null) {
                    registry.register(c.deviceId, c.userId, c.connectionId);   // rebuild after Redis loss
                } else {
                    c.close(ChatSocketHandler.REPLACED);
                }
            }
        }
        try {
            registry.markAlive();
        } catch (RuntimeException e) {
            log.warn("redis unavailable: {}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${securechat.gateway.presence-heartbeat-interval:15s}")
    void presenceHeartbeat() {
        List<Map<String, UUID>> devices = new ArrayList<>();
        for (LocalSessions.Conn c : sessions.authenticated()) {
            devices.add(Map.of("userId", c.userId, "deviceId", c.deviceId));
        }
        if (devices.isEmpty()) {
            return;
        }
        try {
            downstream.presenceHeartbeat(props.id(), devices);
        } catch (RuntimeException e) {
            log.warn("presence heartbeat failed ({} devices)", devices.size());
        }
    }

    /** Runs before the web server stops (ContextClosedEvent precedes lifecycle shutdown). */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void shutdown() {
        for (LocalSessions.Conn c : List.copyOf(sessions.all())) {
            c.close(CloseStatus.SERVICE_RESTARTED);
        }
    }
}
