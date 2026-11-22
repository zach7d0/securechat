package com.securechat.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.support.web.ApiException;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/** Context closed after the class so its scheduled publisher releases its shard leases (@PreDestroy). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext
class MessageServiceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.cassandraProperties(r, "securechat_message");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
        r.add("spring.cassandra.request.timeout", () -> "3s");
    }

    @Autowired
    Environment env;
    @Autowired
    CqlSession cql;
    @Autowired
    MessageStore store;
    @MockitoBean
    Dependencies deps;
    Http http;

    record Party(UUID user, UUID device, String token) {
    }

    UUID conv;
    Party alice;
    Party bob;

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
        conv = UUID.randomUUID();
        alice = party();
        bob = party();
        when(deps.authorize(eq(conv), any())).thenAnswer(inv -> {
            UUID u = inv.getArgument(1);
            boolean member = u.equals(alice.user()) || u.equals(bob.user());
            return new Dependencies.Authorization(member, "DIRECT", "MEMBER", 1, null, Instant.now(), List.of(alice.user(), bob.user()));
        });
        when(deps.device(any())).thenAnswer(inv -> {
            UUID d = inv.getArgument(0);
            if (d.equals(alice.device())) {
                return new Dependencies.DeviceStatus(d, alice.user(), "ACTIVE");
            }
            return d.equals(bob.device()) ? new Dependencies.DeviceStatus(d, bob.user(), "ACTIVE") : null;
        });
        when(deps.readyDevices(anyList())).thenAnswer(inv -> Map.of(alice.user(), List.of(alice.device()), bob.user(), List.of(bob.device())));
    }

    static Party party() {
        UUID u = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        return new Party(u, d, TestTokens.token(u, "p", d));
    }

    static String ct(int n) {
        byte[] b = new byte[n];
        new java.util.Random().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    Map<String, Object> signal(UUID clientId, String ciphertext) {
        return Map.of("clientMessageId", clientId, "protocol", "SIGNAL",
                "envelopes", List.of(Map.of("deviceId", bob.device(), "signalType", 3, "ciphertext", ciphertext)));
    }

    Http.Response send(Party p, Map<String, Object> body) {
        return http.post("/api/v1/conversations/" + conv + "/messages", body, p.token());
    }

    @Test
    void ciphertextIsPersistedAndAcceptedEventPublished() {
        String ciphertext = ct(300);
        Http.Response r = send(alice, signal(UUID.randomUUID(), ciphertext));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("ACCEPTED");
        UUID id = UUID.fromString(r.text("messageId"));
        assertThat(id.version()).isEqualTo(7);
        var row = cql.execute("SELECT ciphertext, encryption_protocol, sender_device_id FROM messages_by_conversation "
                + "WHERE conversation_id = ? AND bucket_date = ?", conv, LocalDate.now(java.time.ZoneOffset.UTC)).one();
        ByteBuffer blob = row.getByteBuffer(0);
        String stored = java.nio.charset.StandardCharsets.UTF_8.decode(blob).toString();
        assertThat(stored).contains(ciphertext).contains(bob.device().toString());
        assertThat(row.getUuid(2)).isEqualTo(alice.device());
        List<EventEnvelope> events = TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> e.aggregateId().equals(id.toString()),
                Duration.ofSeconds(6));
        assertThat(events).hasSize(1);
        JsonNode p = events.get(0).payload();
        assertThat(p.get("envelopes").get(0).get("ciphertext").asString()).isEqualTo(ciphertext);
        assertThat(p.get("messageType").asString()).isEqualTo("SIGNAL_PREKEY");
    }

    @Test
    void disappearingMessagesAreStoredWithTheChosenTtl() {
        Map<String, Object> b = new java.util.HashMap<>(signal(UUID.randomUUID(), ct(64)));
        b.put("expiresInSeconds", 300);
        Http.Response r = send(alice, b);
        assertThat(r.status()).isEqualTo(200);
        Instant at = Instant.parse(r.text("createdAt"));
        Integer ttl = cql.execute("SELECT TTL(ciphertext) FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? "
                + "AND created_at = ? AND message_id = ?", conv, MessageStore.day(at), at, UUID.fromString(r.text("messageId"))).one().getInt(0);
        assertThat(ttl).isBetween(250, 300);
        // without a timer: table default (365 days)
        Http.Response plain = send(alice, signal(UUID.randomUUID(), ct(64)));
        Instant at2 = Instant.parse(plain.text("createdAt"));
        assertThat(cql.execute("SELECT TTL(ciphertext) FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? "
                + "AND created_at = ? AND message_id = ?", conv, MessageStore.day(at2), at2, UUID.fromString(plain.text("messageId")))
                .one().getInt(0)).isGreaterThan(31_000_000);
        // only the allowed timer values
        b.put("clientMessageId", UUID.randomUUID());
        b.put("expiresInSeconds", 42);
        assertThat(send(alice, b).status()).isEqualTo(400);
    }

    @Test
    void onlyTheSenderCanDeleteForEveryoneWithin24Hours() {
        Http.Response r = send(alice, signal(UUID.randomUUID(), ct(64)));
        String id = r.text("messageId");
        String at = r.text("createdAt");
        String url = "/api/v1/conversations/" + conv + "/messages/" + id + "?at=" + at;
        assertThat(http.delete(url, bob.token()).status()).isEqualTo(403);                       // not the sender
        Party outsider = party();
        assertThat(http.delete(url, outsider.token()).status()).isEqualTo(404);                   // not a member
        assertThat(http.delete(url, alice.token()).status()).isEqualTo(204);
        assertThat(http.get("/api/v1/conversations/" + conv + "/messages", bob.token()).body().get("messages").toString())
                .doesNotContain(id);                                                                 // gone from history
        assertThat(http.delete(url, alice.token()).status()).isEqualTo(404);

        // older than 24 h: refused
        UUID oldId = UUID.randomUUID();
        Instant old = Instant.now().minus(Duration.ofHours(25)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        store.writeMessage(new MessageStore.StoredMessage(conv, MessageStore.day(old), old, oldId, alice.user(), alice.device(),
                new byte[] {1}, "SIGNAL", "SIGNAL_WHISPER", UUID.randomUUID(), null, old, 1));
        Http.Response late = http.delete("/api/v1/conversations/" + conv + "/messages/" + oldId + "?at=" + old, alice.token());
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("DELETE_WINDOW_EXPIRED");
    }

    @Test
    void duplicateSubmissionReturnsOriginalLogicalMessage() {
        UUID clientId = UUID.randomUUID();
        Map<String, Object> body = signal(clientId, ct(64));
        Http.Response first = send(alice, body);
        Http.Response second = send(alice, body);
        assertThat(second.text("messageId")).isEqualTo(first.text("messageId"));
        assertThat(second.text("createdAt")).isEqualTo(first.text("createdAt"));
        assertThat(second.body().get("duplicate").asBoolean()).isTrue();
        assertThat(cql.execute("SELECT count(*) FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ?",
                conv, LocalDate.now(java.time.ZoneOffset.UTC)).one().getLong(0)).isEqualTo(1);
        List<EventEnvelope> events = TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> e.aggregateId().equals(first.text("messageId")),
                Duration.ofSeconds(5));
        assertThat(events).hasSize(1);
        // same clientMessageId reused for another conversation is rejected
        UUID other = UUID.randomUUID();
        when(deps.authorize(eq(other), any())).thenReturn(new Dependencies.Authorization(true, "DIRECT", "MEMBER", 1, null,
                Instant.now(), List.of(alice.user(), bob.user())));
        assertThat(http.post("/api/v1/conversations/" + other + "/messages", body, alice.token()).code()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void historyPaginatesAcrossBucketsWithoutFilteringAndShowsOnlyOwnEnvelope() {
        // 7 messages spread over 3 day-buckets (written directly to control timestamps)
        Instant base = Instant.now().minus(Duration.ofDays(3));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Instant t = base.plus(Duration.ofDays(i / 3)).plusSeconds(i);
            UUID id = com.securechat.support.ids.UuidV7.at(t.toEpochMilli());
            ids.add(id);
            String envs = "[{\"deviceId\":\"" + bob.device() + "\",\"signalType\":1,\"ciphertext\":\"QkJC\"},{\"deviceId\":\""
                    + UUID.randomUUID() + "\",\"signalType\":1,\"ciphertext\":\"T1RIRVI=\"}]";
            store.writeMessage(new MessageStore.StoredMessage(conv, MessageStore.day(t), t.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                    id, alice.user(), alice.device(), envs.getBytes(), "SIGNAL", "SIGNAL_WHISPER", UUID.randomUUID(), null, t, 1));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Http.Response p = http.get("/api/v1/conversations/" + conv + "/messages?limit=3" + (cursor == null ? "" : "&before=" + cursor),
                    bob.token());
            assertThat(p.status()).isEqualTo(200);
            for (JsonNode m : p.body().get("messages")) {
                seen.add(m.get("messageId").asString());
                assertThat(m.get("envelope").get("ciphertext").asString()).isEqualTo("QkJC");     // only Bob's envelope
                assertThat(m.toString()).doesNotContain("T1RIRVI=");
            }
            cursor = p.body().get("nextCursor").isNull() ? null : p.body().get("nextCursor").asString();
            pages++;
        } while (cursor != null && pages < 10);
        List<String> expected = ids.stream().map(UUID::toString).toList().reversed();
        assertThat(seen).containsExactlyElementsOf(expected);   // newest first, no gaps, no duplicates
        assertThat(http.get("/api/v1/conversations/" + conv + "/messages", party().token()).status()).isEqualTo(404);
    }

    @Test
    void authorizationAndValidationFailClosed() {
        Party mallory = party();
        when(deps.device(mallory.device())).thenReturn(new Dependencies.DeviceStatus(mallory.device(), mallory.user(), "ACTIVE"));
        assertThat(send(mallory, signal(UUID.randomUUID(), ct(10))).code()).isEqualTo("NOT_A_MEMBER");
        // device that belongs to someone else / revoked
        assertThat(http.post("/api/v1/conversations/" + conv + "/messages", signal(UUID.randomUUID(), ct(10)),
                TestTokens.token(mallory.user(), "m", alice.device())).code()).isEqualTo("FORBIDDEN");
        when(deps.device(alice.device())).thenReturn(new Dependencies.DeviceStatus(alice.device(), alice.user(), "REVOKED"));
        assertThat(send(alice, signal(UUID.randomUUID(), ct(10))).code()).isEqualTo("DEVICE_REVOKED");
        when(deps.device(alice.device())).thenReturn(new Dependencies.DeviceStatus(alice.device(), alice.user(), "ACTIVE"));
        // dependency down → 503, not accepted
        org.mockito.Mockito.doThrow(ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "down")).when(deps).authorize(eq(conv), any());
        assertThat(send(alice, signal(UUID.randomUUID(), ct(10))).status()).isEqualTo(503);
    }

    @Test
    void envelopeAndProtocolRulesAreEnforced() {
        assertThat(send(alice, Map.of("clientMessageId", UUID.randomUUID(), "protocol", "MLS", "ciphertext", ct(10), "epoch", 0)).code())
                .isEqualTo("VALIDATION_FAILED");      // MLS on a direct conversation
        assertThat(send(alice, Map.of("clientMessageId", UUID.randomUUID(), "protocol", "PLAINTEXT", "ciphertext", "aGk=")).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(send(alice, signal(UUID.randomUUID(), ct(70_000))).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(send(alice, Map.of("clientMessageId", UUID.randomUUID(), "protocol", "SIGNAL", "envelopes",
                List.of(Map.of("deviceId", alice.device(), "signalType", 1, "ciphertext", ct(10))))).code()).isEqualTo("VALIDATION_FAILED");
        // Bob registered a second device the sender did not encrypt for
        UUID bobPhone = UUID.randomUUID();
        when(deps.readyDevices(anyList())).thenReturn(Map.of(alice.user(), List.of(alice.device()), bob.user(), List.of(bob.device(), bobPhone)));
        Http.Response stale = send(alice, signal(UUID.randomUUID(), ct(10)));
        assertThat(stale.code()).isEqualTo("DEVICE_LIST_STALE");
        assertThat(stale.body().get("missingDeviceIds").get(0).asString()).isEqualTo(bobPhone.toString());
        // Key Management unavailable → check skipped, message still accepted
        when(deps.readyDevices(anyList())).thenReturn(null);
        assertThat(send(alice, signal(UUID.randomUUID(), ct(10))).text("status")).isEqualTo("ACCEPTED");
    }

    @Test
    void mlsGroupMessagesRequireCurrentEpoch() {
        UUID group = UUID.randomUUID();
        when(deps.authorize(eq(group), any())).thenReturn(new Dependencies.Authorization(true, "GROUP", "MEMBER", 3, 5L, Instant.now(),
                List.of(alice.user(), bob.user())));
        Map<String, Object> stale = Map.of("clientMessageId", UUID.randomUUID(), "protocol", "MLS", "ciphertext", ct(40), "epoch", 4);
        Http.Response r = http.post("/api/v1/conversations/" + group + "/messages", stale, alice.token());
        assertThat(r.code()).isEqualTo("EPOCH_MISMATCH");
        assertThat(r.body().get("currentEpoch").asLong()).isEqualTo(5);
        Map<String, Object> ok = Map.of("clientMessageId", UUID.randomUUID(), "protocol", "MLS", "ciphertext", ct(40), "epoch", 5);
        assertThat(http.post("/api/v1/conversations/" + group + "/messages", ok, alice.token()).text("status")).isEqualTo("ACCEPTED");
    }

    @Test
    void cassandraOutageNeverProducesFalseAcceptance() {
        UUID clientId = UUID.randomUUID();
        Map<String, Object> body = signal(clientId, ct(32));
        Infra.pause(Infra.cassandra());
        Http.Response during;
        try {
            during = send(alice, body);
        } finally {
            Infra.unpause(Infra.cassandra());
        }
        assertThat(during.status()).isEqualTo(503);
        assertThat(during.code()).isEqualTo("STORE_UNAVAILABLE");
        assertThat(during.body().has("messageId")).isFalse();
        // client retries with the same idempotency key after recovery → accepted exactly once
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .until(() -> send(alice, body).status() == 200);
        Http.Response again = send(alice, body);
        Set<String> ids = new HashSet<>(List.of(again.text("messageId")));
        List<EventEnvelope> events = TestSupport.collect(Topics.MESSAGE_ACCEPTED, e -> ids.contains(e.aggregateId()), Duration.ofSeconds(6));
        assertThat(events).hasSize(1);
    }
}
