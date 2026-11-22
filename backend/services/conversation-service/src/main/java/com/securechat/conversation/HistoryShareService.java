package com.securechat.conversation;

import com.securechat.support.web.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ciphertext relay for group history shared with new members. Only a current OWNER/ADMIN of the group
 * creates a share and only the creating device uploads; any current member may download a READY share
 * (useless without the key, which is delivered end-to-end inside the group's MLS epoch).
 */
@Service
public class HistoryShareService {
    private static final Logger log = LoggerFactory.getLogger(HistoryShareService.class);
    static final int MAX_CHUNK_BYTES = 192 * 1024;
    static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;
    static final int MAX_CHUNKS = 200;
    static final int MAX_OPEN_PER_USER = 3;
    static final Duration TTL = Duration.ofDays(30);
    static final Duration GRANT_TTL = Duration.ofDays(7);
    static final java.util.Set<String> SCOPES = java.util.Set.of("last100", "10m", "1h", "10h", "1d", "7d", "30d", "all");
    private final JdbcTemplate jdbc;
    private final ConversationService conversations;

    HistoryShareService(JdbcTemplate jdbc, ConversationService conversations) {
        this.jdbc = jdbc;
        this.conversations = conversations;
    }

    record Share(UUID id, UUID conversationId, UUID createdByUser, UUID createdByDevice, String status, long totalBytes) {
    }

    private Share load(UUID conversationId, UUID shareId) {
        return jdbc.query("SELECT id, conversation_id, created_by_user, created_by_device, status, total_bytes FROM history_shares "
                        + "WHERE id = ? AND conversation_id = ? AND expires_at > now()",
                (rs, i) -> new Share(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getLong(6)), shareId, conversationId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("history share"));
    }

    /** OWNER/ADMIN of the group (the same people who can add members). */
    private void requireGroupAdmin(UUID conversationId, UUID me) {
        ConversationService.Conversation c = conversations.get(conversationId, me);   // 404 for non-members
        if (!"GROUP".equals(c.type())) {
            throw ApiException.badRequest("history can only be shared in groups");
        }
        if (!"OWNER".equals(c.myRole()) && !"ADMIN".equals(c.myRole())) {
            throw ApiException.forbidden("only group owners and admins can share history");
        }
    }

    private Share mustBeCreator(UUID conversationId, UUID me, UUID device, UUID shareId) {
        conversations.get(conversationId, me);   // still a current member (the claimer need not be an admin)
        Share s = load(conversationId, shareId);
        if (!s.createdByUser().equals(me) || !s.createdByDevice().equals(device)) {
            throw ApiException.forbidden("only the device that created the share can upload");
        }
        if (!"UPLOADING".equals(s.status())) {
            throw ApiException.conflict("CONFLICT", "share is already complete");
        }
        return s;
    }

    @Transactional
    public UUID create(UUID conversationId, UUID me, UUID device) {
        requireGroupAdmin(conversationId, me);
        return newShare(conversationId, me, device);
    }

    private UUID newShare(UUID conversationId, UUID me, UUID device) {
        Integer open = jdbc.queryForObject("SELECT count(*) FROM history_shares WHERE created_by_user = ? AND status = 'UPLOADING' "
                + "AND expires_at > now()", Integer.class, me);
        if (open != null && open >= MAX_OPEN_PER_USER) {
            throw ApiException.conflict("CONFLICT", "too many unfinished history shares; try again later");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO history_shares (id, conversation_id, created_by_user, created_by_device, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, conversationId, me, device, Timestamp.from(Instant.now().plus(TTL)));
        log.info("history share created conversationId={} shareId={} by={}", conversationId, id, me);
        return id;
    }

    // ------------------------------------------------------------------ grants (admin authorises, any member fulfils)

    @Transactional
    public UUID grant(UUID conversationId, UUID me, String scope) {
        requireGroupAdmin(conversationId, me);
        if (scope == null || !SCOPES.contains(scope)) {
            throw ApiException.badRequest("scope must be one of " + SCOPES);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO history_grants (id, conversation_id, granted_by_user, scope, expires_at) VALUES (?, ?, ?, ?, ?)",
                id, conversationId, me, scope, Timestamp.from(Instant.now().plus(GRANT_TTL)));
        log.info("history grant created conversationId={} grantId={} scope={}", conversationId, id, scope);
        return id;
    }

    public record GrantView(UUID id, String scope, UUID grantedBy, boolean claimed, Instant expiresAt) {
    }

    public GrantView grantStatus(UUID conversationId, UUID me, UUID grantId) {
        conversations.get(conversationId, me);
        return jdbc.query("SELECT id, scope, granted_by_user, claimed_by_device IS NOT NULL, expires_at FROM history_grants "
                        + "WHERE id = ? AND conversation_id = ? AND expires_at > now()",
                (rs, i) -> new GrantView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getBoolean(4),
                        rs.getTimestamp(5).toInstant()), grantId, conversationId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("history grant"));
    }

    /** First current member device to claim fulfils the grant; everyone else gets 409. */
    @Transactional
    public UUID claim(UUID conversationId, UUID me, UUID device, UUID grantId) {
        grantStatus(conversationId, me, grantId);
        int n = jdbc.update("UPDATE history_grants SET claimed_by_user = ?, claimed_by_device = ?, claimed_at = now() "
                + "WHERE id = ? AND conversation_id = ? AND claimed_by_device IS NULL AND expires_at > now()", me, device, grantId, conversationId);
        if (n == 0) {
            throw ApiException.conflict("ALREADY_CLAIMED", "another member is already sharing this history");
        }
        UUID share = newShare(conversationId, me, device);
        jdbc.update("UPDATE history_grants SET share_id = ? WHERE id = ?", share, grantId);
        log.info("history grant claimed conversationId={} grantId={} by={} shareId={}", conversationId, grantId, me, share);
        return share;
    }

    @Transactional
    public void putChunk(UUID conversationId, UUID me, UUID device, UUID shareId, int seq, String data) {
        Share s = mustBeCreator(conversationId, me, device, shareId);
        if (seq < 0 || seq >= MAX_CHUNKS) {
            throw ApiException.badRequest("seq must be 0-" + (MAX_CHUNKS - 1));
        }
        byte[] bytes = b64(data, MAX_CHUNK_BYTES);
        if (bytes.length == 0 || bytes.length > MAX_CHUNK_BYTES) {
            throw ApiException.badRequest("chunk must be 1-" + MAX_CHUNK_BYTES + " bytes");
        }
        if (s.totalBytes() + bytes.length > MAX_TOTAL_BYTES) {
            throw ApiException.badRequest("history share exceeds " + MAX_TOTAL_BYTES + " bytes");
        }
        try {
            jdbc.update("INSERT INTO history_share_chunks (share_id, seq, data) VALUES (?, ?, ?)", shareId, seq, bytes);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CONFLICT", "chunk " + seq + " already uploaded");
        }
        jdbc.update("UPDATE history_shares SET total_bytes = total_bytes + ? WHERE id = ?", bytes.length, shareId);
    }

    @Transactional
    public void complete(UUID conversationId, UUID me, UUID device, UUID shareId, int chunkCount) {
        mustBeCreator(conversationId, me, device, shareId);
        Integer have = jdbc.queryForObject("SELECT count(*) FROM history_share_chunks WHERE share_id = ? AND seq < ?",
                Integer.class, shareId, chunkCount);
        Integer all = jdbc.queryForObject("SELECT count(*) FROM history_share_chunks WHERE share_id = ?", Integer.class, shareId);
        if (chunkCount < 1 || have == null || have != chunkCount || !have.equals(all)) {
            throw ApiException.badRequest("chunks 0.." + (chunkCount - 1) + " must all be uploaded (and nothing else)");
        }
        jdbc.update("UPDATE history_shares SET status = 'READY', chunk_count = ? WHERE id = ?", chunkCount, shareId);
        log.info("history share ready conversationId={} shareId={} chunks={}", conversationId, shareId, chunkCount);
    }

    /** Any current member (a removed member gets 404 via {@link ConversationService#get}). */
    public String chunk(UUID conversationId, UUID me, UUID shareId, int seq) {
        conversations.get(conversationId, me);
        Share s = load(conversationId, shareId);
        if (!"READY".equals(s.status())) {
            throw ApiException.notFound("history share");
        }
        byte[] data = jdbc.query("SELECT data FROM history_share_chunks WHERE share_id = ? AND seq = ?",
                (rs, i) -> rs.getBytes(1), shareId, seq).stream().findFirst().orElseThrow(() -> ApiException.notFound("chunk"));
        return Base64.getEncoder().encodeToString(data);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void purgeExpired() {
        jdbc.update("DELETE FROM history_grants WHERE expires_at < now() - interval '30 days'");
        int n = jdbc.update("DELETE FROM history_shares WHERE expires_at < now()");
        if (n > 0) {
            log.info("purged {} expired history shares", n);
        }
    }

    private static byte[] b64(String s, int maxBytes) {
        if (s == null || s.length() > maxBytes * 4 / 3 + 8) {
            throw ApiException.badRequest("data missing or too large");
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("data must be base64");
        }
    }
}
