package com.securechat.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminServiceIT {
    static final String BOOT_PW = "bootstrap-password-123";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "admin_db");
        Infra.commonProperties(r);
        r.add("securechat.admin.private-key-file", () -> Infra.jwtKeyFile().toString());
        r.add("securechat.admin.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        r.add("securechat.admin.mfa-required", () -> "true");
        r.add("securechat.admin.bootstrap-username", () -> "root");
        r.add("securechat.admin.bootstrap-password", () -> BOOT_PW);
    }

    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminAuthService auth;
    @MockitoBean
    Downstream ds;
    Http http;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
        when(ds.listUsers(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(json.readTree(
                "{\"items\":[{\"userId\":\"11111111-1111-4111-8111-111111111111\",\"username\":\"alice\",\"displayName\":\"Alice\",\"createdAt\":\"2026-01-01T00:00:00Z\"}],\"total\":1}"));
        when(ds.accounts(any())).thenReturn(json.readTree("[{\"id\":\"11111111-1111-4111-8111-111111111111\",\"status\":\"ACTIVE\",\"email\":\"a@x.test\"}]"));
        when(ds.userDetail(any())).thenReturn(json.readTree(
                "{\"profile\":{\"userId\":\"11111111-1111-4111-8111-111111111111\"},\"devices\":[{\"id\":\"22222222-2222-4222-8222-222222222222\",\"status\":\"ACTIVE\"}]}"));
        when(ds.keyStatus(any())).thenReturn(json.readTree("{\"identity\":true,\"oneTimePreKeys\":80,\"mlsKeyPackages\":19}"));
        when(ds.trace(any())).thenReturn(json.readTree("{\"message_id\":\"x\",\"milestones\":[]}"));
        when(ds.report(any(), any(), any())).thenReturn(json.readTree("{\"accepted\":1}"));
    }

    /** Logs in an admin created directly (FULL stage needs MFA when mfa-required=true). */
    String fullToken(String role) {
        String u = "adm" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = auth.createAdmin(u, u, "a-strong-admin-pass-1", Set.of(role));
        jdbc.update("UPDATE admin_users SET must_change_password = false WHERE id = ?", id);
        Http.Response r = http.post("/api/admin/v1/auth/login", Map.of("username", u, "password", "a-strong-admin-pass-1"), null);
        assertThat(r.text("stage")).isEqualTo("MFA_ENROLLMENT");
        String secret = http.post("/api/admin/v1/mfa/setup", null, r.text("token")).text("secret");
        String code = Totp.code(Totp.fromBase32(secret), Totp.step(System.currentTimeMillis()));
        Http.Response v = http.post("/api/admin/v1/mfa/verify", Map.of("code", code), r.text("token"));
        assertThat(v.text("stage")).isEqualTo("FULL");
        return v.text("token");
    }

    @Test
    void bootstrapAdminMustChangePasswordThenEnrolMfaBeforeFullAccess() {
        Http.Response r = http.post("/api/admin/v1/auth/login", Map.of("username", "root", "password", BOOT_PW), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("stage")).isEqualTo("PASSWORD_CHANGE");
        assertThat(http.get("/api/admin/v1/users", r.text("token")).status()).isEqualTo(403);
        Http.Response pc = http.post("/api/admin/v1/auth/password", Map.of("currentPassword", BOOT_PW, "newPassword", "fresh-admin-secret-456"),
                r.text("token"));
        assertThat(pc.text("stage")).isEqualTo("MFA_ENROLLMENT");
        assertThat(http.get("/api/admin/v1/dashboard", pc.text("token")).status()).isEqualTo(403);
        String secret = http.post("/api/admin/v1/mfa/setup", null, pc.text("token")).text("secret");
        byte[] stored = jdbc.queryForObject("SELECT mfa_secret_enc FROM admin_users WHERE username = 'root'", byte[].class);
        assertThat(new String(stored)).doesNotContain(secret);                  // encrypted at rest
        assertThat(stored).isNotEqualTo(Totp.fromBase32(secret));
        long step = Totp.step(System.currentTimeMillis());
        Http.Response full = http.post("/api/admin/v1/mfa/verify", Map.of("code", Totp.code(Totp.fromBase32(secret), step)), pc.text("token"));
        assertThat(full.text("stage")).isEqualTo("FULL");
        assertThat(http.get("/api/admin/v1/admins", full.text("token")).status()).isEqualTo(200);
        // next login requires the code; the code just used cannot be replayed
        Http.Response noCode = http.post("/api/admin/v1/auth/login", Map.of("username", "root", "password", "fresh-admin-secret-456"), null);
        assertThat(noCode.code()).isEqualTo("MFA_REQUIRED");
        Http.Response replay = http.post("/api/admin/v1/auth/login", Map.of("username", "root", "password", "fresh-admin-secret-456",
                "totp", Totp.code(Totp.fromBase32(secret), step)), null);
        assertThat(replay.status()).isEqualTo(401);
    }

    @Test
    void repeatedFailuresLockTheAccount() {
        String u = "lock" + UUID.randomUUID().toString().substring(0, 6);
        auth.createAdmin(u, u, "a-strong-admin-pass-1", Set.of("SUPPORT_ADMIN"));
        for (int i = 0; i < 5; i++) {
            assertThat(http.post("/api/admin/v1/auth/login", Map.of("username", u, "password", "wrong-password-xx"), null).status()).isEqualTo(401);
        }
        assertThat(http.post("/api/admin/v1/auth/login", Map.of("username", u, "password", "a-strong-admin-pass-1"), null).status()).isEqualTo(429);
    }

    @Test
    void superAdminCanLockAndUnlockAdminAccounts() {
        String sup = fullToken("SUPER_ADMIN");
        String u = "lk" + UUID.randomUUID().toString().substring(0, 6);
        UUID target = auth.createAdmin(u, u, "a-strong-admin-pass-1", Set.of("SUPPORT_ADMIN"));
        Map<String, String> creds = Map.of("username", u, "password", "a-strong-admin-pass-1");

        // manual lock refuses login even with the right password, and survives until unlocked
        assertThat(http.post("/api/admin/v1/admins/" + target + "/lock", null, sup).status()).isEqualTo(204);
        Http.Response denied = http.post("/api/admin/v1/auth/login", creds, null);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.code()).isEqualTo("ACCOUNT_LOCKED");
        var row = findAdmin(sup, u);
        assertThat(row.get("locked").asBoolean()).isTrue();
        assertThat(row.get("locked_manually").asBoolean()).isTrue();
        assertThat(http.post("/api/admin/v1/admins/" + target + "/unlock", null, sup).status()).isEqualTo(204);
        assertThat(http.post("/api/admin/v1/auth/login", creds, null).status()).isEqualTo(200);

        // unlock also clears a failed-login lockout
        for (int i = 0; i < 5; i++) {
            http.post("/api/admin/v1/auth/login", Map.of("username", u, "password", "wrong-password-xx"), null);
        }
        assertThat(http.post("/api/admin/v1/auth/login", creds, null).status()).isEqualTo(429);
        assertThat(findAdmin(sup, u).get("locked").asBoolean()).isTrue();
        assertThat(findAdmin(sup, u).get("locked_manually").asBoolean()).isFalse();
        assertThat(http.post("/api/admin/v1/admins/" + target + "/unlock", null, sup).status()).isEqualTo(204);
        assertThat(http.post("/api/admin/v1/auth/login", creds, null).status()).isEqualTo(200);

        // only SUPER_ADMIN, never yourself, unknown id → 404; both actions audited
        assertThat(http.post("/api/admin/v1/admins/" + target + "/lock", null, fullToken("SECURITY_ADMIN")).status()).isEqualTo(403);
        UUID self = UUID.fromString(http.get("/api/admin/v1/me", sup).text("id"));
        assertThat(http.post("/api/admin/v1/admins/" + self + "/lock", null, sup).status()).isEqualTo(400);
        assertThat(http.post("/api/admin/v1/admins/" + UUID.randomUUID() + "/unlock", null, sup).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE target_id = ? AND action IN ('ADMIN_LOCK', 'ADMIN_UNLOCK')",
                Long.class, target.toString())).isEqualTo(3);
    }

    tools.jackson.databind.JsonNode findAdmin(String token, String username) {
        for (var n : http.get("/api/admin/v1/admins", token).body()) {
            if (username.equals(n.get("username").asString())) {
                return n;
            }
        }
        throw new AssertionError("admin not listed: " + username);
    }

    @Test
    void superAdminCanDeleteAdminsButNeverTheLastSuperAdmin() {
        String sup = fullToken("SUPER_ADMIN");
        String u = "del" + UUID.randomUUID().toString().substring(0, 6);
        UUID target = auth.createAdmin(u, u, "a-strong-admin-pass-1", Set.of("SUPPORT_ADMIN"));
        assertThat(http.delete("/api/admin/v1/admins/" + target, fullToken("SECURITY_ADMIN")).status()).isEqualTo(403);
        assertThat(http.delete("/api/admin/v1/admins/" + target, sup).status()).isEqualTo(204);
        assertThat(http.delete("/api/admin/v1/admins/" + target, sup).status()).isEqualTo(404);
        assertThat(http.post("/api/admin/v1/auth/login", Map.of("username", u, "password", "a-strong-admin-pass-1"), null).status())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = 'ADMIN_DELETED' AND target_id = ?",
                Long.class, target.toString())).isEqualTo(1);
        UUID self = UUID.fromString(http.get("/api/admin/v1/me", sup).text("id"));
        assertThat(http.delete("/api/admin/v1/admins/" + self, sup).status()).isEqualTo(400);

        // make one super admin the only active one, then every way of removing it is refused
        String lu = "last" + UUID.randomUUID().toString().substring(0, 6);
        UUID last = auth.createAdmin(lu, lu, "a-strong-admin-pass-1", Set.of("SUPER_ADMIN"));
        List<UUID> others = jdbc.queryForList("SELECT a.id FROM admin_users a JOIN admin_roles r ON r.admin_id = a.id "
                + "AND r.role = 'SUPER_ADMIN' WHERE a.id <> ? AND a.status = 'ACTIVE'", UUID.class, last);
        UUID actor = UUID.randomUUID();
        try {
            others.forEach(o -> jdbc.update("UPDATE admin_users SET status = 'DISABLED' WHERE id = ?", o));
            for (Runnable r : List.<Runnable>of(() -> auth.deleteAdmin(actor, last), () -> auth.setStatus(actor, last, "DISABLED"),
                    () -> auth.lock(actor, last), () -> auth.setRoles(actor, last, Set.of("SUPPORT_ADMIN")))) {
                assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(com.securechat.support.web.ApiException.class, r::run)
                        .code()).isEqualTo("LAST_SUPER_ADMIN");
            }
            // a non-super admin can still be deleted while only one super admin is active
            UUID plain = auth.createAdmin("p" + lu, "p" + lu, "a-strong-admin-pass-1", Set.of("REPORTING_ADMIN"));
            auth.deleteAdmin(last, plain);
        } finally {
            others.forEach(o -> jdbc.update("UPDATE admin_users SET status = 'ACTIVE' WHERE id = ?", o));
        }
        auth.deleteAdmin(actor, last);   // allowed again once other super admins are active
    }

    @Test
    void mfaRequirementIsChosenPerAdminAtCreation() {
        String sup = fullToken("SUPER_ADMIN");
        String u = "nomfa" + UUID.randomUUID().toString().substring(0, 6);
        Http.Response c = http.post("/api/admin/v1/admins", Map.of("username", u, "password", "a-strong-admin-pass-1",
                "roles", List.of("REPORTING_ADMIN"), "mfaRequired", false), sup);
        assertThat(c.status()).isEqualTo(201);
        jdbc.update("UPDATE admin_users SET must_change_password = false WHERE username = ?", u);
        // global mfa-required=true in this test, yet this admin goes straight to FULL without enrolment
        Http.Response login = http.post("/api/admin/v1/auth/login", Map.of("username", u, "password", "a-strong-admin-pass-1"), null);
        assertThat(login.text("stage")).isEqualTo("FULL");
        assertThat(findAdmin(sup, u).get("mfa_required").asBoolean()).isFalse();

        String m = "mfa" + UUID.randomUUID().toString().substring(0, 6);
        http.post("/api/admin/v1/admins", Map.of("username", m, "password", "a-strong-admin-pass-1",
                "roles", List.of("REPORTING_ADMIN"), "mfaRequired", true), sup);
        jdbc.update("UPDATE admin_users SET must_change_password = false WHERE username = ?", m);
        assertThat(http.post("/api/admin/v1/auth/login", Map.of("username", m, "password", "a-strong-admin-pass-1"), null)
                .text("stage")).isEqualTo("MFA_ENROLLMENT");
        assertThat(findAdmin(sup, m).get("mfa_required").asBoolean()).isTrue();
    }

    @Test
    void reportsAcceptACustomRangeAndRejectBadOnes() {
        String t = fullToken("REPORTING_ADMIN");
        assertThat(http.get("/api/admin/v1/reports/summary?from=2026-11-01T00:00:00Z&to=2026-11-08T00:00:00Z", t).status()).isEqualTo(200);
        org.mockito.Mockito.verify(ds).report("summary", "2026-11-01T00:00:00Z", "2026-11-08T00:00:00Z");
        assertThat(http.get("/api/admin/v1/reports/hourly?from=2026-11-08T00:00:00Z&to=2026-11-01T00:00:00Z", t).status()).isEqualTo(400);
        assertThat(http.get("/api/admin/v1/reports/hourly?from=2026-01-01T00:00:00Z&to=2026-11-01T00:00:00Z", t).status()).isEqualTo(400);
        assertThat(http.get("/api/admin/v1/reports/summary?from=2026-11-01T00:00:00Z", t).status()).isEqualTo(400);
        assertThat(http.get("/api/admin/v1/reports/summary?from=not-a-date&to=2026-11-01T00:00:00Z", t).status()).isEqualTo(400);
    }

    @Test
    void rbacIsEnforcedServerSideForEveryRole() {
        String uid = "11111111-1111-4111-8111-111111111111";
        Map<String, String> tokens = Map.of(
                "SUPER_ADMIN", fullToken("SUPER_ADMIN"), "OPERATIONS_ADMIN", fullToken("OPERATIONS_ADMIN"),
                "SUPPORT_ADMIN", fullToken("SUPPORT_ADMIN"), "SECURITY_ADMIN", fullToken("SECURITY_ADMIN"),
                "REPORTING_ADMIN", fullToken("REPORTING_ADMIN"));
        record Case(String method, String path, Set<String> allowed) {
        }
        List<Case> cases = List.of(
                new Case("GET", "/api/admin/v1/dashboard", Roles.ALL),
                new Case("GET", "/api/admin/v1/users", Set.of("SUPER_ADMIN", "OPERATIONS_ADMIN", "SUPPORT_ADMIN", "SECURITY_ADMIN")),
                new Case("GET", "/api/admin/v1/users/" + uid, Set.of("SUPER_ADMIN", "OPERATIONS_ADMIN", "SUPPORT_ADMIN", "SECURITY_ADMIN")),
                new Case("POST", "/api/admin/v1/users/" + uid + "/suspend", Set.of("SUPER_ADMIN", "SECURITY_ADMIN")),
                new Case("GET", "/api/admin/v1/messages/" + UUID.randomUUID() + "/trace", Set.of("SUPER_ADMIN", "OPERATIONS_ADMIN", "SUPPORT_ADMIN")),
                new Case("GET", "/api/admin/v1/reports/summary", Set.of("SUPER_ADMIN", "OPERATIONS_ADMIN", "REPORTING_ADMIN")),
                new Case("GET", "/api/admin/v1/audit", Set.of("SUPER_ADMIN", "SECURITY_ADMIN")),
                new Case("GET", "/api/admin/v1/admins", Set.of("SUPER_ADMIN")),
                new Case("GET", "/api/admin/v1/config", Set.of("SUPER_ADMIN", "OPERATIONS_ADMIN")));
        for (Case c : cases) {
            for (var e : tokens.entrySet()) {
                int status = http.call(c.method(), c.path(), null, e.getValue(), Map.of()).status();
                if (c.allowed().contains(e.getKey())) {
                    assertThat(status).as("%s %s as %s", c.method(), c.path(), e.getKey()).isIn(200, 204);
                } else {
                    assertThat(status).as("%s %s as %s", c.method(), c.path(), e.getKey()).isEqualTo(403);
                }
            }
        }
    }

    @Test
    void endUserTokensAreRejectedAndUnauthenticatedCallsDenied() {
        String userToken = TestTokens.token(UUID.randomUUID(), "alice", UUID.randomUUID());
        assertThat(http.get("/api/admin/v1/dashboard", userToken).status()).isEqualTo(401);
        assertThat(http.get("/api/admin/v1/users", null).status()).isEqualTo(401);
    }

    @Test
    void sensitiveSearchesLookupsAndActionsAreAuditedAndNoKeyBytesReturned() {
        String sec = fullToken("SECURITY_ADMIN");
        assertThat(http.get("/api/admin/v1/users?q=ali", sec).body().get("items").get(0).get("status").asString()).isEqualTo("ACTIVE");
        Http.Response detail = http.get("/api/admin/v1/users/11111111-1111-4111-8111-111111111111", sec);
        assertThat(detail.body().get("devices").get(0).get("keys").get("oneTimePreKeys").asInt()).isEqualTo(80);
        assertThat(detail.body().toString()).doesNotContainIgnoringCase("privateKey").doesNotContain("publicKey");
        assertThat(http.post("/api/admin/v1/users/11111111-1111-4111-8111-111111111111/devices/22222222-2222-4222-8222-222222222222/revoke", null,
                sec).status()).isEqualTo(204);
        assertThat(http.post("/api/admin/v1/users/11111111-1111-4111-8111-111111111111/devices/" + UUID.randomUUID() + "/revoke", null, sec)
                .status()).isEqualTo(404);
        List<String> actions = jdbc.queryForList("SELECT action FROM admin_audit_log ORDER BY id", String.class);
        assertThat(actions).contains("ADMIN_LOGIN", "ADMIN_MFA_ENABLE", "USER_SEARCH", "USER_VIEW", "DEVICE_REVOKE");
        assertThat(http.get("/api/admin/v1/audit?action=USER_SEARCH", sec).body().get(0).get("details").asString()).contains("q=ali");
    }

    @Test
    void incidentsLifecycle() {
        String ops = fullToken("OPERATIONS_ADMIN");
        String id = http.post("/api/admin/v1/incidents", Map.of("title", "Kafka lag", "severity", "warning", "source", "KafkaConsumerLag"), ops)
                .text("id");
        assertThat(http.post("/api/admin/v1/incidents/" + id + "/notes", Map.of("note", "investigating"), ops).status()).isEqualTo(204);
        assertThat(http.post("/api/admin/v1/incidents/" + id + "/acknowledge", null, ops).status()).isEqualTo(204);
        assertThat(http.post("/api/admin/v1/incidents/" + id + "/resolve", null, ops).status()).isEqualTo(204);
        var list = http.get("/api/admin/v1/incidents", ops).body();
        assertThat(list.toString()).contains("RESOLVED").contains("investigating");
        assertThat(http.post("/api/admin/v1/incidents", Map.of("title", "x", "severity", "warning"), fullToken("REPORTING_ADMIN")).status())
                .isEqualTo(403);
    }
}
