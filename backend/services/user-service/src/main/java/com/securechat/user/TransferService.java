package com.securechat.user;

import com.securechat.support.web.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relay for device-to-device history transfer. Every operation is bound to the caller's
 * authenticated, ACTIVE device; only devices of the SAME user take part. The server relays opaque
 * ciphertext and cannot decrypt it (see V2__device_transfers.sql).
 */
@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    static final int MAX_CHUNK_BYTES = 192 * 1024;
    static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;
    static final int MAX_CHUNKS = 200;
    static final Duration TTL = Duration.ofHours(24);
    /** A request may ask for at most this much history (one "month" plus slack for clock skew). */
    static final Duration MAX_WINDOW = Duration.ofDays(31);
    static final int MAX_REQUESTS_PER_DAY = 30;
    private final JdbcTemplate jdbc;
    private final DeviceService devices;

    TransferService(JdbcTemplate jdbc, DeviceService devices) {
        this.jdbc = jdbc;
        this.devices = devices;
    }

    public record Transfer(UUID id, UUID targetDeviceId, String targetDeviceName, UUID sourceDeviceId, String ephemeralPublicKey,
            String requestSignature, String status, String manifest, String manifestSignature, long totalBytes, Integer chunkCount,
            Instant createdAt, Instant expiresAt, Instant windowFrom, Instant windowTo) {
    }

    private void requireActive(UUID userId, UUID deviceId) {
        DeviceService.Device d = devices.find(deviceId).filter(x -> x.userId().equals(userId))
                .orElseThrow(() -> ApiException.forbidden("unknown device"));
        if (!"ACTIVE".equals(d.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REVOKED", "device is revoked");
        }
    }

    private Transfer load(UUID userId, UUID id) {
        return jdbc.query("""
                SELECT t.*, d.device_name FROM device_transfers t LEFT JOIN devices d ON d.id = t.target_device_id
                WHERE t.id = ? AND t.user_id = ?
                """, (rs, i) -> new Transfer(rs.getObject("id", UUID.class), rs.getObject("target_device_id", UUID.class),
                rs.getString("device_name"), rs.getObject("source_device_id", UUID.class), rs.getString("ephemeral_public_key"),
                rs.getString("request_signature"), rs.getString("status"), rs.getString("manifest"), rs.getString("manifest_signature"),
                rs.getLong("total_bytes"), (Integer) rs.getObject("chunk_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), ts(rs.getTimestamp("window_from")), ts(rs.getTimestamp("window_to"))),
                id, userId).stream().findFirst()
                .filter(t -> t.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.notFound("transfer"));
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public Transfer request(UUID userId, UUID targetDevice, String ephemeralPublicKey, String signature) {
        return request(userId, targetDevice, ephemeralPublicKey, signature, null, null);
    }

    /**
     * New device asks for history in [windowFrom, windowTo]. Its ephemeral key and the window are signed by
     * its identity key (verified by the source device, which then sends automatically).
     */
    @Transactional
    public Transfer request(UUID userId, UUID targetDevice, String ephemeralPublicKey, String signature, Instant windowFrom,
            Instant windowTo) {
        requireActive(userId, targetDevice);
        if ((windowFrom == null) != (windowTo == null)) {
            throw ApiException.badRequest("windowFrom and windowTo go together");
        }
        if (windowFrom != null && (!windowFrom.isBefore(windowTo) || Duration.between(windowFrom, windowTo).compareTo(MAX_WINDOW) > 0
                || windowTo.isAfter(Instant.now().plus(Duration.ofMinutes(10))))) {
            throw ApiException.badRequest("window must be at most " + MAX_WINDOW.toDays() + " days and not in the future");
        }
        Integer today = jdbc.queryForObject("SELECT count(*) FROM device_transfers WHERE user_id = ? AND created_at > now() - interval '1 day'",
                Integer.class, userId);
        if (today != null && today >= MAX_REQUESTS_PER_DAY) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "too many history requests today");
        }
        if (b64(ephemeralPublicKey, 64).length != 32 || b64(signature, 128).length != 64) {
            throw ApiException.badRequest("ephemeralPublicKey must be 32 bytes and signature 64 bytes (base64)");
        }
        Integer open = jdbc.queryForObject("SELECT count(*) FROM device_transfers WHERE user_id = ? AND status IN ('REQUESTED','APPROVED','READY') "
                + "AND expires_at > now()", Integer.class, userId);
        if (open != null && open >= 3) {
            throw ApiException.conflict("CONFLICT", "too many open transfers; finish or decline one first");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device_transfers (id, user_id, target_device_id, ephemeral_public_key, request_signature, expires_at, "
                + "window_from, window_to) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, userId, targetDevice, ephemeralPublicKey, signature,
                Timestamp.from(Instant.now().plus(TTL)), windowFrom == null ? null : Timestamp.from(windowFrom),
                windowTo == null ? null : Timestamp.from(windowTo));
        log.info("history transfer requested userId={} transferId={} target={}", userId, id, targetDevice);
        return load(userId, id);
    }

    /** Transfers this device takes part in: its own requests, and requests it can approve (other devices). */
    public List<Transfer> visible(UUID userId, UUID device) {
        requireActive(userId, device);
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM device_transfers WHERE user_id = ? AND expires_at > now() AND status IN ('REQUESTED','APPROVED','READY')
                AND (target_device_id = ? OR source_device_id = ? OR (status = 'REQUESTED' AND target_device_id <> ?)) ORDER BY created_at
                """, UUID.class, userId, device, device, device);
        return ids.stream().map(i -> load(userId, i)).toList();
    }

    @Transactional
    public Transfer approve(UUID userId, UUID sourceDevice, UUID id) {
        requireActive(userId, sourceDevice);
        Transfer t = load(userId, id);
        if (t.targetDeviceId().equals(sourceDevice)) {
            throw ApiException.badRequest("a device cannot send history to itself");
        }
        requireActive(userId, t.targetDeviceId());
        if (jdbc.update("UPDATE device_transfers SET status = 'APPROVED', source_device_id = ?, updated_at = now() WHERE id = ? AND status = 'REQUESTED'",
                sourceDevice, id) == 0) {
            throw ApiException.conflict("CONFLICT", "transfer is no longer pending");
        }
        log.info("history transfer approved transferId={} source={}", id, sourceDevice);
        return load(userId, id);
    }

    @Transactional
    public void decline(UUID userId, UUID device, UUID id) {
        requireActive(userId, device);
        load(userId, id);
        jdbc.update("UPDATE device_transfers SET status = 'DECLINED', updated_at = now() WHERE id = ? AND status IN ('REQUESTED','APPROVED')", id);
        jdbc.update("DELETE FROM device_transfer_chunks WHERE transfer_id = ?", id);
    }

    @Transactional
    public void putChunk(UUID userId, UUID device, UUID id, int seq, String data) {
        Transfer t = mustBeSource(userId, device, id);
        if (!"APPROVED".equals(t.status())) {
            throw ApiException.conflict("CONFLICT", "transfer is not accepting data");
        }
        if (seq < 0 || seq >= MAX_CHUNKS) {
            throw ApiException.badRequest("chunk index out of range");
        }
        byte[] bytes = b64(data, MAX_CHUNK_BYTES);
        if (bytes.length == 0 || bytes.length > MAX_CHUNK_BYTES) {
            throw ApiException.badRequest("chunk must be 1.." + MAX_CHUNK_BYTES + " bytes");
        }
        jdbc.update("INSERT INTO device_transfer_chunks (transfer_id, seq, data) VALUES (?, ?, ?) ON CONFLICT (transfer_id, seq) "
                + "DO UPDATE SET data = EXCLUDED.data", id, seq, bytes);
        Long total = jdbc.queryForObject("SELECT coalesce(sum(length(data)),0) FROM device_transfer_chunks WHERE transfer_id = ?", Long.class, id);
        if (total != null && total > MAX_TOTAL_BYTES) {
            throw ApiException.badRequest("history archive exceeds " + MAX_TOTAL_BYTES / (1024 * 1024) + " MB");
        }
        jdbc.update("UPDATE device_transfers SET total_bytes = ?, updated_at = now() WHERE id = ?", total, id);
    }

    @Transactional
    public Transfer complete(UUID userId, UUID device, UUID id, String manifest, String signature, int chunkCount) {
        Transfer t = mustBeSource(userId, device, id);
        if (!"APPROVED".equals(t.status())) {
            throw ApiException.conflict("CONFLICT", "transfer is not accepting data");
        }
        Integer have = jdbc.queryForObject("SELECT count(*) FROM device_transfer_chunks WHERE transfer_id = ? AND seq < ?", Integer.class, id, chunkCount);
        if (chunkCount < 1 || have == null || have != chunkCount) {
            throw ApiException.badRequest("missing chunks");
        }
        if (manifest == null || manifest.length() > 8192 || b64(signature, 128).length != 64) {
            throw ApiException.badRequest("manifest and a 64-byte signature are required");
        }
        jdbc.update("UPDATE device_transfers SET status = 'READY', manifest = ?, manifest_signature = ?, chunk_count = ?, updated_at = now() "
                + "WHERE id = ?", manifest, signature, chunkCount, id);
        log.info("history transfer ready transferId={} bytes={}", id, t.totalBytes());
        return load(userId, id);
    }

    public String getChunk(UUID userId, UUID device, UUID id, int seq) {
        requireActive(userId, device);
        Transfer t = load(userId, id);
        if (!t.targetDeviceId().equals(device)) {
            throw ApiException.notFound("transfer");      // only the requesting device may download
        }
        if (!"READY".equals(t.status())) {
            throw ApiException.conflict("CONFLICT", "transfer is not ready");
        }
        List<byte[]> d = jdbc.query("SELECT data FROM device_transfer_chunks WHERE transfer_id = ? AND seq = ?", (rs, i) -> rs.getBytes(1), id, seq);
        if (d.isEmpty()) {
            throw ApiException.notFound("chunk");
        }
        return Base64.getEncoder().encodeToString(d.get(0));
    }

    /** Target confirms import (or either side cancels): ciphertext is deleted immediately. */
    @Transactional
    public void finish(UUID userId, UUID device, UUID id) {
        requireActive(userId, device);
        Transfer t = load(userId, id);
        if (!device.equals(t.targetDeviceId()) && !device.equals(t.sourceDeviceId())) {
            throw ApiException.notFound("transfer");
        }
        jdbc.update("DELETE FROM device_transfer_chunks WHERE transfer_id = ?", id);
        jdbc.update("UPDATE device_transfers SET status = 'COMPLETED', manifest = NULL, updated_at = now() WHERE id = ?", id);
    }

    private Transfer mustBeSource(UUID userId, UUID device, UUID id) {
        requireActive(userId, device);
        Transfer t = load(userId, id);
        if (!device.equals(t.sourceDeviceId())) {
            throw ApiException.forbidden("only the approving device can upload");
        }
        return t;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void expire() {
        jdbc.update("DELETE FROM device_transfer_chunks c USING device_transfers t WHERE c.transfer_id = t.id "
                + "AND (t.expires_at < now() OR t.status IN ('DECLINED','COMPLETED'))");
        jdbc.update("UPDATE device_transfers SET status = 'EXPIRED' WHERE expires_at < now() AND status IN ('REQUESTED','APPROVED','READY')");
    }

    private static byte[] b64(String s, int maxBytes) {
        if (s == null || s.length() > maxBytes * 4 / 3 + 8) {
            throw ApiException.badRequest("value missing or too large");
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("value must be base64");
        }
    }

    static Map<String, Object> view(Transfer t) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", t.id());
        m.put("targetDeviceId", t.targetDeviceId());
        m.put("targetDeviceName", t.targetDeviceName());
        m.put("sourceDeviceId", t.sourceDeviceId());
        m.put("ephemeralPublicKey", t.ephemeralPublicKey());
        m.put("requestSignature", t.requestSignature());
        m.put("status", t.status());
        m.put("manifest", t.manifest());
        m.put("manifestSignature", t.manifestSignature());
        m.put("totalBytes", t.totalBytes());
        m.put("chunkCount", t.chunkCount());
        m.put("createdAt", t.createdAt());
        m.put("expiresAt", t.expiresAt());
        m.put("windowFrom", t.windowFrom());
        m.put("windowTo", t.windowTo());
        return m;
    }
}
