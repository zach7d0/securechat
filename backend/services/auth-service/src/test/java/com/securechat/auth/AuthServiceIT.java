package com.securechat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventType;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.support.kafka.RetryableDependencyException;
import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthServiceIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.postgresProperties(r, "auth_db");
        Infra.kafkaProperties(r);
        Infra.commonProperties(r);
        r.add("securechat.auth.private-key-file", () -> Infra.jwtKeyFile().toString());
        r.add("securechat.auth.login-attempts-per-user", () -> "5");
    }

    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EventPublisher publisher;
    @MockitoBean
    DeviceDirectory devices;

    Http http;

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
    }

    static String uniq() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    Http.Response register(String username) {
        return http.post("/api/v1/auth/register", Map.of("email", username + "@example.test", "username", username,
                "password", "correct horse battery", "displayName", "Test " + username), null);
    }

    @Test
    void registerIssuesRs256TokenVerifiableWithJwks() throws Exception {
        String u = uniq();
        Http.Response r = register(u);
        assertThat(r.status()).isEqualTo(201);
        String at = r.text("accessToken");
        SignedJWT jwt = SignedJWT.parse(at);
        JWKSet jwks = JWKSet.parse(http.get("/.well-known/jwks.json", null).body().toString());
        RSAKey key = (RSAKey) jwks.getKeyByKeyId(jwt.getHeader().getKeyID());
        assertThat(key).isNotNull();
        assertThat(key.isPrivate()).as("JWKS must not leak private key").isFalse();
        assertThat(jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(key))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("securechat-auth");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("securechat");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("preferred_username")).isEqualTo(u);
        assertThat(jwt.getJWTClaimsSet().getStringClaim("did")).isNull();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isBefore(Instant.now().plusSeconds(301));
        assertThat(http.get("/api/v1/auth/me", at).status()).isEqualTo(200);
    }

    @Test
    void registrationValidationAndDuplicates() {
        String u = uniq();
        assertThat(register(u).status()).isEqualTo(201);
        assertThat(register(u).code()).isEqualTo("CONFLICT");
        assertThat(http.post("/api/v1/auth/register", Map.of("email", "x@example.test", "username", "ab",
                "password", "correct horse battery"), null).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(http.post("/api/v1/auth/register", Map.of("email", uniq() + "@example.test", "username", uniq(),
                "password", "short"), null).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void passwordsAndRefreshTokensAreNeverStoredRaw() {
        String u = uniq();
        Http.Response r = register(u);
        String refresh = r.text("refreshToken");
        UUID id = UUID.fromString(r.text("userId"));
        String hash = jdbc.queryForObject("SELECT password_hash FROM credentials WHERE user_id = ?", String.class, id);
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain("correct horse");
        Long raw = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class, refresh);
        assertThat(raw).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Long.class,
                TokenService.hash(refresh))).isEqualTo(1L);
    }

    @Test
    void invalidCredentialsAreRejectedUniformly() {
        String u = uniq();
        register(u);
        Http.Response wrong = http.post("/api/v1/auth/login", Map.of("login", u, "password", "wrong password!!"), null);
        Http.Response unknown = http.post("/api/v1/auth/login", Map.of("login", uniq(), "password", "wrong password!!"), null);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
        Http.Response ok = http.post("/api/v1/auth/login", Map.of("login", u + "@example.test", "password", "correct horse battery"), null);
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    void loginRateLimitHook() {
        String u = uniq();
        register(u);
        int limited = 0;
        for (int i = 0; i < 7; i++) {
            if (http.post("/api/v1/auth/login", Map.of("login", u, "password", "wrong password!!"), null).status() == 429) {
                limited++;
            }
        }
        assertThat(limited).isGreaterThanOrEqualTo(2);
    }

    @Test
    void refreshRotationAndReuseDetectionRevokesFamily() {
        Http.Response r = register(uniq());
        String r1 = r.text("refreshToken");
        Http.Response rot = http.post("/api/v1/auth/refresh", Map.of("refreshToken", r1), null);
        assertThat(rot.status()).isEqualTo(200);
        String r2 = rot.text("refreshToken");
        assertThat(r2).isNotEqualTo(r1);
        // attacker replays the rotated token → whole session revoked, including r2
        Http.Response reuse = http.post("/api/v1/auth/refresh", Map.of("refreshToken", r1), null);
        assertThat(reuse.code()).isEqualTo("TOKEN_REUSED");
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", r2), null).status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesSession() {
        String refresh = register(uniq()).text("refreshToken");
        assertThat(http.post("/api/v1/auth/logout", Map.of("refreshToken", refresh), null).status()).isEqualTo(204);
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", refresh), null).status()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsRequireValidToken() {
        String at = register(uniq()).text("accessToken");
        assertThat(http.get("/api/v1/auth/me", null).status()).isEqualTo(401);
        String tampered = at.substring(0, at.length() - 4) + (at.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThat(http.get("/api/v1/auth/me", tampered).status()).isEqualTo(401);
        assertThat(http.post("/api/v1/auth/device-session", Map.of("deviceId", UUID.randomUUID()), null).status()).isEqualTo(401);
    }

    @Test
    void deviceSessionRequiresOwnedActiveDevice() throws Exception {
        Http.Response reg = register(uniq());
        UUID userId = UUID.fromString(reg.text("userId"));
        String at = reg.text("accessToken");
        String firstRefresh = reg.text("refreshToken");
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        when(devices.find(mine)).thenReturn(Optional.of(new DeviceDirectory.DeviceInfo(mine, userId, "ACTIVE")));
        when(devices.find(other)).thenReturn(Optional.of(new DeviceDirectory.DeviceInfo(other, UUID.randomUUID(), "ACTIVE")));
        when(devices.find(revoked)).thenReturn(Optional.of(new DeviceDirectory.DeviceInfo(revoked, userId, "REVOKED")));

        assertThat(http.post("/api/v1/auth/device-session", Map.of("deviceId", other), at).status()).isEqualTo(404);
        assertThat(http.post("/api/v1/auth/device-session", Map.of("deviceId", revoked), at).code()).isEqualTo("DEVICE_REVOKED");
        Http.Response bound = http.post("/api/v1/auth/device-session", Map.of("deviceId", mine), at);
        assertThat(bound.status()).isEqualTo(200);
        assertThat(SignedJWT.parse(bound.text("accessToken")).getJWTClaimsSet().getStringClaim("did")).isEqualTo(mine.toString());
        // previous unbound session is revoked; refreshing the bound one keeps the device binding
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", firstRefresh), null).status()).isEqualTo(401);
        Http.Response rot = http.post("/api/v1/auth/refresh", Map.of("refreshToken", bound.text("refreshToken")), null);
        assertThat(SignedJWT.parse(rot.text("accessToken")).getJWTClaimsSet().getStringClaim("did")).isEqualTo(mine.toString());
    }

    @Test
    void deviceSessionFailsClosedWhenDirectoryUnavailable() {
        String at = register(uniq()).text("accessToken");
        when(devices.find(any())).thenThrow(new RetryableDependencyException("down"));
        Http.Response r = http.post("/api/v1/auth/device-session", Map.of("deviceId", UUID.randomUUID()), at);
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void deviceRevokedEventRevokesBoundSessions() {
        Http.Response reg = register(uniq());
        UUID userId = UUID.fromString(reg.text("userId"));
        UUID device = UUID.randomUUID();
        when(devices.find(device)).thenReturn(Optional.of(new DeviceDirectory.DeviceInfo(device, userId, "ACTIVE")));
        String refresh = http.post("/api/v1/auth/device-session", Map.of("deviceId", device), reg.text("accessToken"))
                .text("refreshToken");
        publisher.publish(EventType.DEVICE_REVOKED, UUID.randomUUID(), userId.toString(), device.toString(),
                new DeviceRevoked(userId, device, "USER", Instant.now()));
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE device_id = ? AND revoked_at IS NULL",
                        Long.class, device) == 0);
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", refresh), null).status()).isEqualTo(401);
    }

    @Test
    void suspensionBlocksLoginAndRefreshAndInternalApiNeedsCredential() {
        String u = uniq();
        Http.Response reg = register(u);
        String id = reg.text("userId");
        assertThat(http.put("/internal/v1/accounts/" + id + "/status", Map.of("status", "SUSPENDED"), null).status())
                .isIn(401, 403);
        assertThat(http.internal("PUT", "/internal/v1/accounts/" + id + "/status", Map.of("status", "SUSPENDED")).status())
                .isEqualTo(204);
        assertThat(http.post("/api/v1/auth/login", Map.of("login", u, "password", "correct horse battery"), null).code())
                .isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", reg.text("refreshToken")), null).status())
                .isIn(401, 403);
        Http.Response accounts = http.internal("POST", "/internal/v1/accounts:batchGet", Map.of("ids", List.of(id)));
        assertThat(accounts.body().get(0).get("status").asString()).isEqualTo("SUSPENDED");
    }

    @Test
    void userRegisteredEventIsPublishedThroughOutbox() {
        String id = register(uniq()).text("userId");
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.USER_REGISTERED));
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (var rec : c.poll(Duration.ofMillis(500))) {
                    var env = EventCodec.fromJson(rec.value());
                    if (env.aggregateId().equals(id)) {
                        assertThat(env.eventType()).isEqualTo("user.registered");
                        assertThat(rec.value()).doesNotContain("correct horse");
                        return true;
                    }
                }
                return false;
            });
        }
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE msg_key = ? AND published_at IS NOT NULL", Long.class, id) == 1);
    }

    @Test
    void ownerCanViewAndChangeEmailWithPasswordConfirmation() {
        String u = uniq();
        String at = register(u).text("accessToken");
        Http.Response acc = http.get("/api/v1/auth/account", at);
        assertThat(acc.status()).isEqualTo(200);
        assertThat(acc.text("email")).isEqualTo(u + "@example.test");
        assertThat(http.get("/api/v1/auth/account", null).status()).isEqualTo(401);

        assertThat(http.put("/api/v1/auth/account/email", Map.of("email", "new-" + u + "@example.test",
                "currentPassword", "wrong password!!"), at).status()).isEqualTo(401);
        assertThat(http.put("/api/v1/auth/account/email", Map.of("email", "not-an-email",
                "currentPassword", "correct horse battery"), at).status()).isEqualTo(400);
        String other = uniq();
        register(other);
        assertThat(http.put("/api/v1/auth/account/email", Map.of("email", other + "@example.test",
                "currentPassword", "correct horse battery"), at).status()).isEqualTo(409);

        Http.Response ok = http.put("/api/v1/auth/account/email", Map.of("email", "New-" + u + "@Example.test",
                "currentPassword", "correct horse battery"), at);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.text("email")).isEqualTo("new-" + u + "@example.test");
        assertThat(http.get("/api/v1/auth/account", at).text("email")).isEqualTo("new-" + u + "@example.test");
        assertThat(http.post("/api/v1/auth/login", Map.of("login", "new-" + u + "@example.test",
                "password", "correct horse battery"), null).status()).isEqualTo(200);
    }

    @Test
    void ownerCanChangePasswordAndOtherSessionsAreSignedOut() {
        String u = uniq();
        Http.Response first = register(u);
        String at = first.text("accessToken");
        Http.Response other = http.post("/api/v1/auth/login", Map.of("login", u, "password", "correct horse battery"), null);
        String pw = "/api/v1/auth/account/password";

        assertThat(http.put(pw, Map.of("currentPassword", "correct horse battery", "newPassword", "new secret phrase 42"), null)
                .status()).isEqualTo(401);
        assertThat(http.put(pw, Map.of("currentPassword", "wrong password!!", "newPassword", "new secret phrase 42"), at)
                .code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(http.put(pw, Map.of("currentPassword", "correct horse battery", "newPassword", "short"), at).status()).isEqualTo(400);
        assertThat(http.put(pw, Map.of("currentPassword", "correct horse battery", "newPassword", "my " + u + " pass"), at)
                .status()).isEqualTo(400);
        assertThat(http.put(pw, Map.of("currentPassword", "correct horse battery", "newPassword", "correct horse battery"), at)
                .status()).isEqualTo(400);

        Http.Response ok = http.put(pw, Map.of("currentPassword", "correct horse battery", "newPassword", "new secret phrase 42"), at);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("otherSessionsRevoked").asInt()).isEqualTo(1);

        // old password no longer works, new one does
        assertThat(http.post("/api/v1/auth/login", Map.of("login", u, "password", "correct horse battery"), null).status()).isEqualTo(401);
        assertThat(http.post("/api/v1/auth/login", Map.of("login", u, "password", "new secret phrase 42"), null).status()).isEqualTo(200);
        // the other session is signed out; the session that changed the password keeps working
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", other.text("refreshToken")), null).status()).isEqualTo(401);
        assertThat(http.post("/api/v1/auth/refresh", Map.of("refreshToken", first.text("refreshToken")), null).status()).isEqualTo(200);
    }
}
