package com.securechat.fanout;

import com.securechat.contracts.EventCodec;
import com.securechat.support.client.InternalClients;
import com.securechat.support.kafka.RetryableDependencyException;
import com.securechat.support.security.SecureChatSecurityProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Membership and device resolution. Authoritative data comes from Conversation/User services; Redis
 * holds a short-lived, versioned cache ({@code conversation:{id}:members}) that is used only when its
 * version is at least the version Message Service authorized against, and is invalidated by
 * membership/device events. Errors throw {@link RetryableDependencyException}: the record is retried,
 * never fanned out from unsafe stale data.
 */
@Component
public class Directory {
    public record Members(String type, long version, List<UUID> userIds) {
    }

    record Authorization(boolean member, String type, long membershipVersion, Long epoch, List<UUID> memberUserIds) {
    }

    private final StringRedisTemplate redis;
    private final RestClient conversations;
    private final RestClient users;
    private final Duration membersTtl;
    private final Duration devicesTtl;

    Directory(StringRedisTemplate redis, RestClient.Builder b, SecureChatSecurityProperties sec,
            @Value("${securechat.internal.conversation-url}") String conv, @Value("${securechat.internal.user-url}") String user,
            @Value("${securechat.fanout.membership-cache-ttl:60s}") Duration membersTtl,
            @Value("${securechat.fanout.device-cache-ttl:30s}") Duration devicesTtl) {
        this.redis = redis;
        this.conversations = InternalClients.create(b, conv, sec.internalToken(), Duration.ofSeconds(3));
        this.users = InternalClients.create(b, user, sec.internalToken(), Duration.ofSeconds(3));
        this.membersTtl = membersTtl;
        this.devicesTtl = devicesTtl;
    }

    static String membersKey(UUID c) {
        return "conversation:" + c + ":members";
    }

    static String devicesKey(UUID u) {
        return "conversation:userdevices:" + u;
    }

    public Members members(UUID conversationId, long minVersion) {
        Members cached = readCache(membersKey(conversationId), Members.class);
        if (cached != null && cached.version() >= minVersion) {
            return cached;
        }
        Authorization a;
        try {
            a = conversations.get().uri("/internal/v1/conversations/{id}/members", conversationId).retrieve().body(Authorization.class);
        } catch (RestClientException e) {
            throw new RetryableDependencyException("conversation-service unavailable", e);
        }
        Members m = new Members(a.type(), a.membershipVersion(), a.memberUserIds() == null ? List.of() : a.memberUserIds());
        writeCache(membersKey(conversationId), m, membersTtl);
        return m;
    }

    public Map<UUID, List<UUID>> activeDevices(List<UUID> userIds) {
        Map<UUID, List<UUID>> out = new HashMap<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID u : userIds) {
            UUID[] c = readCache(devicesKey(u), UUID[].class);
            if (c != null) {
                out.put(u, List.of(c));
            } else {
                missing.add(u);
            }
        }
        for (int i = 0; i < missing.size(); i += 500) {
            List<UUID> batch = missing.subList(i, Math.min(missing.size(), i + 500));
            Map<UUID, List<UUID>> fetched;
            try {
                fetched = users.post().uri("/internal/v1/devices:active").body(Map.of("userIds", batch)).retrieve()
                        .body(new ParameterizedTypeReference<Map<UUID, List<UUID>>>() { });
            } catch (RestClientException e) {
                throw new RetryableDependencyException("user-service unavailable", e);
            }
            for (UUID u : batch) {
                List<UUID> ds = fetched == null ? List.of() : fetched.getOrDefault(u, List.of());
                out.put(u, ds);
                writeCache(devicesKey(u), ds.toArray(UUID[]::new), devicesTtl);
            }
        }
        return out;
    }

    public void invalidateConversation(UUID conversationId) {
        try {
            redis.delete(membersKey(conversationId));
        } catch (DataAccessException ignored) {
            // TTL bounds staleness; version check still applies
        }
    }

    public void invalidateUserDevices(UUID userId) {
        try {
            redis.delete(devicesKey(userId));
        } catch (DataAccessException ignored) {
            // TTL bounds staleness
        }
    }

    /** Duplicate-event short-circuit (correctness does not depend on it: deliveryIds are deterministic). */
    public boolean alreadyFannedOut(UUID messageId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey("conversation:fanout:" + messageId));
        } catch (DataAccessException e) {
            return false;
        }
    }

    public void markFannedOut(UUID messageId, Duration ttl) {
        try {
            redis.opsForValue().set("conversation:fanout:" + messageId, "1", ttl);
        } catch (DataAccessException ignored) {
            // duplicates would only republish identical delivery tasks
        }
    }

    private <T> T readCache(String key, Class<T> type) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? null : EventCodec.mapper().readValue(v, type);
        } catch (RuntimeException e) {
            return null;    // Redis down → authoritative lookup
        }
    }

    private void writeCache(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, EventCodec.mapper().writeValueAsString(value), ttl);
        } catch (RuntimeException ignored) {
            // cache is optional
        }
    }
}
