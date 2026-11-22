package com.securechat.message;

import com.securechat.support.client.InternalClients;
import com.securechat.support.security.SecureChatSecurityProperties;
import com.securechat.support.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Synchronous dependencies of message acceptance. Membership and device status are
 * security-sensitive and FAIL CLOSED (503, message not accepted). The key-readiness check only
 * improves UX (stale device list detection) and is skipped if Key Management is unavailable.
 */
@Component
public class Dependencies {
    private static final Logger log = LoggerFactory.getLogger(Dependencies.class);
    private static final Duration DEVICE_CACHE = Duration.ofSeconds(15);
    private final RestClient conversations;
    private final RestClient users;
    private final RestClient keys;
    private final Map<UUID, CachedDevice> deviceCache = new ConcurrentHashMap<>();

    public record Authorization(boolean member, String type, String role, long membershipVersion, Long epoch, Instant createdAt,
            List<UUID> memberUserIds) {
    }

    public record DeviceStatus(UUID id, UUID userId, String status) {
    }

    private record CachedDevice(DeviceStatus status, Instant at) {
    }

    Dependencies(RestClient.Builder b, SecureChatSecurityProperties sec,
            @Value("${securechat.internal.conversation-url}") String conv,
            @Value("${securechat.internal.user-url}") String user,
            @Value("${securechat.internal.key-url}") String key) {
        this.conversations = InternalClients.create(b, conv, sec.internalToken(), Duration.ofSeconds(3));
        this.users = InternalClients.create(b, user, sec.internalToken(), Duration.ofSeconds(3));
        this.keys = InternalClients.create(b, key, sec.internalToken(), Duration.ofSeconds(1));
    }

    /** Never cached: authorization must reflect the current membership. */
    public Authorization authorize(UUID conversationId, UUID userId) {
        try {
            return conversations.get().uri("/internal/v1/conversations/{id}/authorize?userId={u}", conversationId, userId)
                    .retrieve().body(Authorization.class);
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "conversation service unavailable, retry later");
        }
    }

    /** Short-lived cache (15 s); revocation also disconnects sockets and revokes sessions immediately. */
    public DeviceStatus device(UUID deviceId) {
        CachedDevice c = deviceCache.get(deviceId);
        if (c != null && c.at().plus(DEVICE_CACHE).isAfter(Instant.now())) {
            return c.status();
        }
        try {
            DeviceStatus s = users.get().uri("/internal/v1/devices/{id}", deviceId).retrieve().body(DeviceStatus.class);
            deviceCache.put(deviceId, new CachedDevice(s, Instant.now()));
            return s;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "user service unavailable, retry later");
        }
    }

    public void evictDevice(UUID deviceId) {
        deviceCache.remove(deviceId);
    }

    /** @return ready (key-registered, active) devices per user, or null if Key Management is unavailable */
    public Map<UUID, List<UUID>> readyDevices(List<UUID> userIds) {
        try {
            return keys.post().uri("/internal/v1/keys/devices:ready").body(Map.of("userIds", userIds)).retrieve()
                    .body(new ParameterizedTypeReference<Map<UUID, List<UUID>>>() { });
        } catch (RestClientException e) {
            log.warn("key-service unavailable; skipping stale device list check");
            return null;
        }
    }
}
