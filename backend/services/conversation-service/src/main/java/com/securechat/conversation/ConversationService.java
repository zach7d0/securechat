package com.securechat.conversation;

import com.securechat.contracts.EventType;
import com.securechat.contracts.events.ConversationCreated;
import com.securechat.contracts.events.GroupCommitted;
import com.securechat.contracts.events.GroupMembershipChanged;
import com.securechat.support.outbox.OutboxWriter;
import com.securechat.support.web.ApiException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative conversation and membership state. Group membership changes and their MLS commit
 * are applied in ONE transaction guarded by an epoch compare-and-set, so the membership the server
 * enforces and the cryptographic group can never diverge silently; each change emits versioned
 * events through the PostgreSQL outbox.
 */
@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final UserDirectory users;
    private final int maxMembers;

    ConversationService(JdbcTemplate jdbc, OutboxWriter outbox, UserDirectory users,
            @Value("${securechat.conversations.max-group-members:256}") int maxMembers) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.users = users;
        this.maxMembers = maxMembers;
    }

    public record Member(UUID userId, String role, Instant joinedAt, long joinedEpoch) {
    }

    public record GroupInfo(String description, String avatarUrl, String mlsGroupId, long epoch, long groupVersion) {
    }

    public record Conversation(UUID id, String type, String displayName, UUID createdBy, long membershipVersion,
            Instant createdAt, Instant updatedAt, List<Member> members, GroupInfo group, String myRole, Long myJoinedEpoch,
            Integer disappearingSeconds) {
    }

    /** Allowed disappearing-message timers (seconds): 5/15 min, 1/3/10 h, 1/3/7/30 days. 0 = off. */
    static final Set<Integer> TIMERS = Set.of(300, 900, 3600, 10800, 36000, 86400, 259200, 604800, 2592000);

    // ------------------------------------------------------------------ creation

    /** Returns the single direct conversation for the pair, creating it race-free if needed. */
    @Transactional
    public Conversation direct(UUID me, UUID peer) {
        if (me.equals(peer)) {
            throw ApiException.badRequest("cannot start a conversation with yourself");
        }
        if (!users.existing(List.of(peer)).contains(peer)) {
            throw ApiException.notFound("user");
        }
        // PostgreSQL orders uuid by unsigned bytes; java.util.UUID.compareTo compares SIGNED longs.
        // The canonical lowercase string order equals the unsigned byte order, so use it.
        UUID low = pairOrder(me, peer) < 0 ? me : peer;
        UUID high = low.equals(me) ? peer : me;
        Optional<UUID> existing = findPair(low, high);
        if (existing.isPresent()) {
            return get(existing.get(), me);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO conversations (id, conversation_type, created_by) VALUES (?, 'DIRECT', ?)", id, me);
        int won = jdbc.update("INSERT INTO direct_conversation_pairs (user_low, user_high, conversation_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (user_low, user_high) DO NOTHING", low, high, id);
        if (won == 0) {
            // Lost the race: the concurrent transaction committed the pair first (our INSERT waited on the
            // unique index). Discard our unused conversation row and return the winner.
            jdbc.update("DELETE FROM conversations WHERE id = ?", id);
            return get(findPair(low, high).orElseThrow(), me);
        }
        jdbc.update("INSERT INTO conversation_members (conversation_id, user_id, role) VALUES (?, ?, 'MEMBER'), (?, ?, 'MEMBER')",
                id, me, id, peer);
        outbox.write(EventType.CONVERSATION_CREATED, id.toString(), id.toString(),
                new ConversationCreated(id, "DIRECT", List.of(me, peer), me, Instant.now()));
        log.info("direct conversation created conversationId={}", id);
        return get(id, me);
    }

    static int pairOrder(UUID a, UUID b) {
        return a.toString().compareTo(b.toString());
    }

    private Optional<UUID> findPair(UUID low, UUID high) {
        return jdbc.queryForList("SELECT conversation_id FROM direct_conversation_pairs WHERE user_low = ? AND user_high = ?",
                UUID.class, low, high).stream().findFirst();
    }

    /** Creates a group with the creator as OWNER at MLS epoch 0; members are added by commit. */
    @Transactional
    public Conversation createGroup(UUID me, String name, String description) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 150) {
            throw ApiException.badRequest("name must be 1-150 characters");
        }
        if (description != null && description.length() > 1000) {
            throw ApiException.badRequest("description too long");
        }
        UUID id = UUID.randomUUID();
        byte[] gid = new byte[32];
        RANDOM.nextBytes(gid);
        jdbc.update("INSERT INTO conversations (id, conversation_type, display_name, created_by) VALUES (?, 'GROUP', ?, ?)", id, n, me);
        jdbc.update("INSERT INTO group_metadata (conversation_id, group_description, mls_group_id) VALUES (?, ?, ?)",
                id, description, Base64.getEncoder().encodeToString(gid));
        jdbc.update("INSERT INTO conversation_members (conversation_id, user_id, role) VALUES (?, ?, 'OWNER')", id, me);
        outbox.write(EventType.CONVERSATION_CREATED, id.toString(), id.toString(),
                new ConversationCreated(id, "GROUP", List.of(me), me, Instant.now()));
        log.info("group created conversationId={}", id);
        return get(id, me);
    }

    // ------------------------------------------------------------------ reads

    public Conversation get(UUID id, UUID me) {
        Conversation c = load(id).orElseThrow(() -> ApiException.notFound("conversation"));
        Member mine = c.members().stream().filter(m -> m.userId().equals(me)).findFirst()
                .orElseThrow(() -> ApiException.notFound("conversation"));
        return new Conversation(c.id(), c.type(), c.displayName(), c.createdBy(), c.membershipVersion(), c.createdAt(),
                c.updatedAt(), c.members(), c.group(), mine.role(), mine.joinedEpoch(), c.disappearingSeconds());
    }

    public Optional<Conversation> load(UUID id) {
        var rows = jdbc.query("""
                SELECT c.id, c.conversation_type, c.display_name, c.created_by, c.membership_version, c.created_at, c.updated_at,
                       g.group_description, g.avatar_url, g.mls_group_id, g.epoch, g.group_version, c.disappearing_seconds
                FROM conversations c LEFT JOIN group_metadata g ON g.conversation_id = c.id
                WHERE c.id = ? AND c.status = 'ACTIVE'
                """, (rs, i) -> new Conversation(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getLong(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), null,
                rs.getString(10) == null ? null : new GroupInfo(rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11),
                        rs.getLong(12)), null, null, (Integer) rs.getObject(13)), id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Conversation c = rows.get(0);
        return Optional.of(new Conversation(c.id(), c.type(), c.displayName(), c.createdBy(), c.membershipVersion(),
                c.createdAt(), c.updatedAt(), members(id), c.group(), null, null, c.disappearingSeconds()));
    }

    public List<Member> members(UUID id) {
        return jdbc.query("SELECT user_id, role, joined_at, joined_epoch FROM conversation_members "
                        + "WHERE conversation_id = ? AND left_at IS NULL ORDER BY joined_at, user_id",
                (rs, i) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getLong(4)), id);
    }

    public List<Conversation> mine(UUID me) {
        List<UUID> ids = jdbc.queryForList("SELECT m.conversation_id FROM conversation_members m JOIN conversations c ON c.id = m.conversation_id "
                + "WHERE m.user_id = ? AND m.left_at IS NULL AND c.status = 'ACTIVE' ORDER BY c.updated_at DESC LIMIT 500", UUID.class, me);
        List<Conversation> out = new ArrayList<>();
        for (UUID id : ids) {
            out.add(get(id, me));
        }
        return out;
    }

    public List<UUID> peers(UUID userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT o.user_id FROM conversation_members m
                JOIN conversation_members o ON o.conversation_id = m.conversation_id AND o.left_at IS NULL
                WHERE m.user_id = ? AND m.left_at IS NULL AND o.user_id <> ?
                """, UUID.class, userId, userId);
    }

    // ------------------------------------------------------------------ group management

    private Conversation requireGroup(UUID id, UUID me) {
        Conversation c = get(id, me);
        if (!"GROUP".equals(c.type())) {
            throw ApiException.badRequest("not a group");
        }
        return c;
    }

    private static void requireAdmin(Conversation c) {
        if (!"OWNER".equals(c.myRole()) && !"ADMIN".equals(c.myRole())) {
            throw ApiException.forbidden("only group owners and admins can do this");
        }
    }

    /** Any current member turns disappearing messages on (one of TIMERS) or off (0). Direct and group chats. */
    @Transactional
    public Conversation setDisappearing(UUID id, UUID me, int seconds) {
        get(id, me);   // 404 for non-members
        if (seconds != 0 && !TIMERS.contains(seconds)) {
            throw ApiException.badRequest("seconds must be 0 (off) or one of " + new java.util.TreeSet<>(TIMERS));
        }
        jdbc.update("UPDATE conversations SET disappearing_seconds = ?, updated_at = now() WHERE id = ?", seconds == 0 ? null : seconds, id);
        log.info("disappearing messages set conversationId={} seconds={}", id, seconds);
        return get(id, me);
    }

    @Transactional
    public Conversation updateGroup(UUID id, UUID me, String name, String description) {
        Conversation c = requireGroup(id, me);
        requireAdmin(c);
        if (name != null && (name.isBlank() || name.length() > 150)) {
            throw ApiException.badRequest("name must be 1-150 characters");
        }
        jdbc.update("UPDATE conversations SET display_name = COALESCE(?, display_name), updated_at = now() WHERE id = ?",
                name == null ? null : name.strip(), id);
        jdbc.update("UPDATE group_metadata SET group_description = COALESCE(?, group_description), group_version = group_version + 1, "
                + "updated_at = now() WHERE conversation_id = ?", description, id);
        return get(id, me);
    }

    public record Welcome(UUID deviceId, UUID keyPackageId, String welcome) {
    }

    public record CommitRequest(String commit, long epoch, List<Welcome> welcomes) {
    }

    public Conversation addMembers(UUID id, UUID me, UUID myDevice, List<UUID> userIds, CommitRequest commit) {
        return addMembers(id, me, myDevice, userIds, commit, null);
    }

    /**
     * Adds users (OWNER/ADMIN). The client-built MLS commit and per-device Welcomes are stored atomically.
     * {@code role} is the role the new members get: MEMBER (default) or ADMIN, and only the OWNER may add admins.
     */
    @Transactional
    public Conversation addMembers(UUID id, UUID me, UUID myDevice, List<UUID> userIds, CommitRequest commit, String role) {
        Conversation c = requireGroup(id, me);
        requireAdmin(c);
        String newRole = role == null || role.isBlank() ? "MEMBER" : role.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ADMIN", "MEMBER").contains(newRole)) {
            throw ApiException.badRequest("role must be ADMIN or MEMBER");
        }
        if ("ADMIN".equals(newRole) && !"OWNER".equals(c.myRole())) {
            throw ApiException.forbidden("only the owner can add admins");
        }
        Set<UUID> add = new LinkedHashSet<>(userIds == null ? List.of() : userIds);
        add.remove(me);
        c.members().forEach(m -> add.remove(m.userId()));
        if (add.isEmpty()) {
            throw ApiException.badRequest("no new members");
        }
        if (c.members().size() + add.size() > maxMembers) {
            throw ApiException.conflict("CONFLICT", "group member limit reached");
        }
        List<UUID> existing = users.existing(new ArrayList<>(add));
        if (existing.size() != add.size()) {
            throw ApiException.notFound("user");
        }
        long newEpoch = applyCommit(id, me, myDevice, commit, "ADD_MEMBERS", add);
        for (UUID u : add) {
            jdbc.update("""
                    INSERT INTO conversation_members (conversation_id, user_id, role, joined_epoch) VALUES (?, ?, ?, ?)
                    ON CONFLICT (conversation_id, user_id) DO UPDATE SET left_at = NULL, role = EXCLUDED.role, joined_at = now(),
                      joined_epoch = EXCLUDED.joined_epoch
                    """, id, u, newRole, newEpoch);
        }
        long version = bumpVersion(id);
        List<UUID> now = members(id).stream().map(Member::userId).toList();
        outbox.write(EventType.GROUP_MEMBER_ADDED, id.toString(), id.toString(),
                new GroupMembershipChanged(id, List.copyOf(add), now, version, newEpoch, me, Instant.now()));
        emitCommit(id, newEpoch, myDevice, now);
        log.info("members added conversationId={} count={} role={} epoch={}", id, add.size(), newRole, newEpoch);
        return get(id, me);
    }

    /** Removes a member with the commit that removes their leaves (TreeKEM re-keys the group). */
    @Transactional
    public Conversation removeMember(UUID id, UUID me, UUID myDevice, UUID target, CommitRequest commit) {
        Conversation c = requireGroup(id, me);
        Member t = c.members().stream().filter(m -> m.userId().equals(target)).findFirst()
                .orElseThrow(() -> ApiException.notFound("member"));
        if (!me.equals(target)) {
            requireAdmin(c);
            if ("OWNER".equals(t.role())) {
                throw ApiException.forbidden("the owner cannot be removed");
            }
            if ("ADMIN".equals(t.role()) && !"OWNER".equals(c.myRole())) {
                throw ApiException.forbidden("only the owner can remove admins");
            }
        }
        long newEpoch = applyCommit(id, me, myDevice, commit, "REMOVE_MEMBER", Set.of());
        markLeft(id, target, me, newEpoch);
        log.info("member removed conversationId={} epoch={}", id, newEpoch);
        return me.equals(target) ? c : get(id, me);
    }

    /**
     * Leave without a commit (the leaver cannot remove its own leaves in MLS). Remaining members'
     * clients notice the stale leaves and commit their removal (device reconciliation).
     */
    @Transactional
    public void leave(UUID id, UUID me) {
        Conversation c = requireGroup(id, me);
        if ("OWNER".equals(c.myRole()) && c.members().size() > 1) {
            UUID heir = c.members().stream().filter(m -> !m.userId().equals(me))
                    .sorted((a, b) -> Integer.compare(rank(a.role()), rank(b.role()))).findFirst().orElseThrow().userId();
            jdbc.update("UPDATE conversation_members SET role = 'OWNER' WHERE conversation_id = ? AND user_id = ?", id, heir);
        }
        markLeft(id, me, me, c.group().epoch());
    }

    private static int rank(String role) {
        return switch (role) {
            case "OWNER" -> 0;
            case "ADMIN" -> 1;
            default -> 2;
        };
    }

    private void markLeft(UUID id, UUID target, UUID actor, long epoch) {
        jdbc.update("UPDATE conversation_members SET left_at = now() WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL", id, target);
        jdbc.update("UPDATE mls_welcomes SET consumed_at = now() WHERE conversation_id = ? AND recipient_user_id = ? AND consumed_at IS NULL",
                id, target);
        long version = bumpVersion(id);
        List<UUID> now = members(id).stream().map(Member::userId).toList();
        outbox.write(EventType.GROUP_MEMBER_REMOVED, id.toString(), id.toString(),
                new GroupMembershipChanged(id, List.of(target), now, version, epoch, actor, Instant.now()));
    }

    @Transactional
    public Conversation setRole(UUID id, UUID me, UUID target, String role) {
        Conversation c = requireGroup(id, me);
        if (!"OWNER".equals(c.myRole())) {
            throw ApiException.forbidden("only the owner can change roles");
        }
        if (!Set.of("ADMIN", "MEMBER").contains(role) || me.equals(target)) {
            throw ApiException.badRequest("role must be ADMIN or MEMBER and not your own");
        }
        if (jdbc.update("UPDATE conversation_members SET role = ? WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                role, id, target) == 0) {
            throw ApiException.notFound("member");
        }
        bumpVersion(id);
        return get(id, me);
    }

    /**
     * Commit without a membership change: device additions/removals for existing members, or a key
     * update. Declared Welcome recipients must be active devices of current members.
     */
    @Transactional
    public long commit(UUID id, UUID me, UUID myDevice, CommitRequest commit, String kind) {
        Conversation c = requireGroup(id, me);
        if (!Set.of("DEVICE_SYNC", "UPDATE").contains(kind)) {
            throw ApiException.badRequest("kind must be DEVICE_SYNC or UPDATE");
        }
        long e = applyCommit(id, me, myDevice, commit, kind, c.members().stream().map(Member::userId).collect(java.util.stream.Collectors.toSet()));
        emitCommit(id, e, myDevice, c.members().stream().map(Member::userId).toList());
        return e;
    }

    /**
     * Epoch compare-and-set: the commit must have been built on the current epoch; otherwise 409
     * EPOCH_CONFLICT (the client fetches newer commits, re-applies and rebuilds).
     */
    private long applyCommit(UUID id, UUID me, UUID myDevice, CommitRequest req, String kind, Set<UUID> welcomeUsers) {
        if (myDevice == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REQUIRED", "a device-bound session is required");
        }
        if (req == null || req.commit() == null || req.commit().isEmpty()) {
            throw ApiException.badRequest("commit is required");
        }
        byte[] commitBytes = decode("commit", req.commit(), 256 * 1024);
        int n = jdbc.update("UPDATE group_metadata SET epoch = epoch + 1, updated_at = now() WHERE conversation_id = ? AND epoch = ?",
                id, req.epoch());
        if (n == 0) {
            Long current = jdbc.queryForObject("SELECT epoch FROM group_metadata WHERE conversation_id = ?", Long.class, id);
            throw new ApiException(HttpStatus.CONFLICT, "EPOCH_CONFLICT", "group is at epoch " + current,
                    Map.of("currentEpoch", current == null ? -1 : current));
        }
        jdbc.update("INSERT INTO mls_commits (conversation_id, epoch, commit_message, sender_user_id, sender_device_id, kind) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, req.epoch(), commitBytes, me, myDevice, kind);
        long newEpoch = req.epoch() + 1;
        List<Welcome> ws = req.welcomes() == null ? List.of() : req.welcomes();
        if (!ws.isEmpty()) {
            List<UUID> wu = new ArrayList<>(welcomeUsers);
            Map<UUID, List<UUID>> devices = wu.isEmpty() ? Map.of() : users.activeDevices(wu);
            Map<UUID, UUID> deviceOwner = new java.util.HashMap<>();
            devices.forEach((u, ds) -> ds.forEach(d -> deviceOwner.put(d, u)));
            Set<UUID> seen = new HashSet<>();
            for (Welcome w : ws) {
                UUID owner = deviceOwner.get(w.deviceId());
                if (owner == null || !seen.add(w.deviceId())) {
                    throw ApiException.badRequest("welcome recipient " + w.deviceId() + " is not an active device of an added member");
                }
                jdbc.update("INSERT INTO mls_welcomes (id, conversation_id, recipient_user_id, recipient_device_id, key_package_id, "
                        + "welcome, epoch) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, owner, w.deviceId(), w.keyPackageId(),
                        decode("welcome", w.welcome(), 512 * 1024), newEpoch);
            }
        }
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE id = ?", id);
        return newEpoch;
    }

    private void emitCommit(UUID id, long epoch, UUID device, List<UUID> members) {
        outbox.write(EventType.GROUP_COMMIT, id.toString(), id + ":" + epoch, new GroupCommitted(id, epoch, device, members, Instant.now()));
    }

    private long bumpVersion(UUID id) {
        Long v = jdbc.queryForObject("UPDATE conversations SET membership_version = membership_version + 1, updated_at = now() "
                + "WHERE id = ? RETURNING membership_version", Long.class, id);
        return v == null ? 0 : v;
    }

    private static byte[] decode(String field, String b64, int max) {
        try {
            byte[] b = Base64.getDecoder().decode(b64);
            if (b.length == 0 || b.length > max) {
                throw ApiException.badRequest(field + " size out of range");
            }
            return b;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(field + " must be base64");
        }
    }

    public record CommitView(long epoch, String commit, UUID senderUserId, UUID senderDeviceId, String kind, Instant createdAt) {
    }

    /** Commits with epoch >= fromEpoch, in order. Current members only. */
    public List<CommitView> commits(UUID id, UUID me, long fromEpoch, int limit) {
        requireGroup(id, me);
        return jdbc.query("SELECT epoch, commit_message, sender_user_id, sender_device_id, kind, created_at FROM mls_commits "
                        + "WHERE conversation_id = ? AND epoch >= ? ORDER BY epoch LIMIT ?",
                (rs, i) -> new CommitView(rs.getLong(1), Base64.getEncoder().encodeToString(rs.getBytes(2)), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), rs.getString(5), rs.getTimestamp(6).toInstant()),
                id, fromEpoch, Math.min(Math.max(limit, 1), 200));
    }

    public record WelcomeView(UUID id, UUID conversationId, UUID keyPackageId, String welcome, long epoch, Instant createdAt) {
    }

    public List<WelcomeView> welcomes(UUID me, UUID myDevice) {
        return jdbc.query("""
                SELECT w.id, w.conversation_id, w.key_package_id, w.welcome, w.epoch, w.created_at FROM mls_welcomes w
                JOIN conversation_members m ON m.conversation_id = w.conversation_id AND m.user_id = w.recipient_user_id AND m.left_at IS NULL
                WHERE w.recipient_device_id = ? AND w.recipient_user_id = ? AND w.consumed_at IS NULL ORDER BY w.created_at
                """, (rs, i) -> new WelcomeView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                Base64.getEncoder().encodeToString(rs.getBytes(4)), rs.getLong(5), rs.getTimestamp(6).toInstant()), myDevice, me);
    }

    @Transactional
    public void consumeWelcome(UUID me, UUID myDevice, UUID welcomeId) {
        jdbc.update("UPDATE mls_welcomes SET consumed_at = now() WHERE id = ? AND recipient_device_id = ? AND recipient_user_id = ?",
                welcomeId, myDevice, me);
    }

    public record Authorization(boolean member, String type, String role, long membershipVersion, Long epoch, Instant createdAt,
            List<UUID> memberUserIds) {
    }

    /** Internal: is {@code userId} an active member? Used by Message/Fanout (they fail closed on errors). */
    public Authorization authorize(UUID id, UUID userId) {
        Optional<Conversation> c = load(id);
        if (c.isEmpty()) {
            return new Authorization(false, null, null, 0, null, null, List.of());
        }
        Optional<Member> m = c.get().members().stream().filter(x -> x.userId().equals(userId)).findFirst();
        return new Authorization(m.isPresent(), c.get().type(), m.map(Member::role).orElse(null), c.get().membershipVersion(),
                c.get().group() == null ? null : c.get().group().epoch(), c.get().createdAt(),
                c.get().members().stream().map(Member::userId).toList());
    }

    public Map<String, Long> stats() {
        return Map.of(
                "conversations", count("SELECT count(*) FROM conversations"),
                "groups", count("SELECT count(*) FROM conversations WHERE conversation_type = 'GROUP'"),
                "direct", count("SELECT count(*) FROM conversations WHERE conversation_type = 'DIRECT'"));
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
