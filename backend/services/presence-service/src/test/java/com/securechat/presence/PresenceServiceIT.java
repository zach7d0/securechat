package com.securechat.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "securechat.presence.device-ttl=2s", "securechat.presence.sweep-interval=500ms"})
class PresenceServiceIT {
    static final Map<UUID, List<UUID>> PEERS = new ConcurrentHashMap<>();
    static String convUrl;

    static {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/users/", ex -> {
                UUID u = UUID.fromString(ex.getRequestURI().getPath().split("/")[4]);
                byte[] b = EventCodec.mapper().writeValueAsBytes(PEERS.getOrDefault(u, List.of()));
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            convUrl = "http://127.0.0.1:" + s.getAddress().getPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.redisProperties(r);
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
        r.add("securechat.internal.conversation-url", () -> convUrl);
    }

    @Autowired
    Environment env;
    @Autowired
    PresenceService presence;
    @Autowired
    StringRedisTemplate redis;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
    }

    void connect(UUID u, UUID d, String gw) {
        assertThat(http.internal("POST", "/internal/v1/presence/connect", Map.of("userId", u, "deviceId", d, "gatewayId", gw)).status())
                .isEqualTo(204);
    }

    void disconnect(UUID u, UUID d, String gw) {
        http.internal("POST", "/internal/v1/presence/disconnect", Map.of("userId", u, "deviceId", d, "gatewayId", gw));
    }

    String status(UUID observer, UUID subject) {
        PEERS.put(observer, List.of(subject));
        var r = http.get("/api/v1/presence?userIds=" + subject, TestTokens.token(observer, "o", null));
        return r.body().isEmpty() ? "HIDDEN" : r.body().get(0).get("status").asString();
    }

    List<EventEnvelope> transitions(UUID user, Duration window) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<EventEnvelope> out = new ArrayList<>();
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.PRESENCE_CHANGED));
            long end = System.currentTimeMillis() + window.toMillis();
            while (System.currentTimeMillis() < end) {
                c.poll(Duration.ofMillis(300)).forEach(rec -> {
                    EventEnvelope e = EventCodec.fromJson(rec.value());
                    if (e.aggregateId().equals(user.toString())) {
                        out.add(e);
                    }
                });
            }
        }
        return out;
    }

    @Test
    void userStaysOnlineUntilLastDeviceDisconnects() {
        UUID u = UUID.randomUUID();
        UUID laptop = UUID.randomUUID();
        UUID phone = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        connect(u, laptop, "gw-a");
        connect(u, phone, "gw-b");
        assertThat(status(observer, u)).isEqualTo("ONLINE");
        disconnect(u, laptop, "gw-a");
        assertThat(status(observer, u)).isEqualTo("ONLINE");        // one device left
        disconnect(u, phone, "gw-b");
        assertThat(status(observer, u)).isEqualTo("OFFLINE");
        List<EventEnvelope> ev = transitions(u, Duration.ofSeconds(4));
        assertThat(ev).extracting(e -> e.payload().get("online").asBoolean()).containsExactly(true, false);
        assertThat(ev.get(1).payload().get("lastSeen").isNull()).isFalse();
    }

    @Test
    void concurrentConnectDisconnectRacesConvergeToTheCorrectAggregate() throws Exception {
        UUID u = UUID.randomUUID();
        List<UUID> devices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            devices.add(UUID.randomUUID());
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<?>> fs = new ArrayList<>();
        for (UUID d : devices) {
            fs.add(pool.submit(() -> {
                connect(u, d, "gw-a");
                disconnect(u, d, "gw-a");
                connect(u, d, "gw-a");
            }));
        }
        for (var f : fs) {
            f.get();
        }
        assertThat(presence.query(UUID.randomUUID(), List.of())).isEmpty();
        PresenceStore store = new PresenceStore(redis, Duration.ofSeconds(2));
        assertThat(store.view(u).activeDevices()).isEqualTo(20);
        List<UUID> shuffled = new ArrayList<>(devices);
        Collections.shuffle(shuffled);
        fs.clear();
        for (UUID d : shuffled.subList(0, 19)) {
            fs.add(pool.submit(() -> disconnect(u, d, "gw-a")));
        }
        for (var f : fs) {
            f.get();
        }
        assertThat(store.view(u).online()).isTrue();
        assertThat(store.view(u).activeDevices()).isEqualTo(1);
        disconnect(u, shuffled.get(19), "gw-a");
        assertThat(store.view(u).online()).isFalse();
        pool.shutdown();
        // Atomicity invariant: transitions strictly alternate (never two "offline" or two "online" in a row,
        // which would mean a lost update) and end in the true final state. A brief offline moment during
        // the concurrent connect/disconnect/connect burst is legitimate, so the count is not fixed.
        List<Boolean> seq = transitions(u, Duration.ofSeconds(4)).stream().map(e -> e.payload().get("online").asBoolean()).toList();
        assertThat(seq).isNotEmpty().startsWith(true).endsWith(false);
        for (int i = 1; i < seq.size(); i++) {
            assertThat(seq.get(i)).as("transition %d of %s", i, seq).isNotEqualTo(seq.get(i - 1));
        }
    }

    @Test
    void ttlExpiryDetectsUnexpectedDisconnectAndHeartbeatsKeepAlive() {
        UUID u = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        UUID vanished = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        connect(u, alive, "gw-a");
        connect(v2, vanished, "gw-dead");
        for (int i = 0; i < 6; i++) {                       // 3 s > 2 s TTL
            http.internal("POST", "/internal/v1/presence/heartbeat", Map.of("gatewayId", "gw-a",
                    "devices", List.of(Map.of("userId", u, "deviceId", alive))));
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(status(observer, u)).isEqualTo("ONLINE");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> "OFFLINE".equals(status(observer, v2)));
    }

    @Test
    void lateDisconnectFromPreviousGatewayDoesNotKickTheNewConnection() {
        UUID u = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        connect(u, d, "gw-a");
        connect(u, d, "gw-b");          // reconnected to another gateway
        disconnect(u, d, "gw-a");       // old gateway's disconnect arrives late
        assertThat(status(UUID.randomUUID(), u)).isEqualTo("ONLINE");
    }

    @Test
    void stateIsRebuiltFromHeartbeatsAfterRedisLosesIt() {
        UUID u = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        connect(u, d, "gw-a");
        // Redis restart without persistence: all presence keys gone
        redis.delete(List.of(PresenceStore.device(d), PresenceStore.devices(u), PresenceStore.state(u)));
        redis.opsForZSet().remove(PresenceStore.ONLINE, u.toString());
        assertThat(status(observer, u)).isEqualTo("OFFLINE");
        http.internal("POST", "/internal/v1/presence/heartbeat", Map.of("gatewayId", "gw-a", "devices", List.of(Map.of("userId", u, "deviceId", d))));
        assertThat(status(observer, u)).isEqualTo("ONLINE");
        // online → (state lost) → online again after the rebuild; a later offline is expected once the 2 s TTL lapses
        assertThat(transitions(u, Duration.ofSeconds(4))).extracting(e -> e.payload().get("online").asBoolean()).startsWith(true, true);
    }

    @Test
    void redisOutageReportsUnknownNotOffline() {
        UUID u = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        PEERS.put(observer, List.of(u));
        Infra.pause(Infra.redis());
        try {
            var r = http.get("/api/v1/presence?userIds=" + u, TestTokens.token(observer, "o", null));
            assertThat(r.body().get(0).get("status").asString()).isEqualTo("UNKNOWN");
        } finally {
            Infra.unpause(Infra.redis());
        }
    }

    @Test
    void presenceIsVisibleOnlyToConversationPeers() {
        UUID u = UUID.randomUUID();
        connect(u, UUID.randomUUID(), "gw-a");
        UUID stranger = UUID.randomUUID();
        PEERS.put(stranger, List.of());
        assertThat(http.get("/api/v1/presence?userIds=" + u, TestTokens.token(stranger, "s", null)).body().size()).isZero();
        assertThat(http.get("/api/v1/presence?userIds=" + u, null).status()).isEqualTo(401);
    }

    @Test
    void deadGatewayMappingsAreReconciled() {
        UUID d = UUID.randomUUID();
        redis.opsForSet().add("gateway:gw-crashed:connections", d.toString());
        redis.opsForValue().set("connection:device:" + d, "gw-crashed|c|" + UUID.randomUUID() + "|1");
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> redis.opsForValue().get("connection:device:" + d) == null);
        assertThat(redis.hasKey("gateway:gw-crashed:connections")).isFalse();
        // a live gateway's mappings are untouched
        UUID live = UUID.randomUUID();
        redis.opsForValue().set("gateway:gw-live:alive", "1", Duration.ofSeconds(30));
        redis.opsForSet().add("gateway:gw-live:connections", live.toString());
        redis.opsForValue().set("connection:device:" + live, "gw-live|c|" + UUID.randomUUID() + "|1");
        presence.sweep();
        assertThat(redis.opsForValue().get("connection:device:" + live)).startsWith("gw-live|");
        assertThat(new String("ok".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo("ok");
    }
}
