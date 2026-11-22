package com.securechat.apigateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.securechat.testsupport.Http;
import com.securechat.testsupport.Infra;
import com.securechat.testsupport.TestTokens;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayIT {
    static final List<Map<String, String>> SEEN = new CopyOnWriteArrayList<>();
    static String upstream;

    static {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                ex.getRequestBody().readAllBytes();
                SEEN.add(Map.of("path", ex.getRequestURI().getPath(),
                        "internal", String.valueOf(ex.getRequestHeaders().getFirst("X-Internal-Token")),
                        "correlation", String.valueOf(ex.getRequestHeaders().getFirst("X-Correlation-Id"))));
                byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            upstream = "http://127.0.0.1:" + s.getAddress().getPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Infra.redisProperties(r);
        r.add("management.server.port", () -> "0");
        r.add("logging.structured.format.console", () -> "");
        r.add("securechat.jwks-uri", TestTokens::jwksUrl);
        for (String s : List.of("AUTH", "USER", "KEY", "MESSAGE", "CONVERSATION", "DELIVERY", "PRESENCE", "ADMIN")) {
            r.add(s + "_SERVICE_URL", () -> upstream);
        }
    }

    @Autowired
    Environment env;
    Http http;

    @BeforeEach
    void setUp() {
        http = new Http("http://localhost:" + env.getProperty("local.server.port"));
    }

    @Test
    void protectedRoutesRequireValidTokenAndPublicAuthRoutesDoNot() {
        assertThat(http.get("/api/v1/users/me", null).status()).isEqualTo(401);
        assertThat(http.get("/api/v1/users/me", "garbage").status()).isEqualTo(401);
        String wrongAud = TestTokens.token(UUID.randomUUID(), "u", null, "securechat-auth", "other", Instant.now().plusSeconds(60));
        assertThat(http.get("/api/v1/users/me", wrongAud).status()).isEqualTo(401);
        String ok = TestTokens.token(UUID.randomUUID(), "u", null);
        assertThat(http.get("/api/v1/users/me", ok).status()).isEqualTo(200);
        assertThat(http.post("/api/v1/auth/login", Map.of("login", "x", "password", "y"), null).status()).isEqualTo(200);
    }

    @Test
    void internalPathsAreNeverRoutedAndInternalCredentialIsStripped() {
        String ok = TestTokens.token(UUID.randomUUID(), "u", null);
        assertThat(http.get("/internal/v1/stats", ok).status()).isIn(401, 403);
        assertThat(http.internal("GET", "/internal/v1/stats", null).status()).isIn(401, 403);
        int before = SEEN.size();
        Http.Response r = http.call("GET", "/api/v1/conversations", null, ok, Map.of("X-Internal-Token", Infra.INTERNAL_TOKEN,
                "X-Correlation-Id", "corr-123"));
        assertThat(r.status()).isEqualTo(200);
        Map<String, String> seen = SEEN.get(before);
        assertThat(seen.get("internal")).isEqualTo("null");
        assertThat(seen.get("correlation")).isEqualTo("corr-123");
        assertThat(SEEN.stream().noneMatch(m -> m.get("path").startsWith("/internal"))).isTrue();
    }

    @Test
    void loginIsRateLimitedPerClientIp() {
        int limited = 0;
        for (int i = 0; i < 25; i++) {
            if (http.call("POST", "/api/v1/auth/login", Map.of("login", "x", "password", "y"), null, Map.of("X-Real-IP", "203.0.113.9"))
                    .status() == 429) {
                limited++;
            }
        }
        assertThat(limited).isGreaterThan(5);
        // a different client is unaffected
        assertThat(http.call("POST", "/api/v1/auth/login", Map.of("login", "x", "password", "y"), null, Map.of("X-Real-IP", "198.51.100.7"))
                .status()).isEqualTo(200);
    }

    @Test
    void oversizedRequestsAreRejected() {
        String ok = TestTokens.token(UUID.randomUUID(), "u", null);
        Http.Response r = http.post("/api/v1/conversations/" + UUID.randomUUID() + "/messages", Map.of("ciphertext", "A".repeat(400_000)), ok);
        assertThat(r.status()).isEqualTo(413);
    }

    /** Regression: an admin bearer token (foreign key/issuer) must pass through to admin-service untouched. */
    @Test
    void adminRoutesAreNotValidatedAgainstTheUserJwks() {
        String adminToken = TestTokens.token(UUID.randomUUID(), "admin", null, "securechat-admin", "securechat-admin", Instant.now().plusSeconds(60));
        assertThat(http.get("/api/admin/v1/dashboard", adminToken).status()).isEqualTo(200);
        assertThat(http.get("/api/admin/v1/dashboard", "not-a-jwt").status()).isEqualTo(200);   // admin-service decides
    }

    @Test
    void unknownPathsAreDenied() {
        assertThat(http.get("/v3/api-docs", null).status()).isIn(401, 403, 404);
        assertThat(http.get("/actuator/env", null).status()).isIn(401, 403, 404);   // not exposed on the main port
    }
}
