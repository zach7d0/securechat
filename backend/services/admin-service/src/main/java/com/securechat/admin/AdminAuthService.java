package com.securechat.admin;

import com.securechat.support.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin authentication: Argon2id password + TOTP (required when enrolled; enrolment enforced when
 * {@code mfa-required}), lockout after repeated failures, forced password change for the bootstrap
 * account, short-lived (15 min) admin tokens. A token's {@code stage} limits what it can do until
 * MFA enrolment / password change are completed.
 */
@Service
public class AdminAuthService {
    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final SecretBox box;
    private final Audit audit;
    private final AdminProperties props;
    private final String dummyHash;

    AdminAuthService(JdbcTemplate jdbc, PasswordEncoder passwords, JwtEncoder jwt, SecretBox box, Audit audit, AdminProperties props) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.jwt = jwt;
        this.box = box;
        this.audit = audit;
        this.props = props;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    record Admin(UUID id, String username, String displayName, String passwordHash, String status, byte[] mfaSecret, boolean mfaEnabled,
            boolean mustChangePassword, int failedAttempts, Instant lockedUntil, Long lastTotpStep, Boolean mfaRequired) {
    }

    /** Whether this admin must enrol MFA: the per-admin choice, or the global default when unset. */
    private boolean mfaRequired(Admin a) {
        return a.mfaRequired() == null ? props.mfaRequired() : a.mfaRequired();
    }

    private Optional<Admin> find(String username) {
        return jdbc.query("SELECT * FROM admin_users WHERE username = ?", (rs, i) -> new Admin(rs.getObject("id", UUID.class),
                rs.getString("username"), rs.getString("display_name"), rs.getString("password_hash"), rs.getString("status"),
                rs.getBytes("mfa_secret_enc"), rs.getBoolean("mfa_enabled"), rs.getBoolean("must_change_password"), rs.getInt("failed_attempts"),
                rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant(),
                (Long) rs.getObject("last_totp_step"), (Boolean) rs.getObject("mfa_required")), username.toLowerCase()).stream().findFirst();
    }

    private Admin byId(UUID id) {
        String u = jdbc.queryForList("SELECT username FROM admin_users WHERE id = ?", String.class, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("admin"));
        return find(u).orElseThrow();
    }

    public List<String> roles(UUID adminId) {
        return jdbc.queryForList("SELECT role FROM admin_roles WHERE admin_id = ? ORDER BY role", String.class, adminId);
    }

    /** First start: creates the SUPER_ADMIN from the environment (must change password on first login). */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM admin_users", Integer.class);
        if (n != null && n > 0) {
            return;
        }
        if (props.bootstrapUsername() == null || props.bootstrapUsername().isBlank() || props.bootstrapPassword() == null
                || props.bootstrapPassword().length() < 12) {
            log.warn("no admin accounts and no valid bootstrap credentials configured");
            return;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO admin_users (id, username, display_name, password_hash, must_change_password) VALUES (?, ?, ?, ?, true)",
                id, props.bootstrapUsername().toLowerCase(), "Super Admin", passwords.encode(props.bootstrapPassword()));
        jdbc.update("INSERT INTO admin_roles (admin_id, role) VALUES (?, 'SUPER_ADMIN')", id);
        audit.record(id, props.bootstrapUsername(), "ADMIN_BOOTSTRAPPED", "admin", id.toString(), "SUCCESS", null);
        log.info("bootstrap super admin created");
    }

    public record LoginResult(String token, Instant expiresAt, String stage, List<String> roles, String username, String displayName,
            boolean mfaEnabled) {
    }

    @Transactional(noRollbackFor = ApiException.class)
    public LoginResult login(String username, String password, String totp) {
        Optional<Admin> found = find(username == null ? "" : username);
        if (found.isEmpty()) {
            passwords.matches(password, dummyHash);
            audit.record(null, username, "ADMIN_LOGIN", "admin", null, "FAILURE", "unknown account");
            throw invalid();
        }
        Admin a = found.get();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT locked_manually FROM admin_users WHERE id = ?", Boolean.class, a.id()))) {
            audit.record(a.id(), a.username(), "ADMIN_LOGIN", "admin", a.id().toString(), "DENIED", "locked by administrator");
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_LOCKED", "account is locked by an administrator");
        }
        if (a.lockedUntil() != null && a.lockedUntil().isAfter(Instant.now())) {
            audit.record(a.id(), a.username(), "ADMIN_LOGIN", "admin", a.id().toString(), "DENIED", "locked");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "account temporarily locked");
        }
        if (!"ACTIVE".equals(a.status()) || !passwords.matches(password, a.passwordHash())) {
            fail(a, "bad password or disabled");
            throw invalid();
        }
        if (a.mfaEnabled()) {
            long step = Totp.verify(box.open(a.mfaSecret(), a.id().toString()), totp, System.currentTimeMillis());
            if (step < 0 || (a.lastTotpStep() != null && step <= a.lastTotpStep())) {
                fail(a, totp == null || totp.isBlank() ? "mfa code required" : "bad or replayed mfa code");
                throw new ApiException(HttpStatus.UNAUTHORIZED, totp == null || totp.isBlank() ? "MFA_REQUIRED" : "INVALID_CREDENTIALS",
                        "a valid authenticator code is required");
            }
            jdbc.update("UPDATE admin_users SET last_totp_step = ? WHERE id = ?", step, a.id());
        }
        jdbc.update("UPDATE admin_users SET failed_attempts = 0, locked_until = NULL, last_login_at = now() WHERE id = ?", a.id());
        String stage = a.mustChangePassword() ? "PASSWORD_CHANGE" : (mfaRequired(a) && !a.mfaEnabled() ? "MFA_ENROLLMENT" : "FULL");
        audit.record(a.id(), a.username(), "ADMIN_LOGIN", "admin", a.id().toString(), "SUCCESS", "stage=" + stage);
        return issue(a, stage);
    }

    private void fail(Admin a, String why) {
        int n = a.failedAttempts() + 1;
        Timestamp lock = n >= props.maxFailedLogins() ? Timestamp.from(Instant.now().plus(props.lockout())) : null;
        jdbc.update("UPDATE admin_users SET failed_attempts = ?, locked_until = COALESCE(?, locked_until) WHERE id = ?",
                lock == null ? n : 0, lock, a.id());
        audit.record(a.id(), a.username(), "ADMIN_LOGIN", "admin", a.id().toString(), "FAILURE", why + (lock != null ? "; locked" : ""));
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "invalid credentials");
    }

    private LoginResult issue(Admin a, String stage) {
        List<String> roles = roles(a.id());
        Instant now = Instant.now();
        Instant exp = now.plus(props.tokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(props.issuer()).audience(List.of(props.audience())).subject(a.id().toString())
                .issuedAt(now).expiresAt(exp).id(UUID.randomUUID().toString()).claim("preferred_username", a.username())
                .claim("roles", "FULL".equals(stage) ? roles : List.of()).claim("stage", stage).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new LoginResult(token, exp, stage, roles, a.username(), a.displayName(), a.mfaEnabled());
    }

    static void validatePassword(String p, String username) {
        if (p == null || p.length() < 12 || p.length() > 128 || p.toLowerCase().contains(username.toLowerCase())) {
            throw ApiException.badRequest("admin passwords need 12-128 characters and must not contain the username");
        }
    }

    @Transactional
    public LoginResult changePassword(UUID adminId, String current, String next) {
        Admin a = byId(adminId);
        if (!passwords.matches(current, a.passwordHash())) {
            audit.record(a.id(), a.username(), "ADMIN_PASSWORD_CHANGE", "admin", a.id().toString(), "FAILURE", "wrong current password");
            throw invalid();
        }
        validatePassword(next, a.username());
        if (passwords.matches(next, a.passwordHash())) {
            throw ApiException.badRequest("choose a new password");
        }
        jdbc.update("UPDATE admin_users SET password_hash = ?, must_change_password = false WHERE id = ?", passwords.encode(next), a.id());
        audit.record(a.id(), a.username(), "ADMIN_PASSWORD_CHANGE", "admin", a.id().toString(), "SUCCESS", null);
        Admin updated = byId(adminId);
        return issue(updated, mfaRequired(updated) && !updated.mfaEnabled() ? "MFA_ENROLLMENT" : "FULL");
    }

    /** Starts (or restarts, while not yet enabled) TOTP enrolment; the secret is shown once. */
    @Transactional
    public Map<String, String> mfaSetup(UUID adminId) {
        Admin a = byId(adminId);
        if (a.mfaEnabled()) {
            throw ApiException.conflict("CONFLICT", "MFA is already enabled");
        }
        byte[] secret = Totp.newSecret();
        jdbc.update("UPDATE admin_users SET mfa_secret_enc = ? WHERE id = ?", box.seal(secret, a.id().toString()), a.id());
        String b32 = Totp.base32(secret);
        audit.record(a.id(), a.username(), "ADMIN_MFA_SETUP", "admin", a.id().toString(), "SUCCESS", null);
        return Map.of("secret", b32, "otpauthUri", "otpauth://totp/SecureChat%20Admin:" + a.username() + "?secret=" + b32
                + "&issuer=SecureChat%20Admin&algorithm=SHA1&digits=6&period=30");
    }

    @Transactional
    public LoginResult mfaVerify(UUID adminId, String code) {
        Admin a = byId(adminId);
        if (a.mfaSecret() == null) {
            throw ApiException.badRequest("start MFA setup first");
        }
        long step = Totp.verify(box.open(a.mfaSecret(), a.id().toString()), code, System.currentTimeMillis());
        if (step < 0) {
            audit.record(a.id(), a.username(), "ADMIN_MFA_ENABLE", "admin", a.id().toString(), "FAILURE", null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "code does not match");
        }
        jdbc.update("UPDATE admin_users SET mfa_enabled = true, last_totp_step = ? WHERE id = ?", step, a.id());
        audit.record(a.id(), a.username(), "ADMIN_MFA_ENABLE", "admin", a.id().toString(), "SUCCESS", null);
        Admin updated = byId(adminId);
        return issue(updated, updated.mustChangePassword() ? "PASSWORD_CHANGE" : "FULL");
    }

    // ------------------------------------------------------------------ admin management (SUPER_ADMIN)

    public List<Map<String, Object>> listAdmins() {
        return jdbc.queryForList("""
                SELECT a.id, a.username, a.display_name, a.status, a.mfa_enabled, a.created_at, a.last_login_at,
                       a.locked_manually, a.failed_attempts, coalesce(a.mfa_required, ?) AS mfa_required,
                       CASE WHEN a.locked_until > now() THEN a.locked_until END AS locked_until,
                       (a.locked_manually OR coalesce(a.locked_until > now(), false)) AS locked,
                       coalesce(string_agg(r.role, ',' ORDER BY r.role), '') AS roles
                FROM admin_users a LEFT JOIN admin_roles r ON r.admin_id = a.id GROUP BY a.id ORDER BY a.username
                """, props.mfaRequired());
    }

    @Transactional
    public UUID createAdmin(String username, String displayName, String password, Set<String> roles) {
        return createAdmin(username, displayName, password, roles, null);
    }

    /** @param mfaRequired whether the new admin must enrol MFA at first login; null = global default */
    @Transactional
    public UUID createAdmin(String username, String displayName, String password, Set<String> roles, Boolean mfaRequired) {
        String u = username == null ? "" : username.trim().toLowerCase();
        if (!u.matches("[a-z0-9_.]{3,32}")) {
            throw ApiException.badRequest("username must be 3-32 characters of a-z, 0-9, '_' or '.'");
        }
        validatePassword(password, u);
        setRolesCheck(roles);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO admin_users (id, username, display_name, password_hash, must_change_password, mfa_required) "
                    + "VALUES (?, ?, ?, ?, true, ?)", id, u, displayName == null || displayName.isBlank() ? u : displayName.trim(),
                    passwords.encode(password), mfaRequired);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("CONFLICT", "admin username already exists");
        }
        for (String r : roles) {
            jdbc.update("INSERT INTO admin_roles (admin_id, role) VALUES (?, ?)", id, r);
        }
        audit.me("ADMIN_CREATED", "admin", id.toString(), "SUCCESS", "roles=" + roles + "; mfaRequired="
                + (mfaRequired == null ? "default(" + props.mfaRequired() + ")" : mfaRequired));
        return id;
    }

    @Transactional
    public void setRoles(UUID actor, UUID adminId, Set<String> roles) {
        setRolesCheck(roles);
        byId(adminId);
        if (actor.equals(adminId) && !roles.contains("SUPER_ADMIN")) {
            throw ApiException.badRequest("you cannot remove your own SUPER_ADMIN role");
        }
        if (!roles.contains("SUPER_ADMIN")) {
            ensureAnotherSuperAdmin(adminId, "remove the SUPER_ADMIN role from");
        }
        jdbc.update("DELETE FROM admin_roles WHERE admin_id = ?", adminId);
        for (String r : roles) {
            jdbc.update("INSERT INTO admin_roles (admin_id, role) VALUES (?, ?)", adminId, r);
        }
        audit.me("ADMIN_ROLES_CHANGED", "admin", adminId.toString(), "SUCCESS", "roles=" + roles);
    }

    @Transactional
    public void setStatus(UUID actor, UUID adminId, String status) {
        if (actor.equals(adminId)) {
            throw ApiException.badRequest("you cannot disable yourself");
        }
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw ApiException.badRequest("status must be ACTIVE or DISABLED");
        }
        if ("DISABLED".equals(status)) {
            ensureAnotherSuperAdmin(adminId, "disable");
        }
        if (jdbc.update("UPDATE admin_users SET status = ? WHERE id = ?", status, adminId) == 0) {
            throw ApiException.notFound("admin");
        }
        audit.me("ADMIN_STATUS_CHANGED", "admin", adminId.toString(), "SUCCESS", "status=" + status);
    }

    /** Manually locks an admin account: login is refused until a SUPER_ADMIN unlocks it. */
    @Transactional
    public void lock(UUID actor, UUID adminId) {
        if (actor.equals(adminId)) {
            throw ApiException.badRequest("you cannot lock yourself");
        }
        ensureAnotherSuperAdmin(adminId, "lock");
        if (jdbc.update("UPDATE admin_users SET locked_manually = true WHERE id = ?", adminId) == 0) {
            throw ApiException.notFound("admin");
        }
        audit.me("ADMIN_LOCK", "admin", adminId.toString(), "SUCCESS", null);
    }

    /** Clears both a manual lock and a failed-login lockout (and resets the failure counter). */
    @Transactional
    public void unlock(UUID adminId) {
        if (jdbc.update("UPDATE admin_users SET locked_manually = false, locked_until = NULL, failed_attempts = 0 WHERE id = ?",
                adminId) == 0) {
            throw ApiException.notFound("admin");
        }
        audit.me("ADMIN_UNLOCK", "admin", adminId.toString(), "SUCCESS", null);
    }

    /** Permanently deletes an admin account (audit history is kept: it stores the username, not a reference). */
    @Transactional
    public void deleteAdmin(UUID actor, UUID adminId) {
        if (actor.equals(adminId)) {
            throw ApiException.badRequest("you cannot delete yourself");
        }
        Admin a = byId(adminId);
        ensureAnotherSuperAdmin(adminId, "delete");
        jdbc.update("DELETE FROM admin_roles WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        audit.me("ADMIN_DELETED", "admin", adminId.toString(), "SUCCESS", "username=" + a.username());
    }

    /**
     * Invariant: at least one ACTIVE, unlocked SUPER_ADMIN other than {@code target} must remain, so the
     * portal can never end up with nobody able to manage admins. Super-admin rows are locked FOR UPDATE so
     * two concurrent removals cannot both pass the check.
     */
    private void ensureAnotherSuperAdmin(UUID target, String action) {
        List<UUID> others = jdbc.queryForList("""
                SELECT a.id FROM admin_users a JOIN admin_roles r ON r.admin_id = a.id AND r.role = 'SUPER_ADMIN'
                WHERE a.id <> ? AND a.status = 'ACTIVE' AND NOT a.locked_manually
                ORDER BY a.id FOR UPDATE OF a
                """, UUID.class, target);
        boolean targetIsSuper = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM admin_roles WHERE admin_id = ? AND role = 'SUPER_ADMIN')", Boolean.class, target));
        if (targetIsSuper && others.isEmpty()) {
            throw ApiException.conflict("LAST_SUPER_ADMIN", "cannot " + action + " the last active SUPER_ADMIN; at least one must remain");
        }
    }

    private static void setRolesCheck(Set<String> roles) {
        if (roles == null || roles.isEmpty() || !Roles.ALL.containsAll(roles)) {
            throw ApiException.badRequest("roles must be a non-empty subset of " + Roles.ALL);
        }
    }
}
