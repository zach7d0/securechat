package com.securechat.gateway;

import com.securechat.support.client.InternalClients;
import com.securechat.support.security.SecureChatSecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Internal REST calls made by the gateway. Callers decide fail-open/closed per operation. */
@Component
public class Downstream {
    private final RestClient messages;
    private final RestClient delivery;
    private final RestClient presence;
    private final RestClient conversations;
    private final RestClient users;

    Downstream(RestClient.Builder b, SecureChatSecurityProperties sec,
            @Value("${securechat.internal.message-url}") String msg,
            @Value("${securechat.internal.delivery-url}") String del,
            @Value("${securechat.internal.presence-url}") String pres,
            @Value("${securechat.internal.conversation-url}") String conv,
            @Value("${securechat.internal.user-url}") String user) {
        this.messages = InternalClients.create(b, msg, sec.internalToken(), Duration.ofSeconds(8));
        this.delivery = InternalClients.create(b, del, sec.internalToken(), Duration.ofSeconds(5));
        this.presence = InternalClients.create(b, pres, sec.internalToken(), Duration.ofSeconds(2));
        this.conversations = InternalClients.create(b, conv, sec.internalToken(), Duration.ofSeconds(3));
        this.users = InternalClients.create(b, user, sec.internalToken(), Duration.ofSeconds(3));
    }

    public record DeviceStatus(UUID id, UUID userId, String status) {
    }

    public record Authorization(boolean member, String type, long membershipVersion, Long epoch, Instant createdAt,
            List<UUID> memberUserIds) {
    }

    /** Thrown with the downstream problem code so it can be relayed in an error frame. */
    public static class DownstreamError extends RuntimeException {
        public final String code;
        public final JsonNode details;

        public DownstreamError(String code, String message, JsonNode details) {
            super(message);
            this.code = code;
            this.details = details;
        }
    }

    /** @return null if the device does not exist */
    public DeviceStatus device(UUID deviceId) {
        try {
            return users.get().uri("/internal/v1/devices/{id}", deviceId).retrieve().body(DeviceStatus.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public void deviceSeen(UUID deviceId) {
        users.post().uri("/internal/v1/devices/{id}/seen?authenticated=true", deviceId).retrieve().toBodilessEntity();
    }

    public JsonNode submit(Map<String, Object> body) {
        return relay(() -> messages.post().uri("/internal/v1/messages").body(body).retrieve().body(JsonNode.class));
    }

    public void ack(UUID userId, UUID deviceId, String status, List<UUID> messageIds) {
        relay(() -> delivery.post().uri("/internal/v1/delivery/acks")
                .body(Map.of("userId", userId, "deviceId", deviceId, "status", status, "messageIds", messageIds))
                .retrieve().toBodilessEntity());
    }

    public void replay(UUID userId, UUID deviceId) {
        delivery.post().uri("/internal/v1/delivery/replay").body(Map.of("userId", userId, "deviceId", deviceId)).retrieve()
                .toBodilessEntity();
    }

    public void presenceConnect(UUID userId, UUID deviceId, String gatewayId) {
        presence.post().uri("/internal/v1/presence/connect").body(Map.of("userId", userId, "deviceId", deviceId, "gatewayId", gatewayId))
                .retrieve().toBodilessEntity();
    }

    public void presenceDisconnect(UUID userId, UUID deviceId, String gatewayId) {
        presence.post().uri("/internal/v1/presence/disconnect").body(Map.of("userId", userId, "deviceId", deviceId, "gatewayId", gatewayId))
                .retrieve().toBodilessEntity();
    }

    public void presenceHeartbeat(String gatewayId, List<Map<String, UUID>> devices) {
        presence.post().uri("/internal/v1/presence/heartbeat").body(Map.of("gatewayId", gatewayId, "devices", devices))
                .retrieve().toBodilessEntity();
    }

    public Authorization authorize(UUID conversationId, UUID userId) {
        return conversations.get().uri("/internal/v1/conversations/{id}/authorize?userId={u}", conversationId, userId)
                .retrieve().body(Authorization.class);
    }

    public List<UUID> peers(UUID userId) {
        return conversations.get().uri("/internal/v1/users/{id}/peers", userId).retrieve()
                .body(new ParameterizedTypeReference<List<UUID>>() { });
    }

    private <T> T relay(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (org.springframework.web.client.RestClientResponseException e) {
            JsonNode body = null;
            try {
                body = e.getResponseBodyAs(JsonNode.class);
            } catch (RuntimeException ignored) {
                // non-JSON error body
            }
            String code = body != null && body.has("code") ? body.get("code").asString() : "DEPENDENCY_UNAVAILABLE";
            String detail = body != null && body.has("detail") ? body.get("detail").asString() : "request failed";
            throw new DownstreamError(code, detail, body);
        } catch (org.springframework.web.client.RestClientException e) {
            throw new DownstreamError("DEPENDENCY_UNAVAILABLE", "service unavailable, retry", null);
        }
    }
}
