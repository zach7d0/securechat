package com.securechat.presence;

import com.securechat.contracts.EventType;
import com.securechat.contracts.events.DeviceConnection;
import com.securechat.contracts.events.PresenceChanged;
import com.securechat.support.client.InternalClients;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.support.security.SecureChatSecurityProperties;
import com.securechat.support.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Presence workflow. Events are emitted only on aggregate transitions (offline↔online), never
 * because a single device of a multi-device user disconnected. Presence is best-effort: if Redis is
 * unavailable the API reports UNKNOWN (never "everyone offline") and state is rebuilt from
 * gateway heartbeats once Redis is back.
 */
@Service
public class PresenceService {
    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private final PresenceStore store;
    private final EventPublisher events;
    private final MeterRegistry meters;
    private final RestClient conversations;

    PresenceService(PresenceStore store, EventPublisher events, MeterRegistry meters, RestClient.Builder b,
            SecureChatSecurityProperties sec, @Value("${securechat.internal.conversation-url}") String conv) {
        this.store = store;
        this.events = events;
        this.meters = meters;
        this.conversations = InternalClients.create(b, conv, sec.internalToken(), Duration.ofSeconds(3));
        io.micrometer.core.instrument.Gauge.builder("presence.online.users", this, p -> {
            try {
                return p.store.onlineUsers();
            } catch (RuntimeException e) {
                return Double.NaN;   // Redis unavailable → unknown, never "0 online"
            }
        }).register(meters);
    }

    /**
     * Each Redis transition is atomic (Lua), but two threads could PUBLISH their events in the opposite
     * order to the order their transitions ran. Transition + publish are therefore serialised per user
     * (striped locks), so subscribers see events in state order. Scope: one presence-service instance
     * (MVP). With several instances, add a per-user version from the Lua scripts to the event (FUTURE).
     */
    private final java.util.concurrent.locks.ReentrantLock[] stripes = new java.util.concurrent.locks.ReentrantLock[256];

    {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new java.util.concurrent.locks.ReentrantLock();
        }
    }

    private <T> T locked(UUID userId, java.util.function.Supplier<T> f) {
        var l = stripes[Math.floorMod(userId.hashCode(), stripes.length)];
        l.lock();
        try {
            return f.get();
        } finally {
            l.unlock();
        }
    }

    public void connect(UUID userId, UUID deviceId, String gatewayId) {
        locked(userId, () -> {
            doConnect(userId, deviceId, gatewayId);
            return null;
        });
    }

    private void doConnect(UUID userId, UUID deviceId, String gatewayId) {
        PresenceStore.Transition t = store.touch(userId, deviceId, gatewayId);
        emit(EventType.DEVICE_CONNECTED, deviceId.toString(), deviceId.toString(), new DeviceConnection(userId, deviceId, gatewayId, Instant.now()));
        if (t.changed()) {
            changed(userId, true, t.activeDevices(), null);
        }
    }

    public void heartbeat(String gatewayId, List<Map<String, UUID>> devices) {
        for (Map<String, UUID> d : devices) {
            locked(d.get("userId"), () -> {
                PresenceStore.Transition t = store.touch(d.get("userId"), d.get("deviceId"), gatewayId);
                if (t.changed()) {
                    // e.g. rebuilt after a Redis restart
                    changed(d.get("userId"), true, t.activeDevices(), null);
                }
                return null;
            });
        }
    }

    public void disconnect(UUID userId, UUID deviceId, String gatewayId) {
        locked(userId, () -> {
            doDisconnect(userId, deviceId, gatewayId);
            return null;
        });
    }

    private void doDisconnect(UUID userId, UUID deviceId, String gatewayId) {
        PresenceStore.Disconnect r = store.disconnect(userId, deviceId, gatewayId);
        if (!r.applied()) {
            return; // stale disconnect from a gateway that no longer owns the device
        }
        emit(EventType.DEVICE_DISCONNECTED, deviceId.toString(), deviceId.toString(),
                new DeviceConnection(userId, deviceId, gatewayId, Instant.now()));
        if (r.wentOffline()) {
            changed(userId, false, 0, Instant.now());
        }
    }

    /** Detects devices that vanished without a disconnect (gateway/network failure) via lease expiry. */
    @Scheduled(fixedDelayString = "${securechat.presence.sweep-interval:5s}")
    public void sweep() {
        try {
            for (String u : store.expiredUsers(1000)) {
                UUID user = UUID.fromString(u);
                locked(user, () -> {
                    PresenceStore.Transition t = store.recompute(user);
                    if (t.changed()) {
                        meters.counter("presence.heartbeat.expired").increment();
                        changed(user, false, 0, Instant.now());
                    }
                    return null;
                });
            }
            for (String gw : store.deadGateways()) {
                List<UUID> removed = store.reconcileGateway(gw);
                log.warn("reconciled dead gateway {} (removed {} stale connection mappings)", gw, removed.size());
                meters.counter("presence.gateway.reconciled").increment();
            }
        } catch (DataAccessException e) {
            log.warn("presence sweep skipped: redis unavailable");
        }
    }

    private void changed(UUID userId, boolean online, long active, Instant lastSeen) {
        PresenceChanged p = new PresenceChanged(userId, online, (int) active, lastSeen, Instant.now());
        UUID id = UUID.randomUUID();
        emit(EventType.PRESENCE_CHANGED, userId.toString(), userId.toString(), p);
        emit(online ? EventType.PRESENCE_ONLINE : EventType.PRESENCE_OFFLINE, userId.toString(), userId.toString(), p);
        meters.counter("presence.transitions", "to", online ? "online" : "offline").increment();
        log.debug("presence {} userId={} event={}", online ? "online" : "offline", userId, id);
    }

    private void emit(EventType t, String key, String aggregate, Object payload) {
        try {
            events.publish(t, UUID.randomUUID(), key, aggregate, payload);
        } catch (RuntimeException e) {
            // presence notifications are ephemeral; state stays correct in Redis
            log.warn("presence event {} not published: {}", t.wireName(), e.getClass().getSimpleName());
        }
    }

    public record PresenceView(UUID userId, String status, Instant lastSeen, long activeDevices) {
    }

    /** Presence of requested users, restricted to the caller's conversation peers. */
    public List<PresenceView> query(UUID requester, List<UUID> userIds) {
        if (userIds.size() > 200) {
            throw ApiException.badRequest("at most 200 users");
        }
        Set<UUID> peers;
        try {
            peers = new HashSet<>(conversations.get().uri("/internal/v1/users/{id}/peers", requester).retrieve()
                    .body(new ParameterizedTypeReference<List<UUID>>() { }));
        } catch (RuntimeException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "conversation service unavailable");
        }
        List<PresenceView> out = new ArrayList<>();
        for (UUID u : userIds) {
            if (!peers.contains(u)) {
                continue;
            }
            try {
                PresenceStore.View v = store.view(u);
                out.add(new PresenceView(u, v.online() ? "ONLINE" : "OFFLINE",
                        v.lastSeenMillis() == null ? null : Instant.ofEpochMilli(v.lastSeenMillis()), v.activeDevices()));
            } catch (DataAccessException e) {
                out.add(new PresenceView(u, "UNKNOWN", null, 0));
            }
        }
        return out;
    }

    public Map<String, Object> stats() {
        try {
            return Map.of("onlineUsers", store.onlineUsers(), "status", "OK");
        } catch (DataAccessException e) {
            return Map.of("onlineUsers", -1, "status", "UNKNOWN");
        }
    }
}
