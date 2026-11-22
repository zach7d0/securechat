package com.securechat.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventType;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.Envelope;
import com.securechat.contracts.events.FanoutCompleted;
import com.securechat.contracts.events.MessageAccepted;
import com.securechat.contracts.events.MessageFailed;
import com.securechat.contracts.events.MessageReceipt;
import com.securechat.support.ids.UuidV7;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitoringIT {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "monitoring_db");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        r.add("securechat.security.jwks-uri", () -> "http://127.0.0.1:1/unused");
    }

    static final String SECRET_CT = "U0VDUkVULUNJUEhFUlRFWFQ=";
    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    Http http;
    KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer());
    }

    /** Publishes every event TWICE (at-least-once upstream). */
    void twice(EventType t, UUID eventId, Object payload) {
        String json = EventCodec.toJson(EventCodec.envelope(t, eventId, "agg", payload, "corr-mon", "trace-1", "test"));
        producer.send(new ProducerRecord<>(t.topic(), "k", json));
        producer.send(new ProducerRecord<>(t.topic(), "k", json));
        producer.flush();
    }

    void accepted(UUID mid, UUID conv) {
        Instant now = Instant.now();
        twice(EventType.MESSAGE_ACCEPTED, DeterministicIds.acceptedEventId(mid), new MessageAccepted(mid, conv, "GROUP", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "MLS", "MLS_APPLICATION", 2L, 1, now, now, now, List.of(new Envelope(UUID.randomUUID(), 1, SECRET_CT)),
                SECRET_CT));
    }

    void requested(UUID mid, UUID conv, UUID device) {
        UUID did = DeterministicIds.deliveryId(mid, device);
        twice(EventType.MESSAGE_DELIVERY_REQUESTED, DeterministicIds.deliveryRequestedEventId(did), new DeliveryRequested(did, mid, conv,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), device, "MLS", "MLS_APPLICATION", null, 2L, SECRET_CT, Instant.now()));
    }

    void receipt(EventType t, UUID mid, UUID conv, UUID device) {
        String status = t == EventType.MESSAGE_READ ? "READ" : "DELIVERED";
        twice(t, DeterministicIds.receiptEventId(mid, device, status), new MessageReceipt(mid, conv, UUID.randomUUID(), UUID.randomUUID(),
                device, status, Instant.now()));
    }

    JsonNode trace(UUID mid) {
        return http.internal("GET", "/internal/v1/monitoring/messages/" + mid, null).body();
    }

    @Test
    void groupMessageLifecycleIsTrackedPerDeviceWithoutDoubleCounting() {
        UUID conv = UUID.randomUUID();
        UUID mid = UuidV7.generate();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        accepted(mid, conv);
        twice(EventType.MESSAGE_FANOUT_COMPLETED, DeterministicIds.fanoutCompletedEventId(mid),
                new FanoutCompleted(mid, conv, 3, List.of(d1, d2, d3), List.of(), Instant.now()));
        for (UUID d : List.of(d1, d2, d3)) {
            requested(mid, conv, d);
        }
        receipt(EventType.MESSAGE_DELIVERED, mid, conv, d1);
        receipt(EventType.MESSAGE_DELIVERED, mid, conv, d2);
        receipt(EventType.MESSAGE_READ, mid, conv, d1);
        Awaitility.await().atMost(Duration.ofSeconds(40)).until(() -> {
            JsonNode t = http.internal("GET", "/internal/v1/monitoring/messages/" + mid, null).body();
            return t != null && t.has("read_devices") && t.get("read_devices").asInt() == 1 && t.get("delivered_devices").asInt() == 2;
        });
        JsonNode t = trace(mid);
        assertThat(t.get("expected_devices").asInt()).isEqualTo(3);
        assertThat(t.get("pending_devices").asInt()).as("pending is not failure").isEqualTo(1);
        assertThat(t.get("failed_devices").asInt()).isZero();
        java.util.Set<String> milestones = new java.util.HashSet<>();
        t.get("milestones").forEach(m -> milestones.add(m.get("milestone").asString()));
        assertThat(milestones).containsAll(List.of("RECEIVED", "PERSISTED", "ACCEPTED", "PUBLISHED", "FANOUT_COMPLETED", "DELIVERY_PENDING",
                "DELIVERED", "READ"));
        assertThat(t.get("milestones").size()).as("duplicates not stored twice").isEqualTo(4 + 1 + 3 + 2 + 1);
        assertThat(t.get("milestones").get(0).get("correlation_id").asString()).isEqualTo("corr-mon");
        java.util.Map<String, String> byDevice = new java.util.HashMap<>();
        t.get("devices").forEach(d -> byDevice.put(d.get("device_id").asString(), d.get("status").asString()));
        assertThat(byDevice).containsEntry(d1.toString(), "READ").containsEntry(d2.toString(), "DELIVERED").containsEntry(d3.toString(), "PENDING");
    }

    @Test
    void outOfOrderEventsConvergeAndFailuresAreCategorised() {
        UUID conv = UUID.randomUUID();
        UUID mid = UuidV7.generate();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        receipt(EventType.MESSAGE_DELIVERED, mid, conv, d1);           // receipt arrives first
        twice(EventType.MESSAGE_FAILED, DeterministicIds.receiptEventId(mid, d2, "FAILED"),
                new MessageFailed(mid, conv, UUID.randomUUID(), d2, "EXPIRED", Instant.now()));
        requested(mid, conv, d1);                                     // late pending must not downgrade DELIVERED
        accepted(mid, conv);
        Awaitility.await().atMost(Duration.ofSeconds(40)).until(() -> {
            JsonNode t = http.internal("GET", "/internal/v1/monitoring/messages/" + mid, null).body();
            return t != null && t.has("accepted_at") && !t.get("accepted_at").isNull() && t.get("failed_devices").asInt() == 1
                    && t.get("delivered_devices").asInt() == 1;
        });
        JsonNode t = trace(mid);
        assertThat(t.get("pending_devices").asInt()).isZero();
        JsonNode report = http.internal("GET", "/internal/v1/monitoring/reports/summary", null).body();
        assertThat(report.get("failure_categories").toString()).contains("EXPIRED");
    }

    @Test
    void aggregateReportsReconcileWithLifecycleRecordsAndNoCiphertextIsStored() {
        UUID conv = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            UUID mid = UuidV7.generate();
            UUID d = UUID.randomUUID();
            accepted(mid, conv);
            requested(mid, conv, d);
            if (i % 2 == 0) {
                receipt(EventType.MESSAGE_DELIVERED, mid, conv, d);
            }
        }
        Awaitility.await().atMost(Duration.ofSeconds(40)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM message_device_delivery d JOIN message_delivery_summary s USING (message_id) WHERE s.conversation_id = ? "
                        + "AND d.status = 'DELIVERED'", Long.class, conv) == 3);
        // reconciliation: hourly counters equal the per-record facts (duplicates did not inflate)
        long acceptedRecords = jdbc.queryForObject("SELECT count(*) FROM message_delivery_summary WHERE accepted_at IS NOT NULL", Long.class);
        long acceptedCounted = jdbc.queryForObject("SELECT coalesce(sum(accepted_count),0) FROM message_metrics_hourly", Long.class);
        long deliveredRecords = jdbc.queryForObject("SELECT count(*) FROM message_device_delivery WHERE delivered_at IS NOT NULL", Long.class);
        long deliveredCounted = jdbc.queryForObject("SELECT coalesce(sum(delivered_device_count),0) FROM message_metrics_hourly", Long.class);
        long failedRecords = jdbc.queryForObject("SELECT count(*) FROM message_device_delivery WHERE status = 'FAILED'", Long.class);
        long failedCounted = jdbc.queryForObject("SELECT coalesce(sum(failed_device_count),0) FROM message_metrics_hourly", Long.class);
        assertThat(acceptedCounted).isEqualTo(acceptedRecords);
        assertThat(deliveredCounted).isEqualTo(deliveredRecords);
        assertThat(failedCounted).isEqualTo(failedRecords);
        JsonNode s = http.internal("GET", "/internal/v1/monitoring/reports/summary", null).body();
        assertThat(s.get("accepted").asLong()).isEqualTo(acceptedRecords);
        assertThat(http.internal("GET", "/internal/v1/monitoring/messages?conversationId=" + conv, null).body().size()).isEqualTo(5);
        // the projection never stores ciphertext
        String dump = "" + jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT * FROM message_lifecycle_events) t", String.class)
                + jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT * FROM message_delivery_summary) t", String.class)
                + jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT * FROM message_device_delivery) t", String.class);
        assertThat(dump).doesNotContain(SECRET_CT);
        assertThat(http.get("/internal/v1/monitoring/reports/summary", null).status()).isIn(401, 403);
    }
}
