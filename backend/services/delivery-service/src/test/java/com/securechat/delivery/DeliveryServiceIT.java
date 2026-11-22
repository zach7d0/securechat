package com.securechat.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.EventType;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.DeviceConnection;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.ids.UuidV7;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "securechat.delivery.sweep-interval=500ms", "securechat.delivery.retry-base=1s", "securechat.delivery.expiry=6s"})
class DeliveryServiceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.cassandraProperties(r, "securechat_delivery");
        Infra.redisProperties(r);
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
    }

    static KafkaProducer<String, String> producer;
    static KafkaConsumer<String, String> consumer;
    static final List<EventEnvelope> EVENTS = new CopyOnWriteArrayList<>();

    @Autowired
    Environment env;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    RedisConnectionFactory cf;
    @Autowired
    DeliveryStore store;
    Http http;
    /** Frames that reached the simulated gateway "gw-test". */
    final List<JsonNode> gatewayFrames = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer gateway;

    @BeforeAll
    static void clients() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new KafkaConsumer<>(c, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(Topics.MESSAGE_DELIVERED, Topics.MESSAGE_READ, Topics.MESSAGE_FAILED, Topics.NOTIFICATION_REQUESTED));
    }

    @AfterAll
    static void close() {
        producer.close();
        consumer.close();
    }

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
        gateway = new RedisMessageListenerContainer();
        gateway.setConnectionFactory(cf);
        gateway.addMessageListener((m, pat) -> gatewayFrames.add(EventCodec.mapper().readTree(new String(m.getBody(), StandardCharsets.UTF_8))),
                new ChannelTopic("gw:gw-test"));
        gateway.afterPropertiesSet();
        gateway.start();
    }

    @org.junit.jupiter.api.AfterEach
    void stopGateway() throws Exception {
        gateway.stop();
        gateway.destroy();
    }

    static synchronized List<EventEnvelope> events(String type, UUID messageId, Duration wait) {
        long end = System.currentTimeMillis() + wait.toMillis();
        do {
            consumer.poll(Duration.ofMillis(200)).forEach(r -> EVENTS.add(EventCodec.fromJson(r.value())));
        } while (System.currentTimeMillis() < end);
        return EVENTS.stream().filter(e -> e.eventType().equals(type) && e.payload().path("messageId").asString().equals(messageId.toString()))
                .toList();
    }

    void online(UUID device) {
        redis.opsForValue().set("connection:device:" + device, "gw-test|conn|" + UUID.randomUUID() + "|1", Duration.ofMinutes(5));
    }

    void offline(UUID device) {
        redis.delete("connection:device:" + device);
    }

    static UUID task(UUID messageId, UUID recipientUser, UUID device, UUID sender) {
        DeliveryRequested t = new DeliveryRequested(DeterministicIds.deliveryId(messageId, device), messageId, UUID.randomUUID(), sender,
                UUID.randomUUID(), recipientUser, device, "SIGNAL", "SIGNAL_WHISPER", 1, null, "Q0lQSEVS",
                Instant.ofEpochMilli(UuidV7.timestampMillis(messageId)));
        producer.send(new ProducerRecord<>(Topics.MESSAGE_DELIVERY_REQUESTED, device.toString(), EventCodec.toJson(EventCodec.envelope(
                EventType.MESSAGE_DELIVERY_REQUESTED, DeterministicIds.deliveryRequestedEventId(t.deliveryId()), t.deliveryId().toString(),
                t, "c", null, "test"))));
        producer.flush();
        return t.deliveryId();
    }

    String status(UUID messageId, UUID device) {
        return store.receipt(messageId, device).map(DeliveryStore.Receipt::status).orElse("NONE");
    }

    long pendingRows(UUID device) {
        long n = 0;
        for (var b : store.buckets(device)) {
            n += store.pending(device, b, 1000).size();
        }
        return n;
    }

    Http.Response ack(UUID user, UUID device, String status, UUID... ids) {
        return http.internal("POST", "/internal/v1/delivery/acks", Map.of("userId", user, "deviceId", device, "status", status,
                "messageIds", List.of(ids)));
    }

    List<JsonNode> framesFor(UUID messageId) {
        return gatewayFrames.stream().filter(f -> f.path("frame").path("messageId").asString().equals(messageId.toString())).toList();
    }

    @Test
    void offlineMessageIsHeldDurablyAndDeliveredAfterReconnect() {
        UUID bob = UUID.randomUUID();
        UUID bobDevice = UUID.randomUUID();
        UUID mid = UuidV7.generate();
        task(mid, bob, bobDevice, UUID.randomUUID());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> pendingRows(bobDevice) == 1);
        assertThat(status(mid, bobDevice)).isEqualTo("PENDING");
        assertThat(events("notification.requested", mid, Duration.ofSeconds(3))).hasSize(1);
        assertThat(framesFor(mid)).isEmpty();
        // Bob reconnects: presence emits device.connected → replay
        online(bobDevice);
        producer.send(new ProducerRecord<>(Topics.DEVICE_CONNECTED, bobDevice.toString(), EventCodec.toJson(EventCodec.envelope(
                EventType.DEVICE_CONNECTED, UUID.randomUUID(), bobDevice.toString(), new DeviceConnection(bob, bobDevice, "gw-test", Instant.now()),
                "c", null, "t"))));
        producer.flush();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !framesFor(mid).isEmpty());
        JsonNode f = framesFor(mid).get(0).get("frame");
        assertThat(f.get("ciphertext").asString()).isEqualTo("Q0lQSEVS");
        assertThat(status(mid, bobDevice)).as("routing is not delivery").isEqualTo("PENDING");
        assertThat(ack(bob, bobDevice, "DELIVERED", mid).status()).isEqualTo(204);
        assertThat(status(mid, bobDevice)).isEqualTo("DELIVERED");
        assertThat(pendingRows(bobDevice)).isZero();
        assertThat(events("message.delivered", mid, Duration.ofSeconds(3))).hasSize(1);
    }

    @Test
    void duplicateAcksAndDuplicateTasksAreHarmless() {
        UUID bob = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        online(d);
        UUID mid = UuidV7.generate();
        task(mid, bob, d, UUID.randomUUID());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !framesFor(mid).isEmpty());
        ack(bob, d, "DELIVERED", mid);
        ack(bob, d, "DELIVERED", mid);
        ack(bob, d, "DELIVERED", mid);
        assertThat(events("message.delivered", mid, Duration.ofSeconds(3))).hasSize(1);
        // the same task arrives again (republished event): no new pending row, no re-route
        int framesBefore = framesFor(mid).size();
        task(mid, bob, d, UUID.randomUUID());
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(pendingRows(d)).isZero();
        assertThat(framesFor(mid)).hasSize(framesBefore);
        assertThat(status(mid, d)).isEqualTo("DELIVERED");
    }

    @Test
    void deliveryStateIsPerDeviceAndReadFollowsDelivered() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID laptop = UUID.randomUUID();
        UUID phone = UUID.randomUUID();
        UUID mid = UuidV7.generate();
        online(laptop);
        task(mid, bob, laptop, alice);
        task(mid, bob, phone, alice);          // phone offline
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !"NONE".equals(status(mid, phone)) && !framesFor(mid).isEmpty());
        ack(bob, laptop, "DELIVERED", mid);
        assertThat(status(mid, laptop)).isEqualTo("DELIVERED");
        assertThat(status(mid, phone)).isEqualTo("PENDING");
        ack(bob, laptop, "READ", mid);
        assertThat(status(mid, laptop)).isEqualTo("READ");
        assertThat(events("message.read", mid, Duration.ofSeconds(3))).hasSize(1);
        // a device cannot acknowledge on behalf of another user's device / a message not addressed to it
        UUID mallory = UUID.randomUUID();
        ack(mallory, phone, "DELIVERED", mid);
        ack(bob, UUID.randomUUID(), "DELIVERED", mid);
        assertThat(status(mid, phone)).isEqualTo("PENDING");
        // only the sender can read the receipts
        assertThat(http.get("/api/v1/messages/" + mid + "/receipts", TestTokens.token(alice, "a", UUID.randomUUID())).body().size())
                .isEqualTo(2);
        assertThat(http.get("/api/v1/messages/" + mid + "/receipts", TestTokens.token(bob, "b", laptop)).status()).isEqualTo(404);
    }

    @Test
    void unacknowledgedOnlineDeliveryIsRetriedWithBackoff() {
        UUID bob = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        online(d);
        UUID mid = UuidV7.generate();
        task(mid, bob, d, UUID.randomUUID());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> framesFor(mid).size() >= 3);
        var p = store.pending(d, store.buckets(d).get(0), 10).get(0);
        assertThat(p.attempts()).isGreaterThanOrEqualTo(3);
        ack(bob, d, "DELIVERED", mid);
        int after = framesFor(mid).size();
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(framesFor(mid).size()).as("no retries after ACK").isEqualTo(after);
    }

    @Test
    void undeliveredMessagesExpireToFailedAndPendingIsNotFailure() {
        UUID bob = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID mid = UuidV7.generate();
        task(mid, bob, d, UUID.randomUUID());
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "PENDING".equals(status(mid, d)));
        assertThat(events("message.failed", mid, Duration.ofSeconds(1))).isEmpty();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> "FAILED".equals(status(mid, d)));
        assertThat(store.receipt(mid, d).orElseThrow().failureReason()).isEqualTo("EXPIRED");
        assertThat(pendingRows(d)).isZero();
        assertThat(events("message.failed", mid, Duration.ofSeconds(3))).hasSize(1);
        // a late ACK after expiry does not resurrect it
        ack(bob, d, "DELIVERED", mid);
        assertThat(status(mid, d)).isEqualTo("FAILED");
    }

    @Test
    void revokedDevicePendingWorkFailsTerminally() {
        UUID bob = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<UUID> mids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID mid = UuidV7.generate();
            mids.add(mid);
            task(mid, bob, d, UUID.randomUUID());
        }
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> pendingRows(d) == 3);
        producer.send(new ProducerRecord<>(Topics.DEVICE_REVOKED, bob.toString(), EventCodec.toJson(EventCodec.envelope(EventType.DEVICE_REVOKED,
                UUID.randomUUID(), d.toString(), new DeviceRevoked(bob, d, "USER", Instant.now()), "c", null, "t"))));
        producer.flush();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> pendingRows(d) == 0);
        for (UUID mid : mids) {
            assertThat(store.receipt(mid, d).orElseThrow().failureReason()).isEqualTo("DEVICE_REVOKED");
        }
    }

    @Test
    void pendingSurvivesAndIsReplayedInCreationOrder() {
        UUID bob = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        List<UUID> mids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID mid = UuidV7.at(System.currentTimeMillis() + i);
            mids.add(mid);
            task(mid, bob, d, UUID.randomUUID());
        }
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> pendingRows(d) == 5);
        online(d);
        assertThat(http.internal("POST", "/internal/v1/delivery/replay", Map.of("deviceId", d)).body().get("replayed").asInt()).isEqualTo(5);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> mids.stream().allMatch(m -> !framesFor(m).isEmpty()));
        List<String> order = gatewayFrames.stream().map(f -> f.path("frame").path("messageId").asString())
                .filter(id -> mids.stream().anyMatch(m -> m.toString().equals(id))).distinct().toList();
        assertThat(order).containsExactlyElementsOf(mids.stream().map(UUID::toString).toList());
        offline(d);
    }
}
