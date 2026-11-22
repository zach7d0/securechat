package com.securechat.user;

import com.securechat.support.web.ApiException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profiles, username search and contacts. */
@Service
public class ProfileService {
    private final JdbcTemplate jdbc;

    ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Profile(UUID userId, String username, String displayName, String avatarUrl, String bio,
            Instant createdAt) {
    }

    static final RowMapper<Profile> MAPPER = (rs, i) -> new Profile(rs.getObject("user_id", UUID.class),
            rs.getString("username"), rs.getString("display_name"), rs.getString("avatar_url"), rs.getString("bio"),
            rs.getTimestamp("created_at").toInstant());

    /** Idempotent upsert from chat.user.registered (or lazy creation from the token). */
    @Transactional
    public void ensure(UUID userId, String username, String displayName) {
        jdbc.update("INSERT INTO user_profiles (user_id, username, display_name) VALUES (?, ?, ?) ON CONFLICT (user_id) DO NOTHING",
                userId, username, displayName == null || displayName.isBlank() ? username : displayName);
    }

    /**
     * Applies chat.user.registered. The client usually fetches its profile right after registering, so the
     * profile may already exist, lazily created with the username as display name. In that case — and only
     * if the user has not edited it since (updated_at = created_at) — take the display name chosen at
     * registration. A name the user changed later is never overwritten. Idempotent.
     */
    @Transactional
    public void applyRegistration(UUID userId, String username, String displayName) {
        String name = displayName == null || displayName.isBlank() ? username : displayName.trim();
        jdbc.update("""
                INSERT INTO user_profiles (user_id, username, display_name) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET display_name = EXCLUDED.display_name
                WHERE user_profiles.display_name = user_profiles.username AND user_profiles.updated_at = user_profiles.created_at
                """, userId, username, name.length() > 100 ? name.substring(0, 100) : name);
    }

    public Optional<Profile> find(UUID userId) {
        return jdbc.query("SELECT * FROM user_profiles WHERE user_id = ?", MAPPER, userId).stream().findFirst();
    }

    public Profile me(UUID userId, String usernameFromToken) {
        return find(userId).orElseGet(() -> {
            if (usernameFromToken == null) {
                throw ApiException.notFound("profile");
            }
            ensure(userId, usernameFromToken, usernameFromToken);
            return find(userId).orElseThrow();
        });
    }

    @Transactional
    public Profile update(UUID userId, String displayName, String bio, String avatarUrl) {
        Profile p = find(userId).orElseThrow(() -> ApiException.notFound("profile"));
        String name = displayName == null ? p.displayName() : displayName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest("displayName must be 1-100 characters");
        }
        if (bio != null && bio.length() > 500) {
            throw ApiException.badRequest("bio must be at most 500 characters");
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            URI u = URI.create(avatarUrl);
            if (!"https".equals(u.getScheme()) || avatarUrl.length() > 500) {
                throw ApiException.badRequest("avatarUrl must be an https URL");
            }
        }
        jdbc.update("UPDATE user_profiles SET display_name = ?, bio = ?, avatar_url = ?, updated_at = now() WHERE user_id = ?",
                name, bio == null ? p.bio() : bio, avatarUrl == null ? p.avatarUrl() : (avatarUrl.isBlank() ? null : avatarUrl),
                userId);
        return find(userId).orElseThrow();
    }

    /** Prefix search over username and display name; LIKE metacharacters are escaped. */
    public List<Profile> search(String q, UUID excludeUserId, int limit) {
        String s = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        if (s.length() < 2 || s.length() > 64) {
            throw ApiException.badRequest("query must be 2-64 characters");
        }
        String like = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("""
                SELECT * FROM user_profiles WHERE (username LIKE ? OR lower(display_name) LIKE ?) AND user_id <> ?
                ORDER BY username LIMIT ?
                """, MAPPER, like, like, excludeUserId, Math.min(Math.max(limit, 1), 20));
    }

    public List<Profile> batch(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > 200) {
            throw ApiException.badRequest("at most 200 ids");
        }
        return jdbc.query("SELECT * FROM user_profiles WHERE user_id = ANY (?)", MAPPER, (Object) ids.toArray(UUID[]::new));
    }

    public List<UUID> existing(List<UUID> ids) {
        return batch(ids).stream().map(Profile::userId).toList();
    }

    @Transactional
    public void addContact(UUID owner, UUID contact) {
        if (owner.equals(contact)) {
            throw ApiException.badRequest("cannot add yourself");
        }
        find(contact).orElseThrow(() -> ApiException.notFound("user"));
        jdbc.update("INSERT INTO contacts (owner_user_id, contact_user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", owner, contact);
    }

    @Transactional
    public void removeContact(UUID owner, UUID contact) {
        jdbc.update("DELETE FROM contacts WHERE owner_user_id = ? AND contact_user_id = ?", owner, contact);
    }

    public List<Profile> contacts(UUID owner) {
        return jdbc.query("SELECT p.* FROM contacts c JOIN user_profiles p ON p.user_id = c.contact_user_id "
                + "WHERE c.owner_user_id = ? ORDER BY p.username", MAPPER, owner);
    }

    public record AdminPage(List<Profile> items, long total) {
    }

    /** Admin listing (via Admin Service internal API): paginated, optional text filter. */
    public AdminPage adminList(String q, int page, int size) {
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * sz;
        if (q == null || q.isBlank()) {
            Long total = jdbc.queryForObject("SELECT count(*) FROM user_profiles", Long.class);
            return new AdminPage(jdbc.query("SELECT * FROM user_profiles ORDER BY created_at DESC LIMIT ? OFFSET ?", MAPPER,
                    sz, offset), total == null ? 0 : total);
        }
        String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = " WHERE username LIKE ? OR lower(display_name) LIKE ? OR user_id::text = ?";
        Long total = jdbc.queryForObject("SELECT count(*) FROM user_profiles" + where, Long.class, like, like, q.trim());
        return new AdminPage(jdbc.query("SELECT * FROM user_profiles" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                MAPPER, like, like, q.trim(), sz, offset), total == null ? 0 : total);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM user_profiles", Long.class);
        return n == null ? 0 : n;
    }
}
