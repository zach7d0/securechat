package com.securechat.admin;

import com.securechat.support.client.InternalClients;
import com.securechat.support.security.SecureChatSecurityProperties;
import com.securechat.support.web.ApiException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Read paths into the owning services' internal APIs. The Admin Service never connects to another
 * service's database; the browser never reaches these endpoints.
 */
@Component
public class Downstream {
    private final RestClient users;
    private final RestClient auth;
    private final RestClient keys;
    private final RestClient presence;
    private final RestClient monitoring;
    private final RestClient delivery;
    private final RestClient conversations;
    private final RestClient plain;
    private final AdminProperties props;

    Downstream(RestClient.Builder b, SecureChatSecurityProperties sec, AdminProperties props,
            @Value("${securechat.internal.user-url}") String user, @Value("${securechat.internal.auth-url}") String authUrl,
            @Value("${securechat.internal.key-url}") String key, @Value("${securechat.internal.presence-url}") String pres,
            @Value("${securechat.internal.monitoring-url}") String mon, @Value("${securechat.internal.delivery-url}") String del,
            @Value("${securechat.internal.conversation-url}") String conv) {
        Duration t = Duration.ofSeconds(4);
        this.users = InternalClients.create(b, user, sec.internalToken(), t);
        this.auth = InternalClients.create(b, authUrl, sec.internalToken(), t);
        this.keys = InternalClients.create(b, key, sec.internalToken(), t);
        this.presence = InternalClients.create(b, pres, sec.internalToken(), t);
        this.monitoring = InternalClients.create(b, mon, sec.internalToken(), t);
        this.delivery = InternalClients.create(b, del, sec.internalToken(), t);
        this.conversations = InternalClients.create(b, conv, sec.internalToken(), t);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(1500);
        rf.setReadTimeout(2500);
        this.plain = b.clone().requestFactory(rf).build();
        this.props = props;
    }

    private static <T> T call(java.util.function.Supplier<T> s, String what) {
        try {
            return s.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound(what);
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", what + " service unavailable");
        }
    }

    public JsonNode listUsers(String q, int page, int size) {
        return call(() -> users.get().uri(u -> u.path("/internal/v1/admin/users").queryParam("q", q == null ? "" : q)
                .queryParam("page", page).queryParam("size", size).build()).retrieve().body(JsonNode.class), "user");
    }

    public JsonNode userDetail(UUID id) {
        return call(() -> users.get().uri("/internal/v1/admin/users/{id}", id).retrieve().body(JsonNode.class), "user");
    }

    public JsonNode accounts(List<UUID> ids) {
        return call(() -> auth.post().uri("/internal/v1/accounts:batchGet").body(Map.of("ids", ids)).retrieve().body(JsonNode.class), "auth");
    }

    public void setAccountStatus(UUID id, String status) {
        call(() -> auth.put().uri("/internal/v1/accounts/{id}/status", id).body(Map.of("status", status)).retrieve().toBodilessEntity(), "auth");
    }

    public void revokeDevice(UUID deviceId) {
        call(() -> users.post().uri("/internal/v1/devices/{id}/revoke", deviceId).retrieve().toBodilessEntity(), "device");
    }

    /** Key metadata only (counts/timestamps) — never key bytes. */
    public JsonNode keyStatus(UUID deviceId) {
        try {
            return keys.get().uri("/internal/v1/keys/devices/{id}/status", deviceId).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            return null;
        }
    }

    public JsonNode trace(UUID messageId) {
        return call(() -> monitoring.get().uri("/internal/v1/monitoring/messages/{id}", messageId).retrieve().body(JsonNode.class), "message");
    }

    public JsonNode authoritativeReceipts(UUID messageId) {
        try {
            return delivery.get().uri("/internal/v1/delivery/messages/{id}/receipts", messageId).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            return null;
        }
    }

    public JsonNode searchMessages(UUID conversationId, String from, String to, int limit) {
        return call(() -> monitoring.get().uri(u -> {
            var x = u.path("/internal/v1/monitoring/messages").queryParam("limit", limit);
            if (conversationId != null) {
                x.queryParam("conversationId", conversationId);
            }
            if (from != null) {
                x.queryParam("from", from);
            }
            if (to != null) {
                x.queryParam("to", to);
            }
            return x.build();
        }).retrieve().body(JsonNode.class), "monitoring");
    }

    public JsonNode report(String kind, String from, String to) {
        return call(() -> monitoring.get().uri(u -> {
            var x = u.path("/internal/v1/monitoring/reports/" + kind);
            if (from != null) {
                x.queryParam("from", from);
            }
            if (to != null) {
                x.queryParam("to", to);
            }
            return x.build();
        }).retrieve().body(JsonNode.class), "monitoring");
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accounts", safe(() -> auth.get().uri("/internal/v1/accounts/stats").retrieve().body(JsonNode.class)));
        out.put("users", safe(() -> users.get().uri("/internal/v1/stats").retrieve().body(JsonNode.class)));
        out.put("presence", safe(() -> presence.get().uri("/internal/v1/presence/stats").retrieve().body(JsonNode.class)));
        out.put("conversations", safe(() -> conversations.get().uri("/internal/v1/stats").retrieve().body(JsonNode.class)));
        out.put("messages24h", safe(() -> monitoring.get().uri("/internal/v1/monitoring/reports/summary").retrieve().body(JsonNode.class)));
        out.put("delivery", safe(() -> delivery.get().uri("/internal/v1/delivery/stats").retrieve().body(JsonNode.class)));
        return out;
    }

    private static Object safe(java.util.function.Supplier<Object> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return Map.of("status", "UNAVAILABLE");
        }
    }

    /** Health of every service (status + component statuses only), probed in parallel. */
    public List<Map<String, Object>> health() {
        List<CompletableFuture<Map<String, Object>>> fs = props.healthTargets().stream().map(t -> CompletableFuture.supplyAsync(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", t.substring(0, t.indexOf(':')));
            long start = System.nanoTime();
            try {
                JsonNode h = plain.get().uri("http://" + t + "/actuator/health").retrieve()
                        .onStatus(s -> s.value() == 503, (q, r) -> { }).body(JsonNode.class);
                m.put("status", h.path("status").asString("UNKNOWN"));
                Map<String, String> comps = new LinkedHashMap<>();
                h.path("components").properties().forEach(e -> comps.put(e.getKey(), e.getValue().path("status").asString()));
                m.put("components", comps);
            } catch (RuntimeException e) {
                m.put("status", "DOWN");
                m.put("components", Map.of());
            }
            m.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
            return m;
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())).toList();
        return fs.stream().map(CompletableFuture::join).toList();
    }

    public JsonNode alerts() {
        return call(() -> plain.get().uri(props.prometheusUrl() + "/api/v1/alerts")
                .headers(h -> {
                    if (props.prometheusUser() != null && !props.prometheusUser().isBlank()) {
                        h.setBasicAuth(props.prometheusUser(), props.prometheusPassword());   // Prometheus is password-protected
                    }
                })
                .retrieve().body(JsonNode.class).path("data"), "prometheus");
    }
}
