package com.securechat.gateway;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Device → owning gateway registry in Redis (ephemeral, rebuildable). Value
 * {@code gatewayId|connectionId|userId|connectedAtMillis} with a TTL refreshed by heartbeats, so a
 * crashed gateway's mappings expire on their own. All mutations are Lua scripts (atomic).
 */
@Component
public class ConnectionRegistry {
    public static String key(UUID deviceId) {
        return "connection:device:" + deviceId;
    }

    public static String gatewaySet(String gatewayId) {
        return "gateway:" + gatewayId + ":connections";
    }

    public static String channel(String gatewayId) {
        return "gw:" + gatewayId;
    }

    /** Registers (replacing any previous owner) and returns the previous value or null. */
    private static final DefaultRedisScript<String> REGISTER = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[4])
            return old
            """, String.class);

    /** Extends the TTL only if this connection still owns the device. */
    private static final DefaultRedisScript<Long> HEARTBEAT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if v and string.sub(v, 1, string.len(ARGV[1])) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    /** Removes the mapping only if this connection still owns it (a newer connection may have replaced it). */
    private static final DefaultRedisScript<Long> UNREGISTER = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[2])
            if v and string.sub(v, 1, string.len(ARGV[1])) == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final GatewayProperties props;

    ConnectionRegistry(StringRedisTemplate redis, GatewayProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public record Owner(String gatewayId, String connectionId, UUID userId) {
        static Owner parse(String v) {
            if (v == null) {
                return null;
            }
            String[] p = v.split("\\|");
            return p.length < 3 ? null : new Owner(p[0], p[1], UUID.fromString(p[2]));
        }
    }

    private String prefix(String connectionId) {
        return props.id() + "|" + connectionId + "|";
    }

    public Owner register(UUID deviceId, UUID userId, String connectionId) {
        long ttl = props.connectionTtl().toMillis();
        String old = redis.execute(REGISTER, List.of(key(deviceId), gatewaySet(props.id())),
                prefix(connectionId) + userId + "|" + System.currentTimeMillis(), String.valueOf(ttl), deviceId.toString(),
                String.valueOf(ttl * 3));
        return Owner.parse(old);
    }

    public boolean heartbeat(UUID deviceId, String connectionId) {
        Long r = redis.execute(HEARTBEAT, List.of(key(deviceId)), prefix(connectionId), String.valueOf(props.connectionTtl().toMillis()));
        return r != null && r == 1;
    }

    public void unregister(UUID deviceId, String connectionId) {
        redis.execute(UNREGISTER, List.of(key(deviceId), gatewaySet(props.id())), prefix(connectionId), deviceId.toString());
    }

    public Owner owner(UUID deviceId) {
        return Owner.parse(redis.opsForValue().get(key(deviceId)));
    }

    public void markAlive() {
        redis.opsForValue().set("gateway:" + props.id() + ":alive", String.valueOf(System.currentTimeMillis()), Duration.ofSeconds(30));
        redis.expire(gatewaySet(props.id()), props.connectionTtl().multipliedBy(3));
    }

    public void publish(String gatewayId, String json) {
        redis.convertAndSend(channel(gatewayId), json);
    }

    public void publishTyping(String json) {
        redis.convertAndSend("typing", json);
    }
}
