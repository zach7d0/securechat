package com.securechat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.testsupport.Infra;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Failure injection for the Cassandra → Kafka outbox. The scheduled publisher is disabled; tests
 * drive publisher instances by hand so "crashes" (an instance that stops without releasing its
 * lease) and restarts are deterministic. Kafka outages are real (docker pause of the broker).
 */
@SpringBootTest(properties = {
        "securechat.messages.outbox-publisher-enabled=false",
        "securechat.messages.outbox-lease=6s",
        "securechat.messages.outbox-quarantine-grace=1s",
        "securechat.kafka.publish-timeout=3s",
        "spring.kafka.producer.properties.delivery.timeout.ms=3000",
        "spring.kafka.producer.properties.request.timeout.ms=2000",
        "spring.kafka.producer.properties.max.block.ms=3000"})
class OutboxReliabilityIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.cassandraProperties(r, "securechat_message");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        r.add("securechat.security.jwks-uri", () -> "http://127.0.0.1:1/unused");
    }

    @Autowired
    MessageService messages;
    @Autowired
    MessageStore store;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    MessageProperties props;
    @MockitoBean
    Dependencies deps;

    UUID user = UUID.randomUUID();
    UUID device = UUID.randomUUID();
    UUID peerDevice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(deps.authorize(any(), any())).thenReturn(new Dependencies.Authorization(true, "DIRECT", "MEMBER", 1, null, Instant.now(),
                List.of(user)));
        when(deps.device(any())).thenReturn(new Dependencies.DeviceStatus(device, user, "ACTIVE"));
        when(deps.readyDevices(anyList())).thenReturn(null);
    }

    final List<CassandraOutboxPublisher> created = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void releaseAll() {
        created.forEach(CassandraOutboxPublisher::releaseLeases);
    }

    CassandraOutboxPublisher publisher(MeterRegistry m) {
        CassandraOutboxPublisher p = new CassandraOutboxPublisher(store, eventPublisher, props, m, true);
        created.add(p);
        return p;
    }

    UUID accept(UUID conversation) {
        var r = messages.submit(user, device, new MessageService.SubmitRequest(conversation, UUID.randomUUID(), "SIGNAL", null,
                List.of(new com.securechat.contracts.events.Envelope(peerDevice, 1, Base64.getEncoder().encodeToString(new byte[24]))),
                null, null));
        assertThat(r.status()).isEqualTo("ACCEPTED");
        return r.messageId();
    }

    List<MessageStore.OutboxRow> pendingFor(Set<UUID> messageIds) {
        List<MessageStore.OutboxRow> out = new ArrayList<>();
        Instant h = MessageStore.hour(Instant.now());
        for (int s = 0; s < props.outboxShards(); s++) {
            for (Instant b : List.of(h.minus(Duration.ofHours(1)), h)) {
                store.scanOutbox(b, s, 5000).stream().filter(r -> messageIds.contains(r.messageId())).forEach(out::add);
            }
        }
        return out;
    }

    static Map<String, Long> countByAggregate(List<EventEnvelope> es) {
        return es.stream().collect(Collectors.groupingBy(EventEnvelope::aggregateId, Collectors.counting()));
    }

    @Test
    void kafkaOutageLeavesRecoverableEventsAndOrderIsPreservedPerConversation() {
        UUID conv = UUID.randomUUID();
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        CassandraOutboxPublisher p = publisher(m);
        List<UUID> ids;
        Infra.pause(Infra.kafka());
        try {
            // Cassandra is healthy: messages are ACCEPTED even though Kafka is down
            ids = List.of(accept(conv), accept(conv), accept(conv));
            p.poll();
            List<MessageStore.OutboxRow> pending = pendingFor(Set.copyOf(ids));
            assertThat(pending).hasSize(3);
            assertThat(pending.stream().mapToInt(MessageStore.OutboxRow::attempts).max().orElse(0)).isEqualTo(1);
            assertThat(m.get("outbox.pending").gauge().value()).isGreaterThanOrEqualTo(3);
            assertThat(m.get("outbox.oldest.pending.age").gauge().value()).isGreaterThanOrEqualTo(0);
            assertThat(m.get("outbox.publish").tag("result", "failure").counter().count()).isGreaterThanOrEqualTo(1);
        } finally {
            Infra.unpause(Infra.kafka());
        }
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            p.poll();
            return pendingFor(Set.copyOf(ids)).isEmpty();
        });
        Set<String> want = ids.stream().map(UUID::toString).collect(Collectors.toSet());
        List<EventEnvelope> events = TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> want.contains(e.aggregateId()), Duration.ofSeconds(6));
        // At-least-once: a send that timed out while the broker was frozen may still have been appended
        // when it resumed, so a duplicate is legitimate. Every event is present, identities are stable
        // (eventId derived from messageId) and first occurrences keep per-conversation order.
        assertThat(countByAggregate(events).keySet()).containsExactlyInAnyOrderElementsOf(want);
        for (EventEnvelope e : events) {
            assertThat(e.eventId()).isEqualTo(DeterministicIds.acceptedEventId(UUID.fromString(e.aggregateId())));
        }
        assertThat(events.stream().map(EventEnvelope::aggregateId).distinct().toList())
                .containsExactlyElementsOf(ids.stream().map(UUID::toString).toList());
        p.releaseLeases();
    }

    @Test
    void crashedPublisherLeaseExpiresAndAnotherInstanceResumes() {
        CassandraOutboxPublisher a = publisher(new SimpleMeterRegistry());
        CassandraOutboxPublisher b = publisher(new SimpleMeterRegistry());
        UUID id;
        Infra.pause(Infra.kafka());
        try {
            id = accept(UUID.randomUUID());
            a.poll();                         // A owns every shard, publish fails
        } finally {
            Infra.unpause(Infra.kafka());
        }
        // A "crashes" (no lease release). B cannot take over until the lease expires.
        created.remove(a);
        b.poll();
        assertThat(pendingFor(Set.of(id))).hasSize(1);
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(700)).until(() -> {
            b.poll();
            return pendingFor(Set.of(id)).isEmpty();
        });
        List<EventEnvelope> copies = TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> e.aggregateId().equals(id.toString()),
                Duration.ofSeconds(5));
        assertThat(copies).isNotEmpty();   // ≥ 1: the timed-out attempt during the pause may also have landed
        assertThat(copies.stream().map(EventEnvelope::eventId).distinct()).containsExactly(DeterministicIds.acceptedEventId(id));
    }

    @Test
    void crashAfterKafkaAckBeforeMarkerRepublishesWithStableEventIdentity() {
        CassandraOutboxPublisher p = publisher(new SimpleMeterRegistry());
        UUID id = accept(UUID.randomUUID());
        MessageStore.OutboxRow row = pendingFor(Set.of(id)).get(0);
        // the crashed publisher had already published (Kafka ACKed) but never wrote its marker
        eventPublisher.sendRaw(Topics.MESSAGE_ACCEPTED, row.conversationId().toString(), new String(row.payload(), StandardCharsets.UTF_8), null);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            p.poll();
            return pendingFor(Set.of(id)).isEmpty();
        });
        List<EventEnvelope> copies = TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> e.aggregateId().equals(id.toString()), Duration.ofSeconds(5));
        assertThat(copies).hasSize(2);   // duplicate publication is possible (at-least-once) …
        assertThat(copies.stream().map(EventEnvelope::eventId).distinct().toList())
                .containsExactly(DeterministicIds.acceptedEventId(id));   // … but the event identity is stable for dedupe
        p.releaseLeases();
    }

    @Test
    void crashAfterMarkerBeforeDeleteDoesNotRepublish() {
        CassandraOutboxPublisher p = publisher(new SimpleMeterRegistry());
        UUID id = accept(UUID.randomUUID());
        MessageStore.OutboxRow row = pendingFor(Set.of(id)).get(0);
        store.markPublished(row);       // marker written, then crash before the delete
        p.poll();
        assertThat(pendingFor(Set.of(id))).isEmpty();
        assertThat(TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> e.aggregateId().equals(id.toString()), Duration.ofSeconds(4))).isEmpty();
        p.releaseLeases();
    }

    @Test
    void outboxRowWithoutMessageIsQuarantinedNotPublishedAndRecoverable() throws Exception {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        CassandraOutboxPublisher p = publisher(m);
        UUID conv = UUID.randomUUID();
        UUID messageId = com.securechat.support.ids.UuidV7.generate();
        Instant created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        // crash between the outbox write and the message write: client never got ACCEPTED
        store.writeOutbox(messages.shard(conv), DeterministicIds.acceptedEventId(messageId), messageId, conv, MessageStore.day(created),
                created, "message.accepted", "{\"eventId\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);
        p.poll();
        List<MessageStore.OutboxRow> rows = pendingFor(Set.of(messageId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("QUARANTINED");
        assertThat(m.get("outbox.quarantine").counter().count()).isEqualTo(1);
        p.poll();
        assertThat(m.get("outbox.quarantined").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> e.aggregateId().equals(messageId.toString()), Duration.ofSeconds(3))).isEmpty();
        // operator recovery: requeue → still not published while the message is missing (re-quarantined)
        assertThat(p.requeueQuarantined()).isGreaterThanOrEqualTo(1);
        p.releaseLeases();
    }
}
