package com.securechat.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventType;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.UserRegistered;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserServiceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "user_db");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
        r.add("securechat.users.max-active-devices", () -> "3");
    }

    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EventPublisher publisher;
    Http http;

    record User(UUID id, String username, String token) {
    }

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
    }

    User user() {
        UUID id = UUID.randomUUID();
        String name = "u" + id.toString().substring(0, 8);
        return new User(id, name, TestTokens.token(id, name, null));
    }

    User registered(String displayName) {
        User u = user();
        publisher.publish(EventType.USER_REGISTERED, UUID.randomUUID(), u.id().toString(), u.id().toString(),
                new UserRegistered(u.id(), u.username(), displayName, Instant.now()));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> http.get("/api/v1/users/" + u.id(), u.token()).status() == 200);
        return u;
    }

    @Test
    void profileCreatedFromRegistrationEventAndDuplicateEventIsHarmless() {
        User u = user();
        UUID eventId = UUID.randomUUID();
        var payload = new UserRegistered(u.id(), u.username(), "Alice Example", Instant.now());
        publisher.publish(EventType.USER_REGISTERED, eventId, u.id().toString(), u.id().toString(), payload);
        publisher.publish(EventType.USER_REGISTERED, eventId, u.id().toString(), u.id().toString(), payload);
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> http.get("/api/v1/users/me", u.token()).status() == 200);
        assertThat(http.get("/api/v1/users/me", u.token()).text("displayName")).isEqualTo("Alice Example");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id = ?", Long.class, u.id())).isEqualTo(1L);
    }

    @Test
    void displayNameFromRegistrationWinsOverLazilyCreatedProfileButNeverOverUserEdits() {
        // the race: the new client fetches its profile BEFORE the registration event is processed
        User u = user();
        assertThat(http.get("/api/v1/users/me", u.token()).text("displayName")).isEqualTo(u.username());   // lazy default
        publisher.publish(EventType.USER_REGISTERED, UUID.randomUUID(), u.id().toString(), u.id().toString(),
                new UserRegistered(u.id(), u.username(), "Alice Display", Instant.now()));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> "Alice Display".equals(http.get("/api/v1/users/me", u.token()).text("displayName")));

        // a name the user chose afterwards is never overwritten by a late/replayed registration event
        User v = user();
        http.get("/api/v1/users/me", v.token());
        assertThat(http.patch("/api/v1/users/me", Map.of("displayName", "My Own Name"), v.token()).status()).isEqualTo(200);
        UUID late = UUID.randomUUID();
        publisher.publish(EventType.USER_REGISTERED, late, v.id().toString(), v.id().toString(),
                new UserRegistered(v.id(), v.username(), "Registration Name", Instant.now()));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, late) == 1);
        assertThat(http.get("/api/v1/users/me", v.token()).text("displayName")).isEqualTo("My Own Name");
    }

    @Test
    void profileUpdateAndValidation() {
        User u = user();
        assertThat(http.get("/api/v1/users/me", u.token()).status()).isEqualTo(200); // lazily created
        assertThat(http.patch("/api/v1/users/me", Map.of("displayName", "New Name", "bio", "hi"), u.token()).text("displayName"))
                .isEqualTo("New Name");
        assertThat(http.patch("/api/v1/users/me", Map.of("avatarUrl", "javascript:alert(1)"), u.token()).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void usernameSearchIsPrefixBasedEscapedAndExcludesSelf() {
        String tag = "s" + UUID.randomUUID().toString().substring(0, 5);
        User a = user();
        http.get("/api/v1/users/me", TestTokens.token(a.id(), tag + "alice", null));
        User b = user();
        http.get("/api/v1/users/me", TestTokens.token(b.id(), tag + "bob", null));
        Http.Response r = http.get("/api/v1/users/search?q=" + tag, TestTokens.token(a.id(), tag + "alice", null));
        assertThat(r.body().size()).isEqualTo(1);
        assertThat(r.body().get(0).get("username").asString()).isEqualTo(tag + "bob");
        assertThat(r.body().get(0).has("email")).isFalse();
        assertThat(http.get("/api/v1/users/search?q=%25%25", a.token()).body().size()).isZero(); // '%' is literal
        assertThat(http.get("/api/v1/users/search?q=a", a.token()).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void contacts() {
        User a = registered("A");
        User b = registered("B");
        assertThat(http.put("/api/v1/contacts/" + b.id(), null, a.token()).status()).isEqualTo(204);
        assertThat(http.get("/api/v1/contacts", a.token()).body().get(0).get("userId").asString()).isEqualTo(b.id().toString());
        assertThat(http.put("/api/v1/contacts/" + a.id(), null, a.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.delete("/api/v1/contacts/" + b.id(), a.token()).status()).isEqualTo(204);
        assertThat(http.get("/api/v1/contacts", a.token()).body().size()).isZero();
    }

    @Test
    void multipleIndependentDevicesPerUserAndIdempotentRegistration() {
        User a = user();
        UUID laptop = UUID.randomUUID();
        Http.Response r1 = http.post("/api/v1/devices", Map.of("deviceId", laptop, "deviceName", "Laptop", "platform", "WEB"), a.token());
        assertThat(r1.status()).isEqualTo(201);
        assertThat(r1.text("status")).isEqualTo("ACTIVE");
        assertThat(http.post("/api/v1/devices", Map.of("deviceId", laptop, "deviceName", "Laptop", "platform", "WEB"), a.token())
                .text("id")).isEqualTo(laptop.toString());
        Http.Response phone = http.post("/api/v1/devices", Map.of("deviceName", "Phone", "platform", "ANDROID"), a.token());
        assertThat(phone.status()).isEqualTo(201);
        assertThat(phone.text("id")).isNotEqualTo(laptop.toString());
        assertThat(http.get("/api/v1/devices", a.token()).body().size()).isEqualTo(2);
        assertThat(http.post("/api/v1/devices", Map.of("platform", "TOASTER"), a.token()).code()).isEqualTo("VALIDATION_FAILED");
        // active-device cap (3 in this test)
        assertThat(http.post("/api/v1/devices", Map.of("platform", "IOS"), a.token()).status()).isEqualTo(201);
        assertThat(http.post("/api/v1/devices", Map.of("platform", "IOS"), a.token()).code()).isEqualTo("CONFLICT");
    }

    @Test
    void usersCannotUseOrRevokeAnotherUsersDevice() {
        User a = user();
        User b = user();
        UUID dev = UUID.fromString(http.post("/api/v1/devices", Map.of("platform", "WEB"), a.token()).text("id"));
        assertThat(http.post("/api/v1/devices", Map.of("deviceId", dev, "platform", "WEB"), b.token()).code()).isEqualTo("CONFLICT");
        assertThat(http.delete("/api/v1/devices/" + dev, b.token()).status()).isEqualTo(404);
        assertThat(http.internal("GET", "/internal/v1/devices/" + dev, null).text("status")).isEqualTo("ACTIVE");
    }

    @Test
    void revocationIsEnforcedAndPublished() {
        User a = user();
        UUID dev = UUID.fromString(http.post("/api/v1/devices", Map.of("platform", "WEB"), a.token()).text("id"));
        assertThat(http.delete("/api/v1/devices/" + dev, a.token()).text("status")).isEqualTo("REVOKED");
        assertThat(http.delete("/api/v1/devices/" + dev, a.token()).text("status")).isEqualTo("REVOKED"); // idempotent
        assertThat(http.internal("GET", "/internal/v1/devices/" + dev, null).text("status")).isEqualTo("REVOKED");
        assertThat(http.post("/api/v1/devices", Map.of("deviceId", dev, "platform", "WEB"), a.token()).code()).isEqualTo("CONFLICT");
        Http.Response active = http.internal("POST", "/internal/v1/devices:active", Map.of("userIds", List.of(a.id())));
        assertThat(active.body().get(a.id().toString()).size()).isZero();
        // exactly one device.revoked event through the outbox
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.DEVICE_REVOKED));
            int[] seen = {0};
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var rec : c.poll(Duration.ofMillis(500))) {
                    if (EventCodec.fromJson(rec.value()).aggregateId().equals(dev.toString())) {
                        seen[0]++;
                    }
                }
                return seen[0] >= 1;
            });
            c.poll(Duration.ofSeconds(2)).forEach(rec -> {
                if (EventCodec.fromJson(rec.value()).aggregateId().equals(dev.toString())) {
                    seen[0]++;
                }
            });
            assertThat(seen[0]).isEqualTo(1);
        }
    }

    @Test
    void ownerCanRemoveDevicesFromTheirList() {
        User a = user();
        User b = user();
        UUID active = UUID.fromString(http.post("/api/v1/devices", Map.of("platform", "WEB"), a.token()).text("id"));
        UUID revoked = UUID.fromString(http.post("/api/v1/devices", Map.of("platform", "IOS"), a.token()).text("id"));
        UUID kept = UUID.fromString(http.post("/api/v1/devices", Map.of("platform", "ANDROID"), a.token()).text("id"));
        http.delete("/api/v1/devices/" + revoked, a.token());

        assertThat(http.post("/api/v1/devices/" + active + "/remove", null, b.token()).status()).isEqualTo(404); // not the owner
        assertThat(http.post("/api/v1/devices/" + revoked + "/remove", null, a.token()).status()).isEqualTo(204);
        assertThat(http.post("/api/v1/devices/" + active + "/remove", null, a.token()).status()).isEqualTo(204);  // revoke + remove
        assertThat(http.post("/api/v1/devices/" + active + "/remove", null, a.token()).status()).isEqualTo(204);  // idempotent

        Http.Response mine = http.get("/api/v1/devices", a.token());
        assertThat(mine.body().size()).isEqualTo(1);
        assertThat(mine.body().get(0).get("id").asString()).isEqualTo(kept.toString());
        // an active device that was removed is revoked everywhere, and its id can never come back
        assertThat(http.internal("GET", "/internal/v1/devices/" + active, null).text("status")).isEqualTo("REVOKED");
        assertThat(http.post("/api/v1/devices", Map.of("deviceId", active, "platform", "WEB"), a.token()).code()).isEqualTo("CONFLICT");
        Http.Response act = http.internal("POST", "/internal/v1/devices:active", Map.of("userIds", List.of(a.id())));
        assertThat(act.body().get(a.id().toString()).size()).isEqualTo(1);
        // removing freed a slot under the active-device cap (3 in this test)
        assertThat(http.post("/api/v1/devices", Map.of("platform", "WEB"), a.token()).status()).isEqualTo(201);
        assertThat(http.post("/api/v1/devices", Map.of("platform", "WEB"), a.token()).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE topic = ? AND payload LIKE ?", Long.class,
                Topics.DEVICE_REVOKED, "%" + active + "%")).isEqualTo(1); // no duplicate revoke on repeated remove
    }

    @Test
    void privateKeyMaterialIsRejectedAndHasNowhereToBeStored() {
        User a = user();
        Http.Response r = http.post("/api/v1/devices", Map.of("platform", "WEB", "identityPrivateKey", "AAAA"), a.token());
        assertThat(r.status()).isEqualTo(400);
        List<String> cols = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'devices'",
                String.class);
        assertThat(cols).noneMatch(c -> c.contains("key") || c.contains("secret") || c.contains("session"));
    }

    @Test
    void authenticationAndInternalCredentialsAreEnforced() {
        User a = user();
        assertThat(http.get("/api/v1/users/me", null).status()).isEqualTo(401);
        assertThat(http.get("/api/v1/users/me", TestTokens.token(a.id(), "x", null, "securechat-auth", "other-aud",
                Instant.now().plusSeconds(60))).status()).isEqualTo(401);
        assertThat(http.get("/api/v1/users/me", TestTokens.token(a.id(), "x", null, "evil-issuer", "securechat",
                Instant.now().plusSeconds(60))).status()).isEqualTo(401);
        assertThat(http.get("/api/v1/users/me", TestTokens.token(a.id(), "x", null, "securechat-auth", "securechat",
                Instant.now().minusSeconds(120))).status()).isEqualTo(401);
        assertThat(http.get("/internal/v1/stats", a.token()).status()).isIn(401, 403);
        assertThat(http.internal("GET", "/internal/v1/stats", null).status()).isEqualTo(200);
    }
}
