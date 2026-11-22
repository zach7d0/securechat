package com.securechat.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferIT {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "user_db");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        TestTokens.properties(r);
    }

    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
    }

    record Dev(UUID user, UUID device, String token) {
    }

    Dev device(UUID user) {
        String unbound = TestTokens.token(user, "u" + user.toString().substring(0, 6), null);
        UUID d = UUID.fromString(http.post("/api/v1/devices", Map.of("platform", "WEB"), unbound).text("id"));
        return new Dev(user, d, TestTokens.token(user, "u", d));
    }

    static String b64(int n) {
        byte[] b = new byte[n];
        new java.util.Random().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    String request(Dev target) {
        Http.Response r = http.post("/api/v1/devices/transfers", Map.of("ephemeralPublicKey", b64(32), "signature", b64(64)), target.token());
        assertThat(r.status()).isEqualTo(201);
        return r.text("id");
    }

    @Test
    void fullLifecycleRelaysOnlyOpaqueCiphertextAndDeletesIt() {
        UUID user = UUID.randomUUID();
        Dev oldDev = device(user);
        Dev newDev = device(user);
        String id = request(newDev);
        // the old device sees the pending request
        assertThat(http.get("/api/v1/devices/transfers", oldDev.token()).body().toString()).contains(id).contains("REQUESTED");
        assertThat(http.post("/api/v1/devices/transfers/" + id + "/approve", null, oldDev.token()).text("status")).isEqualTo("APPROVED");
        String c0 = b64(1000);
        String c1 = b64(500);
        assertThat(http.put("/api/v1/devices/transfers/" + id + "/chunks/0", Map.of("data", c0), oldDev.token()).status()).isEqualTo(204);
        assertThat(http.put("/api/v1/devices/transfers/" + id + "/chunks/1", Map.of("data", c1), oldDev.token()).status()).isEqualTo(204);
        assertThat(http.post("/api/v1/devices/transfers/" + id + "/complete", Map.of("manifest", "{\"v\":1}", "signature", b64(64), "chunkCount", 2),
                oldDev.token()).text("status")).isEqualTo("READY");
        assertThat(http.get("/api/v1/devices/transfers/" + id + "/chunks/0", newDev.token()).text("data")).isEqualTo(c0);
        assertThat(http.get("/api/v1/devices/transfers/" + id + "/chunks/1", newDev.token()).text("data")).isEqualTo(c1);
        assertThat(http.delete("/api/v1/devices/transfers/" + id, newDev.token()).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM device_transfer_chunks WHERE transfer_id = ?::uuid", Long.class, id)).isZero();
        assertThat(http.get("/api/v1/devices/transfers/" + id + "/chunks/0", newDev.token()).status()).isIn(404, 409);
    }

    @Test
    void onlyTheSameUsersDevicesInTheirRolesCanAct() {
        UUID user = UUID.randomUUID();
        Dev oldDev = device(user);
        Dev newDev = device(user);
        Dev otherDev = device(user);
        Dev stranger = device(UUID.randomUUID());
        String id = request(newDev);
        assertThat(http.get("/api/v1/devices/transfers", stranger.token()).body().toString()).doesNotContain(id);
        assertThat(http.post("/api/v1/devices/transfers/" + id + "/approve", null, stranger.token()).status()).isEqualTo(404);
        assertThat(http.post("/api/v1/devices/transfers/" + id + "/approve", null, newDev.token()).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.post("/api/v1/devices/transfers", Map.of("ephemeralPublicKey", b64(32), "signature", b64(64)),
                TestTokens.token(user, "u", null)).code()).isEqualTo("DEVICE_REQUIRED");
        http.post("/api/v1/devices/transfers/" + id + "/approve", null, oldDev.token());
        // a different device of the same user cannot upload, the source cannot download
        assertThat(http.put("/api/v1/devices/transfers/" + id + "/chunks/0", Map.of("data", b64(10)), otherDev.token()).status()).isEqualTo(403);
        http.put("/api/v1/devices/transfers/" + id + "/chunks/0", Map.of("data", b64(10)), oldDev.token());
        http.post("/api/v1/devices/transfers/" + id + "/complete", Map.of("manifest", "{}", "signature", b64(64), "chunkCount", 1), oldDev.token());
        assertThat(http.get("/api/v1/devices/transfers/" + id + "/chunks/0", oldDev.token()).status()).isEqualTo(404);
        assertThat(http.get("/api/v1/devices/transfers/" + id + "/chunks/0", otherDev.token()).status()).isEqualTo(404);
        assertThat(http.get("/api/v1/devices/transfers/" + id + "/chunks/0", newDev.token()).status()).isEqualTo(200);
    }

    @Test
    void revokedDevicesAndOversizedOrIncompleteUploadsAreRejected() {
        UUID user = UUID.randomUUID();
        Dev oldDev = device(user);
        Dev newDev = device(user);
        String id = request(newDev);
        http.post("/api/v1/devices/transfers/" + id + "/approve", null, oldDev.token());
        assertThat(http.put("/api/v1/devices/transfers/" + id + "/chunks/0", Map.of("data", b64(200 * 1024)), oldDev.token()).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(http.post("/api/v1/devices/transfers/" + id + "/complete", Map.of("manifest", "{}", "signature", b64(64), "chunkCount", 1),
                oldDev.token()).code()).isEqualTo("VALIDATION_FAILED");      // chunk 0 missing
        assertThat(http.post("/api/v1/devices/transfers", Map.of("ephemeralPublicKey", b64(16), "signature", b64(64)), newDev.token()).code())
                .isEqualTo("VALIDATION_FAILED");
        http.delete("/api/v1/devices/" + newDev.device(), newDev.token());   // revoke the target
        assertThat(http.get("/api/v1/devices/transfers", newDev.token()).code()).isEqualTo("DEVICE_REVOKED");
    }

    @Test
    void requestsCarryABoundedWindowAndAreCappedPerDay() {
        UUID user = UUID.randomUUID();
        Dev a = device(user);
        String url = "/api/v1/devices/transfers";
        java.time.Instant now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);   // clients send ms
        Http.Response ok = http.post(url, Map.of("ephemeralPublicKey", b64(32), "signature", b64(64),
                "windowFrom", now.minus(java.time.Duration.ofDays(30)).toString(), "windowTo", now.toString()), a.token());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(java.time.Instant.parse(ok.text("windowTo"))).isEqualTo(now);
        // window too long, reversed, in the future, or half given
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("windowFrom", now.minus(java.time.Duration.ofDays(40)).toString(), "windowTo", now.toString()),
                Map.<String, Object>of("windowFrom", now.toString(), "windowTo", now.minus(java.time.Duration.ofDays(1)).toString()),
                Map.<String, Object>of("windowFrom", now.toString(), "windowTo", now.plus(java.time.Duration.ofDays(2)).toString()),
                Map.<String, Object>of("windowFrom", now.toString()))) {
            Map<String, Object> body = new java.util.HashMap<>(bad);
            body.put("ephemeralPublicKey", b64(32));
            body.put("signature", b64(64));
            assertThat(http.post(url, body, a.token()).status()).as(bad.toString()).isEqualTo(400);
        }
        // daily cap (30): requests already made today count, even when finished
        jdbc.update("UPDATE device_transfers SET status = 'COMPLETED' WHERE user_id = ?", user);
        for (int i = 1; i < TransferService.MAX_REQUESTS_PER_DAY; i++) {
            jdbc.update("INSERT INTO device_transfers (id, user_id, target_device_id, ephemeral_public_key, request_signature, status, expires_at) "
                    + "VALUES (?, ?, ?, 'k', 's', 'COMPLETED', now() + interval '1 day')", UUID.randomUUID(), user, a.device());
        }
        Http.Response capped = http.post(url, Map.of("ephemeralPublicKey", b64(32), "signature", b64(64)), a.token());
        assertThat(capped.status()).isEqualTo(429);
        assertThat(capped.code()).isEqualTo("RATE_LIMITED");
    }
}
