package com.securechat.user;

import com.securechat.contracts.EventType;
import com.securechat.contracts.events.DeviceRegistered;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.outbox.OutboxWriter;
import com.securechat.support.web.ApiException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device registry. Every device has its own id and belongs to exactly one user; only the owner (or
 * an admin through the internal API) can revoke it. Records hold metadata only — key material is
 * generated on the device and public keys are uploaded to Key Management Service, never here.
 */
@Service
public class DeviceService {
    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    static final Set<String> PLATFORMS = Set.of("WEB", "IOS", "ANDROID", "DESKTOP");
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final int maxActive;

    DeviceService(JdbcTemplate jdbc, OutboxWriter outbox, @Value("${securechat.users.max-active-devices:16}") int maxActive) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.maxActive = maxActive;
    }

    public record Device(UUID id, UUID userId, String deviceName, String deviceType, String platform, String status,
            Instant createdAt, Instant revokedAt, Instant lastAuthenticatedAt, Instant lastSeenAt, Instant removedAt) {
    }

    static final RowMapper<Device> MAPPER = (rs, i) -> new Device(rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class), rs.getString("device_name"), rs.getString("device_type"),
            rs.getString("platform"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
            ts(rs.getTimestamp("revoked_at")), ts(rs.getTimestamp("last_authenticated_at")), ts(rs.getTimestamp("last_seen_at")),
            ts(rs.getTimestamp("removed_at")));

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /**
     * Registers a device. The client may propose the id (it generates one locally); re-registering the
     * same id by the same owner is idempotent. An id owned by another user, or a revoked id, is rejected.
     */
    @Transactional
    public Device register(UUID userId, UUID proposedId, String name, String platform, String type) {
        String p = platform == null ? "WEB" : platform.toUpperCase(java.util.Locale.ROOT);
        if (!PLATFORMS.contains(p)) {
            throw ApiException.badRequest("platform must be one of " + PLATFORMS);
        }
        UUID id = proposedId == null ? UUID.randomUUID() : proposedId;
        // Serialise registrations per user so the active-device cap cannot be raced.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, userId.toString());
        Optional<Device> existing = find(id);
        if (existing.isPresent()) {
            Device d = existing.get();
            if (!d.userId().equals(userId) || !"ACTIVE".equals(d.status())) {
                throw ApiException.conflict("CONFLICT", "device id cannot be used");
            }
            return d;
        }
        Integer active = jdbc.queryForObject("SELECT count(*) FROM devices WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
        if (active != null && active >= maxActive) {
            throw ApiException.conflict("CONFLICT", "too many active devices; revoke one first");
        }
        jdbc.update("INSERT INTO devices (id, user_id, device_name, device_type, platform, last_authenticated_at) "
                + "VALUES (?, ?, ?, ?, ?, now())", id, userId, trim(name, 100), trim(type, 30), p);
        outbox.write(EventType.DEVICE_REGISTERED, userId.toString(), id.toString(),
                new DeviceRegistered(userId, id, p, trim(name, 100), Instant.now()));
        log.info("device registered userId={} deviceId={} platform={}", userId, id, p);
        return find(id).orElseThrow();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public Optional<Device> find(UUID id) {
        return jdbc.query("SELECT * FROM devices WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** All devices of a user, including removed ones (admin view). */
    public List<Device> list(UUID userId) {
        return jdbc.query("SELECT * FROM devices WHERE user_id = ? ORDER BY created_at", MAPPER, userId);
    }

    /** The owner's own list: devices they removed are hidden. */
    public List<Device> listVisible(UUID userId) {
        return jdbc.query("SELECT * FROM devices WHERE user_id = ? AND removed_at IS NULL ORDER BY created_at", MAPPER, userId);
    }

    /**
     * Owner removal: revokes the device if it is still active (same event as a revoke), then hides it
     * from the owner's list. Idempotent; another user's device is reported as not found.
     */
    @Transactional
    public void removeOwn(UUID userId, UUID deviceId) {
        Device d = find(deviceId).filter(x -> x.userId().equals(userId)).orElseThrow(() -> ApiException.notFound("device"));
        revoke(d, "USER");
        if (jdbc.update("UPDATE devices SET removed_at = now() WHERE id = ? AND removed_at IS NULL", deviceId) == 1) {
            log.info("device removed from list userId={} deviceId={}", userId, deviceId);
        }
    }

    /** Owner revocation: another user's device is reported as not found (no existence oracle). */
    @Transactional
    public Device revokeOwn(UUID userId, UUID deviceId) {
        Device d = find(deviceId).filter(x -> x.userId().equals(userId)).orElseThrow(() -> ApiException.notFound("device"));
        return revoke(d, "USER");
    }

    @Transactional
    public Device revokeByAdmin(UUID deviceId) {
        return revoke(find(deviceId).orElseThrow(() -> ApiException.notFound("device")), "ADMIN");
    }

    private Device revoke(Device d, String by) {
        int n = jdbc.update("UPDATE devices SET status = 'REVOKED', revoked_at = now(), revoked_by = ? WHERE id = ? AND status = 'ACTIVE'",
                by, d.id());
        if (n == 1) {
            outbox.write(EventType.DEVICE_REVOKED, d.userId().toString(), d.id().toString(),
                    new DeviceRevoked(d.userId(), d.id(), by, Instant.now()));
            log.info("device revoked userId={} deviceId={} by={}", d.userId(), d.id(), by);
        }
        return find(d.id()).orElseThrow();
    }

    /** Active device ids per user (for Fanout / Key Management / Message Service). */
    public Map<UUID, List<UUID>> activeDevices(List<UUID> userIds) {
        if (userIds.size() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "at most 500 users");
        }
        Map<UUID, List<UUID>> out = new HashMap<>();
        userIds.forEach(u -> out.put(u, new java.util.ArrayList<>()));
        if (userIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT user_id, id FROM devices WHERE status = 'ACTIVE' AND user_id = ANY (?) ORDER BY created_at",
                rs -> {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new java.util.ArrayList<>()).add(rs.getObject(2, UUID.class));
                }, (Object) userIds.toArray(UUID[]::new));
        return out;
    }

    @Transactional
    public void touch(UUID deviceId, boolean authenticated) {
        jdbc.update(authenticated ? "UPDATE devices SET last_seen_at = now(), last_authenticated_at = now() WHERE id = ?"
                : "UPDATE devices SET last_seen_at = now() WHERE id = ?", deviceId);
    }

    public long countActive() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM devices WHERE status = 'ACTIVE'", Long.class);
        return n == null ? 0 : n;
    }
}
