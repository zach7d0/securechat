package com.securechat.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.EventType;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.Envelope;
import com.securechat.contracts.events.GroupMembershipChanged;
import com.securechat.contracts.events.MessageAccepted;
import com.securechat.support.ids.UuidV7;
import com.securechat.testsupport.Infra;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties = "securechat.fanout.membership-cache-ttl=10m")
class FanoutServiceIT {
    /** conversationId → [type, version, members...]; userId → devices; dependency failure switch. */
    static final Map<UUID, Object[]> CONVERSATIONS = new ConcurrentHashMap<>();
    static final Map<UUID, List<UUID>> DEVICES = new ConcurrentHashMap<>();
    static final AtomicBoolean DOWN = new AtomicBoolean();
    static String stubUrl;
    static KafkaProducer<String, String> producer;
    static KafkaConsumer<String, String> deliveries;
    static final Map<UUID, List<JsonNode>> BY_MESSAGE = new ConcurrentHashMap<>();

    static {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                byte[] in = ex.getRequestBody().readAllBytes();
                byte[] out;
                int status = 200;
                if (DOWN.get()) {
                    status = 503;
                    out = "{}".getBytes();
                } else if (path.endsWith("/members")) {
                    Object[] c = CONVERSATIONS.get(UUID.fromString(path.split("/")[4]));
                    out = EventCodec.mapper().writeValueAsBytes(Map.of("member", false, "type", c[0], "membershipVersion", c[1],
                            "memberUserIds", c[2]));
                } else {
                    JsonNode body = EventCodec.mapper().readTree(in);
                    Map<String, List<UUID>> m = new HashMap<>();
                    body.get("userIds").forEach(u -> m.put(u.asString(), DEVICES.getOrDefault(UUID.fromString(u.asString()), List.of())));
                    out = EventCodec.mapper().writeValueAsBytes(m);
                }
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            s.start();
            stubUrl = "http://127.0.0.1:" + s.getAddress().getPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.redisProperties(r);
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        r.add("securechat.security.jwks-uri", () -> "http://127.0.0.1:1/unused");
        r.add("securechat.internal.conversation-url", () -> stubUrl);
        r.add("securechat.internal.user-url", () -> stubUrl);
    }

    @BeforeAll
    static void clients() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        deliveries = new KafkaConsumer<>(c, new StringDeserializer(), new StringDeserializer());
        deliveries.subscribe(List.of(Topics.MESSAGE_DELIVERY_REQUESTED, Topics.MESSAGE_FANOUT_COMPLETED));
    }

    @AfterAll
    static void close() {
        producer.close();
        deliveries.close();
    }

    /** Collects delivery tasks (and fanout.completed under key "completed:"+messageId) for a while. */
    static synchronized List<JsonNode> tasks(UUID messageId, Duration wait) {
        long end = System.currentTimeMillis() + wait.toMillis();
        while (System.currentTimeMillis() < end) {
            deliveries.poll(Duration.ofMillis(200)).forEach(r -> {
                EventEnvelope e = EventCodec.fromJson(r.value());
                UUID mid = UUID.fromString(e.payload().get("messageId").asString());
                ((tools.jackson.databind.node.ObjectNode) e.payload()).put("_type", e.eventType());
                BY_MESSAGE.computeIfAbsent(mid, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(e.payload());
            });
        }
        return BY_MESSAGE.getOrDefault(messageId, List.of());
    }

    static List<JsonNode> deliveryTasks(UUID messageId, Duration wait) {
        return tasks(messageId, wait).stream().filter(n -> n.get("_type").asString().equals("message.delivery.requested")).toList();
    }

    static JsonNode completed(UUID messageId) {
        return tasks(messageId, Duration.ZERO).stream().filter(n -> n.get("_type").asString().equals("message.fanout.completed"))
                .findFirst().orElse(null);
    }

    static void publish(EventType t, UUID eventId, String key, Object payload) {
        producer.send(new ProducerRecord<>(t.topic(), key, EventCodec.toJson(EventCodec.envelope(t, eventId, key, payload, "c", null, "t"))));
        producer.flush();
    }

    static UUID accept(UUID conv, String type, UUID sender, UUID senderDevice, long version, List<Envelope> envs) {
        UUID mid = UuidV7.generate();
        MessageAccepted m = new MessageAccepted(mid, conv, type, sender, senderDevice, UUID.randomUUID(), envs == null ? "MLS" : "SIGNAL",
                envs == null ? "MLS_APPLICATION" : "SIGNAL_WHISPER", envs == null ? 3L : null, version, Instant.now(), Instant.now(),
                Instant.now(), envs, envs == null ? "R1JPVVA=" : null);
        publish(EventType.MESSAGE_ACCEPTED, DeterministicIds.acceptedEventId(mid), conv.toString(), m);
        return mid;
    }

    static List<UUID> devs(UUID user, int n) {
        List<UUID> ds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ds.add(UUID.randomUUID());
        }
        DEVICES.put(user, ds);
        return ds;
    }

    static Set<String> recipients(List<JsonNode> tasks) {
        return tasks.stream().map(t -> t.get("recipientDeviceId").asString()).collect(Collectors.toSet());
    }

    @Test
    void directMessageReachesRecipientDevicesAndSendersOtherDevicesButNotOrigin() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        List<UUID> a = devs(alice, 2);
        List<UUID> b = devs(bob, 2);
        UUID revokedBobTablet = UUID.randomUUID();
        UUID conv = UUID.randomUUID();
        CONVERSATIONS.put(conv, new Object[] {"DIRECT", 1, List.of(alice, bob)});
        // Alice's laptop encrypted for Bob's 2 devices, her own phone, and a since-revoked tablet
        UUID mid = accept(conv, "DIRECT", alice, a.get(0), 1, List.of(new Envelope(b.get(0), 3, "QjA="), new Envelope(b.get(1), 3, "QjE="),
                new Envelope(a.get(1), 1, "QTE="), new Envelope(revokedBobTablet, 1, "WA==")));
        List<JsonNode> t = deliveryTasks(mid, Duration.ofSeconds(8));
        assertThat(recipients(t)).containsExactlyInAnyOrder(b.get(0).toString(), b.get(1).toString(), a.get(1).toString());
        for (JsonNode n : t) {
            UUID dev = UUID.fromString(n.get("recipientDeviceId").asString());
            assertThat(n.get("deliveryId").asString()).isEqualTo(DeterministicIds.deliveryId(mid, dev).toString());
            assertThat(n.get("ciphertext").asString()).isEqualTo(dev.equals(b.get(0)) ? "QjA=" : dev.equals(b.get(1)) ? "QjE=" : "QTE=");
        }
        JsonNode done = completed(mid);
        assertThat(done.get("expectedDevices").asInt()).isEqualTo(3);
        assertThat(done.get("skippedDevices").toString()).contains(revokedBobTablet.toString()).contains("DEVICE_INACTIVE");
    }

    @Test
    void groupMessageFansOutToCurrentMembersOnly() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        List<UUID> a = devs(alice, 2);
        devs(bob, 2);
        List<UUID> c = devs(carol, 2);
        UUID conv = UUID.randomUUID();
        CONVERSATIONS.put(conv, new Object[] {"GROUP", 2, List.of(alice, bob, carol)});
        UUID m1 = accept(conv, "GROUP", alice, a.get(0), 2, null);
        List<JsonNode> t1 = deliveryTasks(m1, Duration.ofSeconds(8));
        assertThat(t1).hasSize(5);
        assertThat(t1).allMatch(n -> n.get("ciphertext").asString().equals("R1JPVVA=") && n.get("protocol").asString().equals("MLS"));
        // Carol removed: membership event invalidates the cache
        CONVERSATIONS.put(conv, new Object[] {"GROUP", 3, List.of(alice, bob)});
        publish(EventType.GROUP_MEMBER_REMOVED, UUID.randomUUID(), conv.toString(),
                new GroupMembershipChanged(conv, List.of(carol), List.of(alice, bob), 3, 4, alice, Instant.now()));
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        UUID m2 = accept(conv, "GROUP", alice, a.get(0), 3, null);
        List<JsonNode> t2 = deliveryTasks(m2, Duration.ofSeconds(8));
        assertThat(t2).hasSize(3);
        assertThat(recipients(t2)).doesNotContain(c.get(0).toString(), c.get(1).toString());
    }

    @Test
    void staleCacheIsBypassedWhenEventCarriesNewerMembershipVersion() {
        UUID alice = UUID.randomUUID();
        UUID dave = UUID.randomUUID();
        List<UUID> a = devs(alice, 1);
        List<UUID> d = devs(dave, 1);
        UUID conv = UUID.randomUUID();
        CONVERSATIONS.put(conv, new Object[] {"GROUP", 1, List.of(alice, dave)});
        UUID m1 = accept(conv, "GROUP", alice, a.get(0), 1, null);
        assertThat(deliveryTasks(m1, Duration.ofSeconds(8))).hasSize(1);      // cached at version 1
        // Dave removed but the invalidation event has NOT arrived; Message Service authorized at version 2
        CONVERSATIONS.put(conv, new Object[] {"GROUP", 2, List.of(alice)});
        UUID m2 = accept(conv, "GROUP", alice, a.get(0), 2, null);
        assertThat(deliveryTasks(m2, Duration.ofSeconds(6))).noneMatch(n -> n.get("recipientDeviceId").asString().equals(d.get(0).toString()));
        assertThat(completed(m2).get("expectedDevices").asInt()).isZero();
    }

    @Test
    void duplicateAcceptedEventsDoNotCreateDuplicateLogicalDeliveries() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        List<UUID> a = devs(alice, 1);
        List<UUID> b = devs(bob, 1);
        UUID conv = UUID.randomUUID();
        CONVERSATIONS.put(conv, new Object[] {"DIRECT", 1, List.of(alice, bob)});
        UUID mid = UuidV7.generate();
        MessageAccepted m = new MessageAccepted(mid, conv, "DIRECT", alice, a.get(0), UUID.randomUUID(), "SIGNAL", "SIGNAL_WHISPER", null, 1,
                Instant.now(), Instant.now(), Instant.now(), List.of(new Envelope(b.get(0), 1, "QQ==")), null);
        for (int i = 0; i < 3; i++) {     // outbox republished the same event
            publish(EventType.MESSAGE_ACCEPTED, DeterministicIds.acceptedEventId(mid), conv.toString(), m);
        }
        List<JsonNode> t = deliveryTasks(mid, Duration.ofSeconds(8));
        assertThat(t.stream().map(n -> n.get("deliveryId").asString()).distinct().toList())
                .containsExactly(DeterministicIds.deliveryId(mid, b.get(0)).toString());
        assertThat(t).hasSize(1);        // done-marker short-circuits replays
    }

    @Test
    void dependencyOutageRetriesInsteadOfSkipping() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        List<UUID> a = devs(alice, 1);
        devs(bob, 1);
        UUID conv = UUID.randomUUID();
        CONVERSATIONS.put(conv, new Object[] {"GROUP", 1, List.of(alice, bob)});
        DOWN.set(true);
        UUID mid;
        try {
            mid = accept(conv, "GROUP", alice, a.get(0), 1, null);
            assertThat(deliveryTasks(mid, Duration.ofSeconds(5))).isEmpty();
        } finally {
            DOWN.set(false);
        }
        assertThat(deliveryTasks(mid, Duration.ofSeconds(20))).hasSize(1);
    }

    @Test
    void largeGroupFansOutToEveryDevice() {
        List<UUID> members = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            UUID u = UUID.randomUUID();
            members.add(u);
            devs(u, 1);
        }
        UUID conv = UUID.randomUUID();
        CONVERSATIONS.put(conv, new Object[] {"GROUP", 1, members});
        UUID mid = accept(conv, "GROUP", members.get(0), DEVICES.get(members.get(0)).get(0), 1, null);
        long start = System.currentTimeMillis();
        List<JsonNode> t = deliveryTasks(mid, Duration.ofSeconds(10));
        assertThat(t).hasSize(199);
        assertThat(t.stream().map(n -> n.get("deliveryId").asString()).distinct().count()).isEqualTo(199);
        assertThat(System.currentTimeMillis() - start).isLessThan(15_000);
    }
}
