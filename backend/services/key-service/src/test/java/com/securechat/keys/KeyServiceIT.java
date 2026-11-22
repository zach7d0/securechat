package com.securechat.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventType;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.support.web.ApiException;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KeyServiceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "key_db");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
        r.add("securechat.keys.claims-per-minute", () -> "1000");
    }

    static final SecureRandom RND = new SecureRandom();
    /** Stand-in for User Service: deviceId → (userId, status). */
    static final Map<UUID, DeviceDirectory.DeviceInfo> DEVICES = new ConcurrentHashMap<>();

    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EventPublisher publisher;
    @MockitoBean
    DeviceDirectory directory;
    Http http;

    record Dev(UUID userId, UUID deviceId, String token) {
    }

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
        when(directory.device(any())).thenAnswer(inv -> DEVICES.get(inv.<UUID>getArgument(0)));
        when(directory.activeDevices(anyList())).thenAnswer(inv -> {
            Map<UUID, List<UUID>> m = new HashMap<>();
            for (UUID u : inv.<List<UUID>>getArgument(0)) {
                m.put(u, DEVICES.values().stream().filter(d -> d.userId().equals(u) && d.status().equals("ACTIVE"))
                        .map(DeviceDirectory.DeviceInfo::id).toList());
            }
            return m;
        });
    }

    static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    static byte[] rnd(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    static String curve() {
        byte[] k = rnd(33);
        k[0] = 5;
        return b64(k);
    }

    static String keyPackage() {
        byte[] k = rnd(200);
        k[0] = 0;
        k[1] = 1;
        k[2] = 0;
        k[3] = 5;
        return b64(k);
    }

    Dev device(UUID userId) {
        UUID d = UUID.randomUUID();
        DEVICES.put(d, new DeviceDirectory.DeviceInfo(d, userId, "ACTIVE"));
        return new Dev(userId, d, TestTokens.token(userId, "u", d));
    }

    /** Full client bootstrap: identity, signed prekey, n one-time prekeys, k key packages + last resort. */
    String provision(Dev d, int prekeys, int keyPackages) {
        String ik = curve();
        assertThat(http.put("/api/v1/keys/identity", Map.of("identityKey", ik, "registrationId", 1 + RND.nextInt(16000),
                "mlsSignatureKey", b64(rnd(32)), "mlsSignatureBinding", b64(rnd(64))), d.token()).status()).isEqualTo(204);
        assertThat(http.post("/api/v1/keys/signed-prekeys", Map.of("keyId", 1, "publicKey", curve(), "signature", b64(rnd(64))),
                d.token()).status()).isEqualTo(204);
        if (prekeys > 0) {
            List<Map<String, Object>> keys = new ArrayList<>();
            for (int i = 1; i <= prekeys; i++) {
                keys.add(Map.of("keyId", i, "publicKey", curve()));
            }
            assertThat(http.post("/api/v1/keys/one-time-prekeys", Map.of("keys", keys), d.token()).body().get("stored").asInt())
                    .isEqualTo(prekeys);
        }
        List<Map<String, Object>> kps = new ArrayList<>();
        for (int i = 0; i < keyPackages; i++) {
            kps.add(Map.of("id", UUID.randomUUID(), "keyPackage", keyPackage(), "lastResort", false));
        }
        kps.add(Map.of("id", UUID.randomUUID(), "keyPackage", keyPackage(), "lastResort", true));
        assertThat(http.post("/api/v1/keys/mls-key-packages", Map.of("keyPackages", kps), d.token()).status()).isEqualTo(200);
        return ik;
    }

    Http.Response claim(Dev requester, UUID target, boolean mls) {
        return http.post("/api/v1/keys/users/" + target + "/bundles:claim", Map.of("includeMls", mls), requester.token());
    }

    @Test
    void uploadStatusAndBundleRetrieval() {
        Dev alice = device(UUID.randomUUID());
        Dev bob = device(UUID.randomUUID());
        String ik = provision(alice, 5, 2);
        JsonNode st = http.get("/api/v1/keys/me/status", alice.token()).body();
        assertThat(st.get("identity").asBoolean()).isTrue();
        assertThat(st.get("oneTimePreKeys").asLong()).isEqualTo(5);
        assertThat(st.get("mlsKeyPackages").asLong()).isEqualTo(2);

        JsonNode b = claim(bob, alice.userId(), true).body().get("devices").get(0);
        assertThat(b.get("deviceId").asString()).isEqualTo(alice.deviceId().toString());
        assertThat(b.get("identityKey").asString()).isEqualTo(ik);
        assertThat(b.get("signedPreKey").get("signature").asString()).isNotEmpty();
        assertThat(b.get("preKey").get("keyId").asLong()).isEqualTo(1);
        assertThat(b.get("mlsKeyPackage").get("lastResort").asBoolean()).isFalse();
        assertThat(claim(bob, alice.userId(), false).body().get("devices").get(0).get("preKey").get("keyId").asLong()).isEqualTo(2);
        assertThat(http.get("/api/v1/keys/me/status", alice.token()).body().get("oneTimePreKeys").asLong()).isEqualTo(3);
        // identities endpoint (no claim)
        assertThat(http.get("/api/v1/keys/users/" + alice.userId() + "/identities", bob.token()).body().size()).isEqualTo(1);
    }

    @Test
    void eachActiveDeviceHasItsOwnBundleAndRequesterIsExcluded() {
        UUID aliceId = UUID.randomUUID();
        Dev laptop = device(aliceId);
        Dev phone = device(aliceId);
        String ik1 = provision(laptop, 2, 1);
        String ik2 = provision(phone, 2, 1);
        assertThat(ik1).isNotEqualTo(ik2);
        Dev bob = device(UUID.randomUUID());
        assertThat(claim(bob, aliceId, false).body().get("devices").size()).isEqualTo(2);
        // Alice's laptop fetching her own user's bundles gets only the phone (sync to own devices)
        JsonNode own = claim(laptop, aliceId, false).body().get("devices");
        assertThat(own.size()).isEqualTo(1);
        assertThat(own.get(0).get("deviceId").asString()).isEqualTo(phone.deviceId().toString());
    }

    @Test
    void oneTimePrekeyIsNeverClaimedTwiceUnderConcurrency() throws Exception {
        Dev alice = device(UUID.randomUUID());
        provision(alice, 10, 3);
        List<Dev> requesters = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            requesters.add(device(UUID.randomUUID()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(30);
        List<Future<JsonNode>> fs = new ArrayList<>();
        for (Dev r : requesters) {
            fs.add(pool.submit(() -> claim(r, alice.userId(), true).body().get("devices").get(0)));
        }
        List<Long> prekeys = new ArrayList<>();
        List<String> packages = new ArrayList<>();
        int withoutPrekey = 0;
        for (Future<JsonNode> f : fs) {
            JsonNode b = f.get();
            if (b.get("preKey") == null || b.get("preKey").isNull()) {
                withoutPrekey++;
            } else {
                prekeys.add(b.get("preKey").get("keyId").asLong());
            }
            if (!b.get("mlsKeyPackage").get("lastResort").asBoolean()) {
                packages.add(b.get("mlsKeyPackage").get("id").asString());
            }
        }
        pool.shutdown();
        assertThat(prekeys).hasSize(10).doesNotHaveDuplicates();
        assertThat(withoutPrekey).isEqualTo(20);
        assertThat(packages).hasSize(3).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM one_time_prekey_inventory WHERE device_id = ? AND status = 'CLAIMED'",
                Long.class, alice.deviceId())).isEqualTo(10L);
    }

    @Test
    void depletedInventoryFallsBackToSignedPrekeyAndLastResortPackage() {
        Dev alice = device(UUID.randomUUID());
        Dev bob = device(UUID.randomUUID());
        provision(alice, 0, 0);
        JsonNode b = claim(bob, alice.userId(), true).body().get("devices").get(0);
        assertThat(b.get("preKey").isNull()).isTrue();          // X3DH without one-time prekey
        assertThat(b.get("signedPreKey").isNull()).isFalse();
        assertThat(b.get("mlsKeyPackage").get("lastResort").asBoolean()).isTrue();
        // the last-resort package is not consumed
        assertThat(claim(bob, alice.userId(), true).body().get("devices").get(0).get("mlsKeyPackage").get("lastResort").asBoolean()).isTrue();
    }

    @Test
    void signedPrekeyReplacementRetiresOldOne() {
        Dev alice = device(UUID.randomUUID());
        Dev bob = device(UUID.randomUUID());
        provision(alice, 1, 0);
        assertThat(http.post("/api/v1/keys/signed-prekeys", Map.of("keyId", 2, "publicKey", curve(), "signature", b64(rnd(64))),
                alice.token()).status()).isEqualTo(204);
        assertThat(claim(bob, alice.userId(), false).body().get("devices").get(0).get("signedPreKey").get("keyId").asLong()).isEqualTo(2);
        assertThat(http.post("/api/v1/keys/signed-prekeys", Map.of("keyId", 2, "publicKey", curve(), "signature", b64(rnd(64))),
                alice.token()).code()).isEqualTo("CONFLICT");
    }

    @Test
    void keyFormatsAreValidatedAndUnknownFieldsRejected() {
        Dev d = device(UUID.randomUUID());
        byte[] wrongPrefix = rnd(33);
        wrongPrefix[0] = 4;
        String sig = b64(rnd(64));
        assertThat(http.put("/api/v1/keys/identity", Map.of("identityKey", b64(wrongPrefix), "registrationId", 5,
                "mlsSignatureKey", b64(rnd(32)), "mlsSignatureBinding", sig), d.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.put("/api/v1/keys/identity", Map.of("identityKey", "%%%not-base64", "registrationId", 5,
                "mlsSignatureKey", b64(rnd(32)), "mlsSignatureBinding", sig), d.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.put("/api/v1/keys/identity", Map.of("identityKey", curve(), "registrationId", 99999,
                "mlsSignatureKey", b64(rnd(32)), "mlsSignatureBinding", sig), d.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.put("/api/v1/keys/identity", Map.of("identityKey", curve(), "registrationId", 5,
                "mlsSignatureKey", b64(rnd(32)), "mlsSignatureBinding", sig, "identityPrivateKey", b64(rnd(32))), d.token()).status())
                .isEqualTo(400);
        provision(d, 0, 0);
        assertThat(http.post("/api/v1/keys/signed-prekeys", Map.of("keyId", 9, "publicKey", curve(), "signature", b64(rnd(10))),
                d.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.post("/api/v1/keys/mls-key-packages", Map.of("keyPackages", List.of(Map.of("id", UUID.randomUUID(),
                "keyPackage", b64(rnd(100)), "lastResort", false))), d.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.post("/api/v1/keys/one-time-prekeys", Map.of("keys", List.of(Map.of("keyId", 1, "publicKey", curve(),
                "privateKey", curve()))), d.token()).status()).isEqualTo(400);
    }

    @Test
    void identityIsImmutablePerDevice() {
        Dev d = device(UUID.randomUUID());
        String ik = provision(d, 0, 0);
        Map<String, Object> same = Map.of("identityKey", ik, "registrationId", 7, "mlsSignatureKey", b64(rnd(32)),
                "mlsSignatureBinding", b64(rnd(64)));
        assertThat(http.put("/api/v1/keys/identity", same, d.token()).status()).isEqualTo(204);
        assertThat(http.put("/api/v1/keys/identity", Map.of("identityKey", curve(), "registrationId", 7, "mlsSignatureKey",
                b64(rnd(32)), "mlsSignatureBinding", b64(rnd(64))), d.token()).code()).isEqualTo("CONFLICT");
    }

    @Test
    void ownershipDeviceBindingAndRevocationAreEnforced() {
        Dev alice = device(UUID.randomUUID());
        provision(alice, 3, 1);
        // token claims a device that belongs to someone else
        UUID mallory = UUID.randomUUID();
        String forged = TestTokens.token(mallory, "m", alice.deviceId());
        assertThat(http.post("/api/v1/keys/signed-prekeys", Map.of("keyId", 5, "publicKey", curve(), "signature", b64(rnd(64))),
                forged).code()).isEqualTo("FORBIDDEN");
        // token without a device
        assertThat(http.get("/api/v1/keys/me/status", TestTokens.token(alice.userId(), "a", null)).code()).isEqualTo("DEVICE_REQUIRED");
        // revoked requester cannot fetch; revoked owner cannot publish
        Dev bob = device(UUID.randomUUID());
        DEVICES.put(bob.deviceId(), new DeviceDirectory.DeviceInfo(bob.deviceId(), bob.userId(), "REVOKED"));
        assertThat(claim(bob, alice.userId(), false).code()).isEqualTo("DEVICE_REVOKED");
        DEVICES.put(alice.deviceId(), new DeviceDirectory.DeviceInfo(alice.deviceId(), alice.userId(), "REVOKED"));
        assertThat(http.post("/api/v1/keys/signed-prekeys", Map.of("keyId", 6, "publicKey", curve(), "signature", b64(rnd(64))),
                alice.token()).code()).isEqualTo("DEVICE_REVOKED");
        // a revoked device's bundle is not distributed
        Dev carol = device(UUID.randomUUID());
        assertThat(claim(carol, alice.userId(), false).body().get("devices").size()).isZero();
    }

    @Test
    void deviceRevokedEventStopsDistributionEvenIfDirectoryIsStale() {
        Dev alice = device(UUID.randomUUID());
        Dev bob = device(UUID.randomUUID());
        provision(alice, 3, 1);
        publisher.publish(EventType.DEVICE_REVOKED, UUID.randomUUID(), alice.userId().toString(), alice.deviceId().toString(),
                new DeviceRevoked(alice.userId(), alice.deviceId(), "USER", Instant.now()));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> claim(bob, alice.userId(), false).body().get("devices").size() == 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM one_time_prekey_inventory WHERE device_id = ? AND status = 'AVAILABLE'",
                Long.class, alice.deviceId())).isZero();
    }

    @Test
    void lowInventoryPublishesReplenishmentSignal() {
        Dev alice = device(UUID.randomUUID());
        Dev bob = device(UUID.randomUUID());
        provision(alice, 3, 1);
        claim(bob, alice.userId(), true);
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.KEYS_PREKEYS_LOW));
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var rec : c.poll(Duration.ofMillis(500))) {
                    var env = EventCodec.fromJson(rec.value());
                    if (env.aggregateId().equals(alice.deviceId().toString())) {
                        assertThat(env.payload().get("remainingPrekeys").asLong()).isEqualTo(2);
                        return true;
                    }
                }
                return false;
            });
        }
    }

    @Test
    void failsClosedWhenUserServiceIsUnavailable() {
        Dev alice = device(UUID.randomUUID());
        org.mockito.Mockito.doThrow(ApiException.unavailable("DEPENDENCY_UNAVAILABLE", "down")).when(directory).device(any());
        Http.Response r = http.get("/api/v1/keys/me/status", alice.token());
        assertThat(r.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void schemaHoldsOnlyPublicMaterial() {
        Set<String> cols = new HashSet<>(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name IN ('identity_keys','signed_prekeys','one_time_prekey_inventory','mls_key_packages')", String.class));
        assertThat(cols).noneMatch(c -> c.contains("private") || c.contains("secret") || c.contains("session") || c.contains("ratchet"));
    }
}
