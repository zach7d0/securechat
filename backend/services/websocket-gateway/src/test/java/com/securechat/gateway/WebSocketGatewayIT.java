package com.securechat.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventType;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.contracts.events.MessageReceipt;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;

/** Two real gateway instances (A, B) sharing Redis + Kafka; downstream services stubbed over HTTP. */
class WebSocketGatewayIT {
    static final String ORIGIN = "http://chat.test";
    static DownstreamStub stub;
    static ConfigurableApplicationContext gwA;
    static ConfigurableApplicationContext gwB;
    static KafkaProducer<String, String> kafka;
    static final Duration T = Duration.ofSeconds(10);

    static ConfigurableApplicationContext start(String id) {
        Map<String, Object> p = new HashMap<>();
        p.put("server.port", "0");
        p.put("management.server.port", "0");
        p.put("securechat.gateway.id", id);
        p.put("securechat.gateway.allowed-origins", ORIGIN);
        p.put("securechat.gateway.auth-timeout", "1s");
        p.put("securechat.gateway.connection-ttl", "4s");
        p.put("securechat.gateway.token-grace", "1s");
        p.put("securechat.gateway.maintenance-interval", "1s");
        p.put("securechat.security.internal-token", Infra.INTERNAL_TOKEN);
        p.put("securechat.security.jwks-uri", TestTokens.jwksUrl());
        for (String k : List.of("user", "message", "delivery", "presence", "conversation")) {
            p.put("securechat.internal." + k + "-url", stub.url);
        }
        p.put("spring.data.redis.host", Infra.redis().getHost());
        p.put("spring.data.redis.port", Infra.redis().getMappedPort(6379));
        p.put("spring.data.redis.username", "default");
        p.put("spring.data.redis.password", Infra.REDIS_PASSWORD);
        p.put("spring.kafka.bootstrap-servers", Infra.kafka().getBootstrapServers());
        p.put("logging.structured.format.console", "");
        return new SpringApplicationBuilder(GatewayApplication.class)
                .run(p.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    static int port(ConfigurableApplicationContext c) {
        return Integer.parseInt(c.getEnvironment().getProperty("local.server.port"));
    }

    @BeforeAll
    static void boot() throws Exception {
        stub = new DownstreamStub();
        gwA = start("gw-a");
        gwB = start("gw-b");
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        kafka = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    @AfterAll
    static void stop() {
        kafka.close();
        if (gwB != null && gwB.isActive()) {
            gwB.close();
        }
        gwA.close();
    }

    record Dev(UUID user, UUID device, String token) {
    }

    static Dev device(UUID user) {
        UUID d = UUID.randomUUID();
        stub.devices.put(d, new String[] {user.toString(), "ACTIVE"});
        return new Dev(user, d, TestTokens.token(user, "u", d));
    }

    static WsClient connected(ConfigurableApplicationContext gw, Dev d) throws InterruptedException {
        WsClient c = WsClient.connect(port(gw), ORIGIN).auth(d.token());
        assertThat(c.await("ready", T)).as("ready").isNotNull();
        return c;
    }

    static StringRedisTemplate redis() {
        return gwA.getBean(StringRedisTemplate.class);
    }

    static void publish(EventType t, String key, Object payload) {
        kafka.send(new ProducerRecord<>(t.topic(), key, EventCodec.toJson(EventCodec.envelope(t, UUID.randomUUID(), key, payload,
                "c", null, "test"))));
        kafka.flush();
    }

    @Test
    void rejectsMissingInvalidExpiredAndUnboundTokensAndForeignOrigins() throws Exception {
        UUID u = UUID.randomUUID();
        Dev d = device(u);
        assertThat(WsClient.connect(port(gwA), ORIGIN).closeCode(T)).isEqualTo(4401);                       // no auth frame in time
        assertThat(WsClient.connect(port(gwA), ORIGIN).auth("not-a-jwt").closeCode(T)).isEqualTo(4401);
        assertThat(WsClient.connect(port(gwA), ORIGIN).auth(TestTokens.token(u, "u", d.device(), "securechat-auth", "securechat",
                Instant.now().minusSeconds(120))).closeCode(T)).isEqualTo(4401);                                // expired
        assertThat(WsClient.connect(port(gwA), ORIGIN).auth(TestTokens.token(u, "u", null)).closeCode(T)).isEqualTo(4401); // no device
        WsClient early = WsClient.connect(port(gwA), ORIGIN).send("{\"type\":\"send\"}");                   // frame before auth
        assertThat(early.closeCode(T)).isEqualTo(4401);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> WsClient.connect(port(gwA), "https://evil.example"));
    }

    @Test
    void revokedOrForeignDeviceIsRefusedAtConnect() throws Exception {
        UUID u = UUID.randomUUID();
        Dev d = device(u);
        stub.devices.put(d.device(), new String[] {u.toString(), "REVOKED"});
        WsClient c = WsClient.connect(port(gwA), ORIGIN).auth(d.token());
        assertThat(c.await("revoked", T)).isNotNull();
        assertThat(c.closeCode(T)).isEqualTo(4403);
        Dev other = device(UUID.randomUUID());
        WsClient forged = WsClient.connect(port(gwA), ORIGIN).auth(TestTokens.token(u, "u", other.device()));
        assertThat(forged.closeCode(T)).isEqualTo(4403);
    }

    @Test
    void connectionIsRegisteredAndIngressUsesTokenIdentityNotFrameClaims() throws Exception {
        Dev d = device(UUID.randomUUID());
        WsClient c = connected(gwA, d);
        String owner = redis().opsForValue().get("connection:device:" + d.device());
        assertThat(owner).startsWith("gw-a|").contains("|" + d.user() + "|");
        Awaitility.await().atMost(T).until(() -> stub.presenceCalls.contains("connect:" + d.device()));
        UUID conv = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        c.send("{\"type\":\"send\",\"clientMessageId\":\"" + clientId + "\",\"conversationId\":\"" + conv + "\",\"protocol\":\"MLS\","
                + "\"ciphertext\":\"AAEC\",\"epoch\":3,\"senderUserId\":\"" + UUID.randomUUID() + "\"}");
        JsonNode acc = c.await("accepted", T);
        assertThat(acc.get("clientMessageId").asString()).isEqualTo(clientId.toString());
        JsonNode sent = stub.submitted.stream().filter(b -> b.get("clientMessageId").asString().equals(clientId.toString())).findFirst().orElseThrow();
        assertThat(sent.get("senderUserId").asString()).isEqualTo(d.user().toString());       // spoofed field ignored
        assertThat(sent.get("senderDeviceId").asString()).isEqualTo(d.device().toString());
        assertThat(sent.get("ciphertext").asString()).isEqualTo("AAEC");                        // relayed opaque
        // downstream rejection is relayed with its code
        c.send("{\"type\":\"send\",\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"conversationId\":\""
                + DownstreamStub.FORBIDDEN_CONVERSATION + "\",\"protocol\":\"MLS\",\"ciphertext\":\"AAEC\",\"epoch\":1}");
        assertThat(c.await("error", T).get("code").asString()).isEqualTo("NOT_A_MEMBER");
        // acks carry the authenticated device
        UUID mid = UUID.randomUUID();
        c.send("{\"type\":\"ack\",\"status\":\"DELIVERED\",\"messageIds\":[\"" + mid + "\"]}");
        Awaitility.await().atMost(T).until(() -> stub.acks.stream().anyMatch(a -> a.get("messageIds").get(0).asString().equals(mid.toString())
                && a.get("deviceId").asString().equals(d.device().toString())));
        // heartbeat
        c.send("{\"type\":\"ping\"}");
        assertThat(c.await("pong", T)).isNotNull();
    }

    @Test
    void sameDeviceNewestConnectionWinsOnSameAndAcrossGateways() throws Exception {
        Dev d = device(UUID.randomUUID());
        WsClient tab1 = connected(gwA, d);
        WsClient tab2 = connected(gwA, d);
        assertThat(tab1.closeCode(T)).isEqualTo(4409);
        WsClient onB = connected(gwB, d);
        assertThat(tab2.closeCode(T)).isEqualTo(4409);
        assertThat(redis().opsForValue().get("connection:device:" + d.device())).startsWith("gw-b|");
        onB.send("{\"type\":\"ping\"}");
        assertThat(onB.await("pong", T)).isNotNull();
    }

    @Test
    void crossGatewayRoutingDeliversToOwningGatewayOnly() throws Exception {
        Dev d = device(UUID.randomUUID());
        WsClient c = connected(gwB, d);
        String frame = "{\"type\":\"message\",\"messageId\":\"" + UUID.randomUUID() + "\",\"ciphertext\":\"AAEC\"}";
        redis().convertAndSend("gw:gw-a", "{\"type\":\"deliver\",\"deviceId\":\"" + d.device() + "\",\"frame\":" + frame + "}");
        assertThat(c.await("message", Duration.ofSeconds(2))).as("wrong gateway must not deliver").isNull();
        redis().convertAndSend("gw:gw-b", "{\"type\":\"deliver\",\"deviceId\":\"" + d.device() + "\",\"frame\":" + frame + "}");
        assertThat(c.await("message", T).get("ciphertext").asString()).isEqualTo("AAEC");
    }

    @Test
    void multipleDevicesOfAUserOnDifferentGatewaysAllGetReceipts() throws Exception {
        UUID alice = UUID.randomUUID();
        WsClient laptop = connected(gwA, device(alice));
        WsClient phone = connected(gwB, device(alice));
        UUID mid = UUID.randomUUID();
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            publish(EventType.MESSAGE_DELIVERED, mid.toString(), new MessageReceipt(mid, UUID.randomUUID(), alice, UUID.randomUUID(),
                    UUID.randomUUID(), "DELIVERED", Instant.now()));
            return laptop.frames.stream().anyMatch(f -> f.path("messageId").asString().equals(mid.toString()))
                    && phone.frames.stream().anyMatch(f -> f.path("messageId").asString().equals(mid.toString()));
        });
    }

    @Test
    void typingIsRelayedToOtherMembersAcrossGateways() throws Exception {
        Dev a = device(UUID.randomUUID());
        Dev b = device(UUID.randomUUID());
        UUID conv = UUID.randomUUID();
        stub.conversations.put(conv, List.of(a.user(), b.user()));
        WsClient ca = connected(gwA, a);
        WsClient cb = connected(gwB, b);
        ca.send("{\"type\":\"typing\",\"conversationId\":\"" + conv + "\",\"state\":\"start\"}");
        JsonNode t = cb.await("typing", T);
        assertThat(t.get("userId").asString()).isEqualTo(a.user().toString());
        assertThat(t.has("members")).isFalse();
        assertThat(ca.await("typing", Duration.ofSeconds(1))).isNull();      // not echoed to the sender
    }

    @Test
    void revokedDeviceEventDisconnectsLiveSocket() throws Exception {
        Dev d = device(UUID.randomUUID());
        WsClient c = connected(gwB, d);
        Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            publish(EventType.DEVICE_REVOKED, d.user().toString(), new DeviceRevoked(d.user(), d.device(), "USER", Instant.now()));
            return c.closed.isDone();
        });
        assertThat(c.closeCode(T)).isEqualTo(4403);
    }

    @Test
    void tokenRefreshKeepsConnectionButOtherDeviceTokenIsRejected() throws Exception {
        Dev d = device(UUID.randomUUID());
        WsClient c = connected(gwA, d);
        c.auth(TestTokens.token(d.user(), "u", d.device()));
        assertThat(c.await("ready", T)).isNotNull();
        c.auth(TestTokens.token(d.user(), "u", device(d.user()).device()));
        assertThat(c.closeCode(T)).isEqualTo(4401);
    }

    @Test
    void staleMappingsOfACrashedGatewayExpire() throws Exception {
        UUID ghost = UUID.randomUUID();
        // a gateway that crashed without cleanup leaves a mapping that nobody refreshes
        redis().opsForValue().set("connection:device:" + ghost, "gw-dead|c1|" + UUID.randomUUID() + "|1", Duration.ofSeconds(4));
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> redis().opsForValue().get("connection:device:" + ghost) == null);
        // live connections keep their lease beyond the TTL thanks to heartbeats
        Dev d = device(UUID.randomUUID());
        WsClient c = connected(gwA, d);
        for (int i = 0; i < 6; i++) {
            Thread.sleep(1000);
            c.send("{\"type\":\"ping\"}");
        }
        assertThat(redis().opsForValue().get("connection:device:" + d.device())).startsWith("gw-a|");
    }

    @Test
    void gatewayShutdownClosesSocketsForReconnectAndRemovesMappings() throws Exception {
        ConfigurableApplicationContext gwC = start("gw-c");
        Dev d = device(UUID.randomUUID());
        WsClient c = connected(gwC, d);
        assertThat(redis().opsForValue().get("connection:device:" + d.device())).startsWith("gw-c|");
        gwC.close();
        assertThat(c.closeCode(T)).isEqualTo(1012);
        Awaitility.await().atMost(T).until(() -> redis().opsForValue().get("connection:device:" + d.device()) == null);
        // the client reconnects to another healthy gateway
        connected(gwA, d);
        assertThat(redis().opsForValue().get("connection:device:" + d.device())).startsWith("gw-a|");
    }
}
