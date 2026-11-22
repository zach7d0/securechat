package com.securechat.admin;

import com.securechat.support.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Admin API. Every endpoint is authorised server-side (@PreAuthorize); sensitive searches, lookups
 * and actions are audited. No endpoint can return message plaintext or key material — the owning
 * services do not have plaintext and key status is reduced to counts/timestamps.
 */
@RestController
@RequestMapping("/api/admin/v1")
class AdminController {
    private final AdminAuthService auth;
    private final Downstream ds;
    private final Audit audit;
    private final JdbcTemplate jdbc;
    private final AdminProperties props;

    AdminController(AdminAuthService auth, Downstream ds, Audit audit, JdbcTemplate jdbc, AdminProperties props) {
        this.auth = auth;
        this.ds = ds;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
    }

    record Login(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password, @Size(max = 10) String totp) {
    }

    record PasswordChange(@NotBlank String currentPassword, @NotBlank @Size(max = 128) String newPassword) {
    }

    record Code(@NotBlank @Size(max = 10) String code) {
    }

    private static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    // ------------------------------------------------------------------ authentication

    @PostMapping("/auth/login")
    AdminAuthService.LoginResult login(@Valid @RequestBody Login r) {
        return auth.login(r.username(), r.password(), r.totp());
    }

    @PostMapping("/auth/password")
    AdminAuthService.LoginResult password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChange r) {
        return auth.changePassword(id(jwt), r.currentPassword(), r.newPassword());
    }

    @PostMapping("/mfa/setup")
    Map<String, String> mfaSetup(@AuthenticationPrincipal Jwt jwt) {
        return auth.mfaSetup(id(jwt));
    }

    @PostMapping("/mfa/verify")
    AdminAuthService.LoginResult mfaVerify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Code r) {
        return auth.mfaVerify(id(jwt), r.code());
    }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("id", jwt.getSubject(), "username", jwt.getClaimAsString("preferred_username"), "stage", jwt.getClaimAsString("stage"),
                "roles", auth.roles(id(jwt)), "expiresAt", jwt.getExpiresAt());
    }

    // ------------------------------------------------------------------ dashboard & users

    @GetMapping("/dashboard")
    @PreAuthorize(Roles.ANY)
    Map<String, Object> dashboard() {
        Map<String, Object> d = new LinkedHashMap<>(ds.dashboard());
        List<Map<String, Object>> health = ds.health();
        d.put("servicesUp", health.stream().filter(h -> "UP".equals(h.get("status"))).count());
        d.put("servicesTotal", health.size());
        return d;
    }

    /** Registered users: profile (User Service) merged with account status (Auth Service). */
    @GetMapping("/users")
    @PreAuthorize(Roles.USERS_READ)
    Map<String, Object> users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        if (q != null && q.length() > 64) {
            throw ApiException.badRequest("query too long");
        }
        JsonNode p = ds.listUsers(q, page, size);
        List<UUID> ids = new ArrayList<>();
        p.path("items").forEach(i -> ids.add(UUID.fromString(i.path("userId").asString())));
        Map<String, JsonNode> acc = new HashMap<>();
        if (!ids.isEmpty()) {
            ds.accounts(ids).forEach(a -> acc.put(a.path("id").asString(), a));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode i : p.path("items")) {
            JsonNode a = acc.get(i.path("userId").asString());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", i.path("userId").asString());
            m.put("username", i.path("username").asString());
            m.put("displayName", i.path("displayName").asString());
            m.put("createdAt", i.path("createdAt").asString());
            m.put("status", a == null ? "UNKNOWN" : a.path("status").asString());
            m.put("email", a == null ? null : a.path("email").asString());
            items.add(m);
        }
        audit.me("USER_SEARCH", "user", null, "SUCCESS", "q=" + (q == null ? "" : q) + " page=" + page);
        return Map.of("items", items, "total", p.path("total").asLong(), "page", page, "size", size);
    }

    @GetMapping("/users/{id}")
    @PreAuthorize(Roles.USERS_READ)
    Map<String, Object> user(@PathVariable UUID id) {
        JsonNode u = ds.userDetail(id);
        JsonNode accounts = ds.accounts(List.of(id));
        List<Map<String, Object>> devices = new ArrayList<>();
        for (JsonNode d : u.path("devices")) {
            Map<String, Object> m = new LinkedHashMap<>();
            d.properties().forEach(e -> m.put(e.getKey(), e.getValue()));
            m.put("keys", ds.keyStatus(UUID.fromString(d.path("id").asString())));
            devices.add(m);
        }
        audit.me("USER_VIEW", "user", id.toString(), "SUCCESS", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", u.path("profile"));
        out.put("account", accounts.isEmpty() ? null : accounts.get(0));
        out.put("devices", devices);
        return out;
    }

    @PostMapping("/users/{id}/suspend")
    @PreAuthorize(Roles.USERS_ACT)
    ResponseEntity<Void> suspend(@PathVariable UUID id) {
        ds.setAccountStatus(id, "SUSPENDED");
        audit.me("USER_SUSPEND", "user", id.toString(), "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reactivate")
    @PreAuthorize(Roles.USERS_ACT)
    ResponseEntity<Void> reactivate(@PathVariable UUID id) {
        ds.setAccountStatus(id, "ACTIVE");
        audit.me("USER_REACTIVATE", "user", id.toString(), "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/devices/{deviceId}/revoke")
    @PreAuthorize(Roles.USERS_ACT)
    ResponseEntity<Void> revoke(@PathVariable UUID userId, @PathVariable UUID deviceId) {
        boolean owned = false;
        for (JsonNode d : ds.userDetail(userId).path("devices")) {
            owned |= d.path("id").asString().equals(deviceId.toString());
        }
        if (!owned) {
            throw ApiException.notFound("device");
        }
        ds.revokeDevice(deviceId);
        audit.me("DEVICE_REVOKE", "device", deviceId.toString(), "SUCCESS", "user=" + userId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ message traceability (metadata only)

    @GetMapping("/messages/{messageId}/trace")
    @PreAuthorize(Roles.TRACE)
    JsonNode trace(@PathVariable UUID messageId) {
        JsonNode t = ds.trace(messageId);
        JsonNode receipts = ds.authoritativeReceipts(messageId);
        if (t instanceof ObjectNode o && receipts != null) {
            o.set("authoritativeReceipts", receipts);
        }
        audit.me("MESSAGE_TRACE", "message", messageId.toString(), "SUCCESS", null);
        return t;
    }

    @GetMapping("/messages")
    @PreAuthorize(Roles.TRACE)
    JsonNode messages(@RequestParam(required = false) UUID conversationId, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "50") int limit) {
        audit.me("MESSAGE_SEARCH", "conversation", conversationId == null ? null : conversationId.toString(), "SUCCESS",
                "from=" + from + " to=" + to);
        return ds.searchMessages(conversationId, from == null ? null : from.toString(), to == null ? null : to.toString(), limit);
    }

    // ------------------------------------------------------------------ reports, health, alerts

    @GetMapping("/reports/summary")
    @PreAuthorize(Roles.REPORTS)
    JsonNode reportSummary(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        checkRange(from, to);
        return ds.report("summary", from == null ? null : from.toString(), to == null ? null : to.toString());
    }

    @GetMapping("/reports/hourly")
    @PreAuthorize(Roles.REPORTS)
    JsonNode reportHourly(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        checkRange(from, to);
        return ds.report("hourly", from == null ? null : from.toString(), to == null ? null : to.toString());
    }

    /** Longest report window an admin may request (bounds the hourly result to ~2,200 rows). */
    static final java.time.Duration MAX_REPORT_RANGE = java.time.Duration.ofDays(92);

    /** No bounds = last 24 h (monitoring default); otherwise both bounds are required and must form a sane window. */
    static void checkRange(Instant f, Instant t) {
        if (f == null && t == null) {
            return;
        }
        if (f == null || t == null) {
            throw ApiException.badRequest("provide both 'from' and 'to', or neither");
        }
        if (!f.isBefore(t)) {
            throw ApiException.badRequest("'from' must be before 'to'");
        }
        if (java.time.Duration.between(f, t).compareTo(MAX_REPORT_RANGE) > 0) {
            throw ApiException.badRequest("report range must be at most " + MAX_REPORT_RANGE.toDays() + " days");
        }
    }

    @GetMapping("/health")
    @PreAuthorize(Roles.ALERTS)
    List<Map<String, Object>> health() {
        return ds.health();
    }

    @GetMapping("/alerts")
    @PreAuthorize(Roles.ALERTS)
    JsonNode alerts() {
        return ds.alerts();
    }

    // ------------------------------------------------------------------ incidents

    record NewIncident(@NotBlank @Size(max = 200) String title, @NotBlank String severity, @Size(max = 100) String source) {
    }

    record Note(@NotBlank @Size(max = 2000) String note) {
    }

    @GetMapping("/incidents")
    @PreAuthorize(Roles.ALERTS)
    List<Map<String, Object>> incidents(@RequestParam(required = false) String status) {
        List<Map<String, Object>> list = status == null
                ? jdbc.queryForList("SELECT * FROM incidents ORDER BY created_at DESC LIMIT 200")
                : jdbc.queryForList("SELECT * FROM incidents WHERE status = ? ORDER BY created_at DESC LIMIT 200", status);
        for (Map<String, Object> i : list) {
            i.put("notes", jdbc.queryForList("SELECT admin_username, note, created_at FROM incident_notes WHERE incident_id = ? ORDER BY created_at",
                    i.get("id")));
        }
        return list;
    }

    @PostMapping("/incidents")
    @PreAuthorize(Roles.OPS)
    ResponseEntity<Map<String, Object>> createIncident(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NewIncident r) {
        if (!Set.of("critical", "warning", "info").contains(r.severity())) {
            throw ApiException.badRequest("severity must be critical, warning or info");
        }
        UUID iid = UUID.randomUUID();
        jdbc.update("INSERT INTO incidents (id, title, severity, source, created_by) VALUES (?, ?, ?, ?, ?)", iid, r.title(), r.severity(),
                r.source(), id(jwt));
        audit.me("INCIDENT_CREATE", "incident", iid.toString(), "SUCCESS", r.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", iid));
    }

    @PostMapping("/incidents/{iid}/acknowledge")
    @PreAuthorize(Roles.OPS)
    ResponseEntity<Void> ack(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID iid) {
        if (jdbc.update("UPDATE incidents SET status = 'ACKNOWLEDGED', acknowledged_by = ?, acknowledged_at = now() WHERE id = ? AND status = 'OPEN'",
                id(jwt), iid) == 0) {
            throw ApiException.conflict("CONFLICT", "incident is not open");
        }
        audit.me("INCIDENT_ACK", "incident", iid.toString(), "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/incidents/{iid}/resolve")
    @PreAuthorize(Roles.OPS)
    ResponseEntity<Void> resolve(@PathVariable UUID iid) {
        if (jdbc.update("UPDATE incidents SET status = 'RESOLVED', resolved_at = now() WHERE id = ? AND status <> 'RESOLVED'", iid) == 0) {
            throw ApiException.conflict("CONFLICT", "incident already resolved or missing");
        }
        audit.me("INCIDENT_RESOLVE", "incident", iid.toString(), "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/incidents/{iid}/notes")
    @PreAuthorize(Roles.OPS)
    ResponseEntity<Void> note(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID iid, @Valid @RequestBody Note r) {
        jdbc.update("INSERT INTO incident_notes (id, incident_id, admin_id, admin_username, note) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), iid,
                id(jwt), jwt.getClaimAsString("preferred_username"), r.note());
        audit.me("INCIDENT_NOTE", "incident", iid.toString(), "SUCCESS", null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ audit, admins, config

    @GetMapping("/audit")
    @PreAuthorize(Roles.AUDIT)
    List<Map<String, Object>> audit(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action) {
        return audit.list(page, size, action);
    }

    record NewAdmin(@NotBlank String username, String displayName, @NotBlank String password, @NotEmpty Set<String> roles,
            Boolean mfaRequired) {
    }

    record RolesBody(@NotEmpty Set<String> roles) {
    }

    record StatusBody(@NotBlank String status) {
    }

    @GetMapping("/admins")
    @PreAuthorize(Roles.SUPER)
    List<Map<String, Object>> admins() {
        return auth.listAdmins();
    }

    @PostMapping("/admins")
    @PreAuthorize(Roles.SUPER)
    ResponseEntity<Map<String, Object>> createAdmin(@Valid @RequestBody NewAdmin r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", auth.createAdmin(r.username(), r.displayName(), r.password(), r.roles(), r.mfaRequired())));
    }

    @PutMapping("/admins/{aid}/roles")
    @PreAuthorize(Roles.SUPER)
    ResponseEntity<Void> roles(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID aid, @Valid @RequestBody RolesBody r) {
        auth.setRoles(id(jwt), aid, r.roles());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/admins/{aid}/status")
    @PreAuthorize(Roles.SUPER)
    ResponseEntity<Void> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID aid, @Valid @RequestBody StatusBody r) {
        auth.setStatus(id(jwt), aid, r.status());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/admins/{aid}")
    @PreAuthorize(Roles.SUPER)
    ResponseEntity<Void> deleteAdmin(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID aid) {
        auth.deleteAdmin(id(jwt), aid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admins/{aid}/lock")
    @PreAuthorize(Roles.SUPER)
    ResponseEntity<Void> lock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID aid) {
        auth.lock(id(jwt), aid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admins/{aid}/unlock")
    @PreAuthorize(Roles.SUPER)
    ResponseEntity<Void> unlock(@PathVariable UUID aid) {
        auth.unlock(aid);
        return ResponseEntity.noContent().build();
    }

    /** Read-only operational configuration (no secrets). Mutation workflows are deferred (MVP). */
    @GetMapping("/config")
    @PreAuthorize(Roles.OPS)
    Map<String, Object> config() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("adminTokenTtl", props.tokenTtl().toString());
        c.put("adminMfaRequired", props.mfaRequired());
        c.put("adminLockout", Map.of("maxFailedLogins", props.maxFailedLogins(), "duration", props.lockout().toString()));
        c.put("userAccessTokenTtl", "PT5M");
        c.put("userRefreshTokenTtl", "P30D");
        c.put("pendingDeliveryExpiry", "P14D");
        c.put("messageHistoryRetention", "P365D");
        c.put("idempotencyWindow", "P7D");
        c.put("kafkaRetention", "P7D");
        c.put("lifecycleProjectionRetention", "P30D");
        c.put("deployment", "single-host MVP (not highly available)");
        return c;
    }
}
