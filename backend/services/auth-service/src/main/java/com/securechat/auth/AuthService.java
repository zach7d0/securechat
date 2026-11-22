package com.securechat.auth;

import com.securechat.contracts.EventType;
import com.securechat.contracts.events.UserRegistered;
import com.securechat.contracts.events.UserStatusChanged;
import com.securechat.support.kafka.RetryableDependencyException;
import com.securechat.support.outbox.OutboxWriter;
import com.securechat.support.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, login, refresh rotation with reuse detection, logout and device binding. */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,32}");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");
    /** Hash of a random password, used to equalise timing for unknown users. */
    private final String dummyHash;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final OutboxWriter outbox;
    private final LoginRateLimiter limiter;
    private final DeviceDirectory devices;
    private final AuthProperties props;

    AuthService(JdbcTemplate jdbc, PasswordEncoder passwords, TokenService tokens, OutboxWriter outbox,
            LoginRateLimiter limiter, DeviceDirectory devices, AuthProperties props) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.outbox = outbox;
        this.limiter = limiter;
        this.devices = devices;
        this.props = props;
        this.dummyHash = passwords.encode(TokenService.newRefreshToken());
    }

    public record Session(UUID userId, String username, UUID deviceId, String accessToken, Instant accessTokenExpiresAt,
            String refreshToken, Instant refreshTokenExpiresAt) {
    }

    record Account(UUID id, String username, String email, String status, String passwordHash) {
    }

    @Transactional
    public Session register(String email, String username, String password, String displayName) {
        String u = username.trim().toLowerCase(Locale.ROOT);
        String e = email.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw ApiException.badRequest("username must be 3-32 characters of a-z, 0-9, '_' or '.'");
        }
        if (!EMAIL.matcher(e).matches()) {
            throw ApiException.badRequest("email is invalid");
        }
        validatePassword(password, u);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id, email, username) VALUES (?, ?, ?)", id, e, u);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("CONFLICT", "username or email is already registered");
        }
        jdbc.update("INSERT INTO credentials (user_id, password_hash) VALUES (?, ?)", id, passwords.encode(password));
        String name = displayName == null || displayName.isBlank() ? u : displayName.trim();
        outbox.write(EventType.USER_REGISTERED, id.toString(), id.toString(),
                new UserRegistered(id, u, name.length() > 100 ? name.substring(0, 100) : name, Instant.now()));
        log.info("user registered userId={}", id);
        return issue(id, u, UUID.randomUUID(), null);
    }

    static void validatePassword(String password, String username) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw ApiException.badRequest("password must be 10-128 characters");
        }
        if (password.toLowerCase(Locale.ROOT).contains(username)) {
            throw ApiException.badRequest("password must not contain the username");
        }
    }

    @Transactional
    public Session login(String login, String password, String clientIp) {
        String key = login.trim().toLowerCase(Locale.ROOT);
        limiter.check(key, clientIp);
        Optional<Account> acc = findAccount(key);
        if (acc.isEmpty()) {
            passwords.matches(password, dummyHash);
            throw invalidCredentials();
        }
        Account a = acc.get();
        if (!passwords.matches(password, a.passwordHash())) {
            log.info("login failed userId={}", a.id());
            throw invalidCredentials();
        }
        if (!"ACTIVE".equals(a.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "account is suspended");
        }
        limiter.reset(key);
        if (passwords.upgradeEncoding(a.passwordHash())) {
            jdbc.update("UPDATE credentials SET password_hash = ?, password_updated_at = now() WHERE user_id = ?",
                    passwords.encode(password), a.id());
        }
        log.info("login succeeded userId={}", a.id());
        return issue(a.id(), a.username(), UUID.randomUUID(), null);
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "invalid username or password");
    }

    private Optional<Account> findAccount(String login) {
        List<Account> rows = jdbc.query("""
                SELECT u.id, u.username, u.email, u.status, c.password_hash FROM users u
                JOIN credentials c ON c.user_id = u.id WHERE u.username = ? OR u.email = ?
                """, (rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), login, login);
        return rows.stream().findFirst();
    }

    record TokenRow(UUID id, UUID userId, UUID familyId, UUID deviceId, Instant expiresAt, Instant revokedAt) {
    }

    /**
     * Rotates a refresh token. Presenting an already-rotated/revoked token is treated as theft:
     * the entire family (session) is revoked.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String refreshToken) {
        TokenRow t = lockToken(refreshToken).orElseThrow(() -> unauthenticated("refresh token is invalid"));
        if (t.revokedAt() != null) {
            int n = revokeFamily(t.familyId(), "REUSE_DETECTED");
            log.warn("refresh token reuse detected userId={} family={} revoked={}", t.userId(), t.familyId(), n);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REUSED", "refresh token was already used; session revoked");
        }
        if (t.expiresAt().isBefore(Instant.now())) {
            throw unauthenticated("refresh token expired");
        }
        Account a = accountById(t.userId());
        if (!"ACTIVE".equals(a.status())) {
            revokeFamily(t.familyId(), "ACCOUNT_SUSPENDED");
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "account is suspended");
        }
        Session s = issue(a.id(), a.username(), t.familyId(), t.deviceId());
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'ROTATED', replaced_by = "
                + "(SELECT id FROM refresh_tokens WHERE token_hash = ?) WHERE id = ?", TokenService.hash(s.refreshToken()), t.id());
        return s;
    }

    @Transactional
    public void logout(String refreshToken) {
        lockToken(refreshToken).ifPresent(t -> revokeFamily(t.familyId(), "LOGOUT"));
    }

    /**
     * Binds a new session to a device registered by the caller. The device must exist, belong to
     * the caller and be ACTIVE according to User Service (fail closed if it is unreachable).
     * The caller's previous (unbound) session is revoked.
     */
    @Transactional
    public Session bindDevice(UUID userId, UUID currentSessionId, UUID deviceId) {
        Account a = accountById(userId);
        if (!"ACTIVE".equals(a.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", "account is suspended");
        }
        DeviceDirectory.DeviceInfo d;
        try {
            d = devices.find(deviceId).orElseThrow(() -> ApiException.notFound("device"));
        } catch (RetryableDependencyException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "device directory unavailable, retry later");
        }
        if (!userId.equals(d.userId())) {
            throw ApiException.notFound("device");
        }
        if (!"ACTIVE".equals(d.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REVOKED", "device is revoked");
        }
        if (currentSessionId != null) {
            revokeFamily(currentSessionId, "DEVICE_BOUND");
        }
        log.info("session bound userId={} deviceId={}", userId, deviceId);
        return issue(userId, a.username(), UUID.randomUUID(), deviceId);
    }

    /** Revokes every session bound to a device (consumer of chat.device.revoked). Idempotent. */
    @Transactional
    public int revokeDeviceSessions(UUID deviceId) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'DEVICE_REVOKED' "
                + "WHERE device_id = ? AND revoked_at IS NULL", deviceId);
    }

    /** Admin-controlled account status. Suspension revokes all sessions. */
    @Transactional
    public void setStatus(UUID userId, String status) {
        if (!List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw ApiException.badRequest("status must be ACTIVE or SUSPENDED");
        }
        int n = jdbc.update("UPDATE users SET status = ?, updated_at = now() WHERE id = ? AND status <> ?", status, userId, status);
        if (n == 0) {
            accountById(userId);
            return;
        }
        if ("SUSPENDED".equals(status)) {
            jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'ACCOUNT_SUSPENDED' "
                    + "WHERE user_id = ? AND revoked_at IS NULL", userId);
        }
        outbox.write(EventType.USER_STATUS_CHANGED, userId.toString(), userId.toString(),
                new UserStatusChanged(userId, status, Instant.now()));
        log.info("account status changed userId={} status={}", userId, status);
    }

    /** The caller's own account details (email is never exposed through public profile APIs). */
    public record MyAccount(UUID userId, String username, String email) {
    }

    public MyAccount myAccount(UUID userId) {
        Account a = accountById(userId);
        return new MyAccount(a.id(), a.username(), a.email());
    }

    /** Changes the caller's email; requires re-entering the current password (rate limited like login). */
    @Transactional
    public MyAccount changeEmail(UUID userId, String newEmail, String currentPassword, String clientIp) {
        String key = "email-change:" + userId;
        limiter.check(key, clientIp);
        Account a = accountById(userId);
        if (!passwords.matches(currentPassword, a.passwordHash())) {
            log.info("email change rejected (bad password) userId={}", userId);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "current password is incorrect");
        }
        limiter.reset(key);
        String e = newEmail.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) {
            throw ApiException.badRequest("email is invalid");
        }
        if (e.equals(a.email())) {
            return new MyAccount(a.id(), a.username(), a.email());
        }
        try {
            jdbc.update("UPDATE users SET email = ?, updated_at = now() WHERE id = ?", e, userId);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("CONFLICT", "email is already registered");
        }
        log.info("email changed userId={}", userId);
        return new MyAccount(a.id(), a.username(), e);
    }

    /**
     * Changes the caller's password after re-checking the current one (rate limited like login).
     * Every other session of the account is revoked; the session making the change stays signed in.
     * @return number of other sessions signed out
     */
    @Transactional
    public int changePassword(UUID userId, UUID currentSessionId, String currentPassword, String newPassword, String clientIp) {
        String key = "password-change:" + userId;
        limiter.check(key, clientIp);
        Account a = accountById(userId);
        if (!passwords.matches(currentPassword, a.passwordHash())) {
            log.info("password change rejected (bad current password) userId={}", userId);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "current password is incorrect");
        }
        limiter.reset(key);
        validatePassword(newPassword, a.username());
        if (passwords.matches(newPassword, a.passwordHash())) {
            throw ApiException.badRequest("new password must be different from the current one");
        }
        jdbc.update("UPDATE credentials SET password_hash = ?, password_updated_at = now() WHERE user_id = ?",
                passwords.encode(newPassword), userId);
        int n = jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = 'PASSWORD_CHANGED' "
                + "WHERE user_id = ? AND revoked_at IS NULL AND family_id IS DISTINCT FROM ?", userId, currentSessionId);
        log.info("password changed userId={} otherSessionsRevoked={}", userId, n);
        return n;
    }

    public record AccountView(UUID id, String username, String email, String status, Instant createdAt,
            long activeSessions) {
    }

    public List<AccountView> accounts(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT u.id, u.username, u.email, u.status, u.created_at,
                  (SELECT count(*) FROM refresh_tokens r WHERE r.user_id = u.id AND r.revoked_at IS NULL AND r.expires_at > now())
                FROM users u WHERE u.id = ANY (?)
                """, (rs, i) -> new AccountView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getLong(6)), (Object) ids.toArray(UUID[]::new));
    }

    public long countUsers(String status) {
        Long n = status == null ? jdbc.queryForObject("SELECT count(*) FROM users", Long.class)
                : jdbc.queryForObject("SELECT count(*) FROM users WHERE status = ?", Long.class, status);
        return n == null ? 0 : n;
    }

    private Account accountById(UUID id) {
        return jdbc.query("SELECT u.id, u.username, u.email, u.status, c.password_hash FROM users u "
                        + "JOIN credentials c ON c.user_id = u.id WHERE u.id = ?",
                (rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), id).stream().findFirst().orElseThrow(() -> ApiException.notFound("user"));
    }

    private Optional<TokenRow> lockToken(String refreshToken) {
        if (refreshToken == null || refreshToken.length() < 20 || refreshToken.length() > 100) {
            return Optional.empty();
        }
        return jdbc.query("SELECT id, user_id, family_id, device_id, expires_at, revoked_at FROM refresh_tokens "
                        + "WHERE token_hash = ? FOR UPDATE",
                (rs, i) -> new TokenRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
                TokenService.hash(refreshToken)).stream().findFirst();
    }

    private int revokeFamily(UUID familyId, String reason) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), revoke_reason = ? WHERE family_id = ? AND revoked_at IS NULL",
                reason, familyId);
    }

    private Session issue(UUID userId, String username, UUID familyId, UUID deviceId) {
        String refresh = TokenService.newRefreshToken();
        Instant refreshExp = Instant.now().plus(props.refreshTokenTtl());
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, family_id, device_id, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, TokenService.hash(refresh), familyId, deviceId, Timestamp.from(refreshExp));
        TokenService.AccessToken at = tokens.accessToken(userId, username, familyId, deviceId);
        return new Session(userId, username, deviceId, at.value(), at.expiresAt(), refresh, refreshExp);
    }

    private static ApiException unauthenticated(String msg) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", msg);
    }
}
