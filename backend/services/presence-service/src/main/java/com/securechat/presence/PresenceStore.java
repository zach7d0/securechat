package com.securechat.presence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis presence model (ephemeral, rebuildable from gateway heartbeats):
 * <ul>
 *   <li>{@code presence:device:{d}} = userId|gatewayId with TTL (device lease);</li>
 *   <li>{@code presence:user:{u}:devices} ZSET deviceId → lease expiry (ms);</li>
 *   <li>{@code presence:user:{u}:state} "1"/"0" aggregate; {@code presence:user:{u}:lastseen} ms;</li>
 *   <li>{@code presence:online} ZSET userId → latest device expiry (drives the expiry sweep).</li>
 * </ul>
 * Every transition is one Lua script, so concurrent connects/disconnects/heartbeats of a user's
 * devices cannot interleave into a wrong aggregate. A user is online iff ≥ 1 device lease is live.
 */
@Component
public class PresenceStore {
    /** Returns {becameOnline(0/1), activeDevices}. */
    private static final DefaultRedisScript<List> TOUCH = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1]); local ttl = tonumber(ARGV[2])
            redis.call('SET', KEYS[1], ARGV[4] .. '|' .. ARGV[5], 'PX', ttl)
            redis.call('ZADD', KEYS[2], now + ttl, ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            redis.call('PEXPIRE', KEYS[2], ttl * 2)
            local cnt = redis.call('ZCARD', KEYS[2])
            local top = redis.call('ZRANGE', KEYS[2], -1, -1, 'WITHSCORES')
            redis.call('ZADD', KEYS[4], tonumber(top[2]), ARGV[4])
            local prev = redis.call('GET', KEYS[3])
            redis.call('SET', KEYS[3], '1')
            if prev == '1' then return {0, cnt} end
            return {1, cnt}
            """, List.class);

    /** Disconnect of one device by the gateway that owns it. Returns {becameOffline, activeDevices, applied}. */
    private static final DefaultRedisScript<List> DISCONNECT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local v = redis.call('GET', KEYS[1])
            if v then
              local gw = string.match(v, '|(.*)$')
              if gw ~= ARGV[3] then return {0, redis.call('ZCARD', KEYS[2]), 0} end
              redis.call('DEL', KEYS[1])
            end
            redis.call('ZREM', KEYS[2], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            local cnt = redis.call('ZCARD', KEYS[2])
            if cnt == 0 and redis.call('GET', KEYS[3]) == '1' then
              redis.call('SET', KEYS[3], '0')
              redis.call('SET', KEYS[5], tostring(now))
              redis.call('ZREM', KEYS[4], ARGV[4])
              return {1, 0, 1}
            end
            return {0, cnt, 1}
            """, List.class);

    /** Recompute after lease expiry. Returns {becameOffline, activeDevices}. */
    private static final DefaultRedisScript<List> RECOMPUTE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            local cnt = redis.call('ZCARD', KEYS[1])
            if cnt == 0 then
              redis.call('ZREM', KEYS[3], ARGV[2])
              if redis.call('GET', KEYS[2]) == '1' then
                redis.call('SET', KEYS[2], '0')
                redis.call('SET', KEYS[4], ARGV[3])
                return {1, 0}
              end
              return {0, 0}
            end
            local top = redis.call('ZRANGE', KEYS[1], -1, -1, 'WITHSCORES')
            redis.call('ZADD', KEYS[3], tonumber(top[2]), ARGV[2])
            return {0, cnt}
            """, List.class);

    static final String ONLINE = "presence:online";
    private final StringRedisTemplate redis;
    private final long ttlMs;

    PresenceStore(StringRedisTemplate redis, @Value("${securechat.presence.device-ttl:45s}") Duration ttl) {
        this.redis = redis;
        this.ttlMs = ttl.toMillis();
    }

    static String device(UUID d) {
        return "presence:device:" + d;
    }

    static String devices(UUID u) {
        return "presence:user:" + u + ":devices";
    }

    static String state(UUID u) {
        return "presence:user:" + u + ":state";
    }

    static String lastSeen(UUID u) {
        return "presence:user:" + u + ":lastseen";
    }

    public record Transition(boolean changed, long activeDevices) {
    }

    public Transition touch(UUID userId, UUID deviceId, String gatewayId) {
        List<?> r = redis.execute(TOUCH, List.of(device(deviceId), devices(userId), state(userId), ONLINE),
                String.valueOf(System.currentTimeMillis()), String.valueOf(ttlMs), deviceId.toString(), userId.toString(), gatewayId);
        return new Transition(num(r.get(0)) == 1, num(r.get(1)));
    }

    public record Disconnect(boolean wentOffline, long activeDevices, boolean applied) {
    }

    public Disconnect disconnect(UUID userId, UUID deviceId, String gatewayId) {
        List<?> r = redis.execute(DISCONNECT, List.of(device(deviceId), devices(userId), state(userId), ONLINE, lastSeen(userId)),
                String.valueOf(System.currentTimeMillis()), deviceId.toString(), gatewayId, userId.toString());
        return new Disconnect(num(r.get(0)) == 1, num(r.get(1)), num(r.get(2)) == 1);
    }

    /** Users whose newest device lease has expired (candidates for offline). */
    public Set<String> expiredUsers(int limit) {
        return redis.opsForZSet().rangeByScore(ONLINE, Double.NEGATIVE_INFINITY, System.currentTimeMillis(), 0, limit);
    }

    public Transition recompute(UUID userId) {
        long now = System.currentTimeMillis();
        List<?> r = redis.execute(RECOMPUTE, List.of(devices(userId), state(userId), ONLINE, lastSeen(userId)),
                String.valueOf(now), userId.toString(), String.valueOf(now));
        return new Transition(num(r.get(0)) == 1, num(r.get(1)));
    }

    public record View(boolean online, long activeDevices, Long lastSeenMillis) {
    }

    public View view(UUID userId) {
        long now = System.currentTimeMillis();
        Long active = redis.opsForZSet().count(devices(userId), now, Double.POSITIVE_INFINITY);
        String ls = redis.opsForValue().get(lastSeen(userId));
        long n = active == null ? 0 : active;
        return new View(n > 0, n, ls == null ? null : Long.parseLong(ls));
    }

    public long onlineUsers() {
        Long n = redis.opsForZSet().count(ONLINE, System.currentTimeMillis(), Double.POSITIVE_INFINITY);
        return n == null ? 0 : n;
    }

    /** Gateways that registered connections but stopped advertising liveness (crashed). */
    public List<String> deadGateways() {
        List<String> dead = new ArrayList<>();
        try (Cursor<String> c = redis.scan(ScanOptions.scanOptions().match("gateway:*:connections").count(200).build())) {
            while (c.hasNext()) {
                String k = c.next();
                String id = k.substring("gateway:".length(), k.length() - ":connections".length());
                if (Boolean.FALSE.equals(redis.hasKey("gateway:" + id + ":alive"))) {
                    dead.add(id);
                }
            }
        }
        return dead;
    }

    /** Removes connection mappings still owned by a dead gateway; returns removed device ids. */
    public List<UUID> reconcileGateway(String gatewayId) {
        String set = "gateway:" + gatewayId + ":connections";
        List<UUID> removed = new ArrayList<>();
        Set<String> members = redis.opsForSet().members(set);
        if (members != null) {
            for (String d : members) {
                String key = "connection:device:" + d;
                String v = redis.opsForValue().get(key);
                if (v != null && v.startsWith(gatewayId + "|")) {
                    redis.delete(key);
                    removed.add(UUID.fromString(d));
                }
            }
        }
        redis.delete(set);
        return removed;
    }

    /** userId|gatewayId of a live device lease, or null. */
    public String deviceLease(UUID deviceId) {
        return redis.opsForValue().get(device(deviceId));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }
}
