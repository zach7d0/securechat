package com.securechat.keys;

import static com.securechat.keys.KeyFormats.encode;

import com.securechat.contracts.EventType;
import com.securechat.contracts.events.PrekeysLow;
import com.securechat.support.outbox.OutboxWriter;
import com.securechat.support.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public-key directory. Stores and distributes public material only; one-time prekeys and MLS key
 * packages are claimed atomically so no two requesters ever receive the same one.
 */
@Service
public class KeyService {
    private static final Logger log = LoggerFactory.getLogger(KeyService.class);
    private static final String CURVE = "CURVE25519";
    private final JdbcTemplate jdbc;
    private final DeviceDirectory directory;
    private final OutboxWriter outbox;
    private final KeyProperties props;
    private final Map<UUID, Long> lastLowNotice = new ConcurrentHashMap<>();

    KeyService(JdbcTemplate jdbc, DeviceDirectory directory, OutboxWriter outbox, KeyProperties props) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.outbox = outbox;
        this.props = props;
    }

    /** The token's device must exist in User Service, belong to the caller and be ACTIVE. */
    public void requireActiveDevice(UUID userId, UUID deviceId) {
        DeviceDirectory.DeviceInfo d = directory.device(deviceId);
        if (d == null || !userId.equals(d.userId())) {
            throw ApiException.forbidden("device does not belong to caller");
        }
        if (!"ACTIVE".equals(d.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REVOKED", "device is revoked");
        }
    }

    // ------------------------------------------------------------------ uploads

    public record IdentityUpload(String identityKey, int registrationId, String mlsSignatureKey, String mlsSignatureBinding) {
    }

    /** Identity is immutable per device: same key → idempotent; different key → 409. */
    @Transactional
    public void putIdentity(UUID userId, UUID deviceId, IdentityUpload u) {
        byte[] ik = KeyFormats.curvePublicKey("identityKey", u.identityKey());
        KeyFormats.registrationId(u.registrationId());
        byte[] mls = KeyFormats.ed25519PublicKey("mlsSignatureKey", u.mlsSignatureKey());
        byte[] binding = KeyFormats.signature("mlsSignatureBinding", u.mlsSignatureBinding());
        List<byte[]> existing = jdbc.query("SELECT public_key FROM identity_keys WHERE device_id = ? AND revoked_at IS NULL",
                (rs, i) -> rs.getBytes(1), deviceId);
        if (!existing.isEmpty()) {
            if (Arrays.equals(existing.get(0), ik)) {
                return;
            }
            throw ApiException.conflict("CONFLICT", "device identity key is immutable; register a new device instead");
        }
        Integer revoked = jdbc.queryForObject("SELECT count(*) FROM identity_keys WHERE device_id = ?", Integer.class, deviceId);
        if (revoked != null && revoked > 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REVOKED", "device keys were revoked");
        }
        jdbc.update("""
                INSERT INTO identity_keys (id, user_id, device_id, public_key, algorithm, key_version, registration_id,
                  mls_signature_key, mls_signature_binding) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)
                """, UUID.randomUUID(), userId, deviceId, ik, CURVE, u.registrationId(), mls, binding);
        log.info("identity key registered userId={} deviceId={}", userId, deviceId);
    }

    private void requireIdentity(UUID deviceId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM identity_keys WHERE device_id = ? AND revoked_at IS NULL",
                Integer.class, deviceId);
        if (n == null || n == 0) {
            throw ApiException.conflict("CONFLICT", "upload the identity key first");
        }
    }

    /** New signed prekey becomes current; older ones are retired (no longer served). */
    @Transactional
    public void putSignedPreKey(UUID deviceId, long keyId, String publicKey, String signature) {
        requireIdentity(deviceId);
        if (keyId < 0 || keyId > 0xFFFFFFL) {
            throw ApiException.badRequest("keyId out of range");
        }
        byte[] pk = KeyFormats.curvePublicKey("publicKey", publicKey);
        byte[] sig = KeyFormats.signature("signature", signature);
        int n = jdbc.update("""
                INSERT INTO signed_prekeys (id, device_id, key_id, public_key, signature, algorithm, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now() + interval '30 days') ON CONFLICT (device_id, key_id) DO NOTHING
                """, UUID.randomUUID(), deviceId, keyId, pk, sig, CURVE);
        if (n == 0) {
            throw ApiException.conflict("CONFLICT", "signed prekey id already used");
        }
        jdbc.update("UPDATE signed_prekeys SET retired_at = now() WHERE device_id = ? AND key_id <> ? AND retired_at IS NULL",
                deviceId, keyId);
    }

    public record PreKey(long keyId, String publicKey) {
    }

    @Transactional
    public int putOneTimePreKeys(UUID deviceId, List<PreKey> keys) {
        requireIdentity(deviceId);
        if (keys == null || keys.isEmpty() || keys.size() > 200) {
            throw ApiException.badRequest("upload 1..200 keys per request");
        }
        Integer available = jdbc.queryForObject("SELECT count(*) FROM one_time_prekey_inventory WHERE device_id = ? AND status = 'AVAILABLE'",
                Integer.class, deviceId);
        if (available != null && available + keys.size() > props.maxAvailablePrekeys()) {
            throw ApiException.conflict("CONFLICT", "prekey inventory is full");
        }
        List<Object[]> rows = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (PreKey k : keys) {
            if (k.keyId() < 0 || k.keyId() > 0xFFFFFFL || !ids.add(k.keyId())) {
                throw ApiException.badRequest("keyIds must be unique and in range");
            }
            rows.add(new Object[] {UUID.randomUUID(), deviceId, k.keyId(), KeyFormats.curvePublicKey("publicKey", k.publicKey())});
        }
        int inserted = Arrays.stream(jdbc.batchUpdate("INSERT INTO one_time_prekey_inventory (id, device_id, key_id, public_key) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (device_id, key_id) DO NOTHING", rows)).sum();
        lastLowNotice.remove(deviceId);
        return inserted;
    }

    public record KeyPackageUpload(UUID id, String keyPackage, boolean lastResort) {
    }

    @Transactional
    public int putKeyPackages(UUID userId, UUID deviceId, List<KeyPackageUpload> pkgs) {
        requireIdentity(deviceId);
        if (pkgs == null || pkgs.isEmpty() || pkgs.size() > 50) {
            throw ApiException.badRequest("upload 1..50 key packages per request");
        }
        Integer available = jdbc.queryForObject("SELECT count(*) FROM mls_key_packages WHERE device_id = ? AND status = 'AVAILABLE'",
                Integer.class, deviceId);
        if (available != null && available + pkgs.size() > props.maxAvailableKeyPackages()) {
            throw ApiException.conflict("CONFLICT", "key package inventory is full");
        }
        int n = 0;
        for (KeyPackageUpload p : pkgs) {
            if (p.id() == null) {
                throw ApiException.badRequest("key package id is required");
            }
            byte[] kp = KeyFormats.mlsKeyPackage("keyPackage", p.keyPackage());
            if (p.lastResort()) {
                // only one live last-resort package per device
                jdbc.update("UPDATE mls_key_packages SET status = 'REVOKED' WHERE device_id = ? AND last_resort AND status = 'AVAILABLE'",
                        deviceId);
            }
            n += jdbc.update("INSERT INTO mls_key_packages (id, user_id, device_id, key_package, last_resort) VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT (id) DO NOTHING", p.id(), userId, deviceId, kp, p.lastResort());
        }
        lastLowNotice.remove(deviceId);
        return n;
    }

    public record KeyStatus(boolean identity, long oneTimePreKeys, long mlsKeyPackages, boolean lastResortKeyPackage,
            Long signedPreKeyId, Instant signedPreKeyCreatedAt) {
    }

    public KeyStatus status(UUID deviceId) {
        boolean identity = Optional.ofNullable(jdbc.queryForObject(
                "SELECT count(*) FROM identity_keys WHERE device_id = ? AND revoked_at IS NULL", Long.class, deviceId)).orElse(0L) > 0;
        long otpk = count("SELECT count(*) FROM one_time_prekey_inventory WHERE device_id = ? AND status = 'AVAILABLE'", deviceId);
        long kp = count("SELECT count(*) FROM mls_key_packages WHERE device_id = ? AND status = 'AVAILABLE' AND NOT last_resort", deviceId);
        long lr = count("SELECT count(*) FROM mls_key_packages WHERE device_id = ? AND status = 'AVAILABLE' AND last_resort", deviceId);
        var spk = jdbc.query("SELECT key_id, created_at FROM signed_prekeys WHERE device_id = ? AND retired_at IS NULL ORDER BY created_at DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getTimestamp(2).toInstant()}, deviceId);
        return new KeyStatus(identity, otpk, kp, lr > 0, spk.isEmpty() ? null : (Long) spk.get(0)[0],
                spk.isEmpty() ? null : (Instant) spk.get(0)[1]);
    }

    private long count(String sql, UUID deviceId) {
        Long n = jdbc.queryForObject(sql, Long.class, deviceId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ distribution

    public record DeviceIdentity(UUID deviceId, int registrationId, String identityKey, String mlsSignatureKey,
            String mlsSignatureBinding, int keyVersion, Instant createdAt) {
    }

    /** Identity keys of the user's ACTIVE devices (revoked devices are never served). */
    public List<DeviceIdentity> identities(UUID userId) {
        List<UUID> active = directory.activeDevices(List.of(userId)).getOrDefault(userId, List.of());
        if (active.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT device_id, registration_id, public_key, mls_signature_key, mls_signature_binding, key_version, created_at
                FROM identity_keys WHERE user_id = ? AND revoked_at IS NULL AND device_id = ANY (?) ORDER BY created_at
                """, (rs, i) -> new DeviceIdentity(rs.getObject(1, UUID.class), rs.getInt(2), encode(rs.getBytes(3)),
                encode(rs.getBytes(4)), encode(rs.getBytes(5)), rs.getInt(6), rs.getTimestamp(7).toInstant()),
                userId, active.toArray(UUID[]::new));
    }

    public record SignedPreKeyView(long keyId, String publicKey, String signature) {
    }

    public record KeyPackageView(UUID id, String keyPackage, boolean lastResort) {
    }

    /** {@code preKey} is null when inventory is depleted: the Signal protocol then runs X3DH without a one-time prekey. */
    public record DeviceBundle(UUID deviceId, int registrationId, String identityKey, String mlsSignatureKey,
            String mlsSignatureBinding, SignedPreKeyView signedPreKey, PreKey preKey, KeyPackageView mlsKeyPackage) {
    }

    public record UserBundle(UUID userId, List<DeviceBundle> devices) {
    }

    /**
     * Claims a bundle for every eligible device of {@code userId} (optionally restricted to
     * {@code deviceIds}). Each one-time prekey / key package is consumed by a single atomic
     * {@code UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED)} so concurrent claimers can never
     * receive the same key. Last-resort key packages are returned without being consumed.
     */
    @Transactional
    public UserBundle claim(UUID requesterDeviceId, UUID userId, boolean includeMls, Set<UUID> deviceIds) {
        List<DeviceBundle> out = new ArrayList<>();
        for (DeviceIdentity id : identities(userId)) {
            if (id.deviceId().equals(requesterDeviceId) || (deviceIds != null && !deviceIds.isEmpty() && !deviceIds.contains(id.deviceId()))) {
                continue;
            }
            var spk = jdbc.query("SELECT key_id, public_key, signature FROM signed_prekeys WHERE device_id = ? AND retired_at IS NULL "
                    + "ORDER BY created_at DESC LIMIT 1", (rs, i) -> new SignedPreKeyView(rs.getLong(1), encode(rs.getBytes(2)),
                    encode(rs.getBytes(3))), id.deviceId());
            if (spk.isEmpty()) {
                continue; // device not ready for session establishment
            }
            PreKey pre = jdbc.query("""
                    UPDATE one_time_prekey_inventory SET status = 'CLAIMED', claimed_at = now(), claimed_by_device = ?
                    WHERE id = (SELECT id FROM one_time_prekey_inventory WHERE device_id = ? AND status = 'AVAILABLE'
                                ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
                    RETURNING key_id, public_key
                    """, (rs, i) -> new PreKey(rs.getLong(1), encode(rs.getBytes(2))), requesterDeviceId, id.deviceId())
                    .stream().findFirst().orElse(null);
            KeyPackageView kp = null;
            if (includeMls) {
                kp = jdbc.query("""
                        UPDATE mls_key_packages SET status = 'CLAIMED', claimed_at = now()
                        WHERE id = (SELECT id FROM mls_key_packages WHERE device_id = ? AND status = 'AVAILABLE' AND NOT last_resort
                                    ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id, key_package, last_resort
                        """, (rs, i) -> new KeyPackageView(rs.getObject(1, UUID.class), encode(rs.getBytes(2)), false), id.deviceId())
                        .stream().findFirst()
                        .or(() -> jdbc.query("SELECT id, key_package FROM mls_key_packages WHERE device_id = ? AND status = 'AVAILABLE' "
                                + "AND last_resort ORDER BY created_at DESC LIMIT 1", (rs, i) -> new KeyPackageView(rs.getObject(1, UUID.class),
                                encode(rs.getBytes(2)), true), id.deviceId()).stream().findFirst())
                        .orElse(null);
            }
            out.add(new DeviceBundle(id.deviceId(), id.registrationId(), id.identityKey(), id.mlsSignatureKey(),
                    id.mlsSignatureBinding(), spk.get(0), pre, kp));
            maybeNotifyLow(userId, id.deviceId());
        }
        return new UserBundle(userId, out);
    }

    private void maybeNotifyLow(UUID userId, UUID deviceId) {
        KeyStatus s = status(deviceId);
        if (s.oneTimePreKeys() >= props.prekeyLowThreshold() && s.mlsKeyPackages() >= props.keyPackageLowThreshold()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastLowNotice.get(deviceId);
        if (last != null && now - last < 60_000) {
            return;
        }
        lastLowNotice.put(deviceId, now);
        outbox.write(EventType.KEYS_PREKEYS_LOW, deviceId.toString(), deviceId.toString(),
                new PrekeysLow(userId, deviceId, s.oneTimePreKeys(), s.mlsKeyPackages()));
    }

    /** Device revoked: stop distributing everything it published (idempotent). */
    @Transactional
    public void revokeDevice(UUID deviceId) {
        jdbc.update("UPDATE identity_keys SET revoked_at = now() WHERE device_id = ? AND revoked_at IS NULL", deviceId);
        jdbc.update("UPDATE one_time_prekey_inventory SET status = 'REVOKED' WHERE device_id = ? AND status = 'AVAILABLE'", deviceId);
        jdbc.update("UPDATE mls_key_packages SET status = 'REVOKED' WHERE device_id = ? AND status = 'AVAILABLE'", deviceId);
        jdbc.update("UPDATE signed_prekeys SET retired_at = now() WHERE device_id = ? AND retired_at IS NULL", deviceId);
        log.info("device keys revoked deviceId={}", deviceId);
    }

    /** Devices with an active identity, per user (Message Service stale-device-list check). */
    public Map<UUID, List<UUID>> readyDevices(List<UUID> userIds) {
        Map<UUID, List<UUID>> active = directory.activeDevices(userIds);
        Map<UUID, List<UUID>> out = new java.util.HashMap<>();
        for (UUID u : userIds) {
            List<UUID> a = active.getOrDefault(u, List.of());
            out.put(u, a.isEmpty() ? List.of() : jdbc.queryForList("SELECT device_id FROM identity_keys WHERE user_id = ? "
                    + "AND revoked_at IS NULL AND device_id = ANY (?)", UUID.class, u, a.toArray(UUID[]::new)));
        }
        return out;
    }

    public record AdminKeyStatus(UUID deviceId, boolean identity, Instant identityCreatedAt, Instant revokedAt,
            int keyVersion, long oneTimePreKeys, long mlsKeyPackages) {
    }

    /** Operational key metadata for admins: counts and timestamps only, never key bytes. */
    public AdminKeyStatus adminStatus(UUID deviceId) {
        var id = jdbc.query("SELECT created_at, revoked_at, key_version FROM identity_keys WHERE device_id = ? ORDER BY key_version DESC LIMIT 1",
                (rs, i) -> new Object[] {rs.getTimestamp(1).toInstant(), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getInt(3)}, deviceId);
        KeyStatus s = status(deviceId);
        return new AdminKeyStatus(deviceId, !id.isEmpty() && id.get(0)[1] == null, id.isEmpty() ? null : (Instant) id.get(0)[0],
                id.isEmpty() ? null : (Instant) id.get(0)[1], id.isEmpty() ? 0 : (Integer) id.get(0)[2], s.oneTimePreKeys(),
                s.mlsKeyPackages());
    }

    static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
