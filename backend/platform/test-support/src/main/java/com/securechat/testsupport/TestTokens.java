package com.securechat.testsupport;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Mints Auth-Service-shaped RS256 access tokens and serves their JWKS for resource-server tests. */
public final class TestTokens {
    private static RSAKey key;
    private static String jwksUrl;

    private TestTokens() {
    }

    public static synchronized String jwksUrl() {
        if (jwksUrl == null) {
            try {
                key = new RSAKeyGenerator(2048).keyID("test-key").generate();
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/jwks.json", ex -> {
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                });
                server.setExecutor(null);
                server.start();
                jwksUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
            } catch (IOException | com.nimbusds.jose.JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
        return jwksUrl;
    }

    public static void properties(DynamicPropertyRegistry r) {
        r.add("securechat.security.jwks-uri", TestTokens::jwksUrl);
    }

    public static String token(UUID userId, String username, UUID deviceId) {
        return token(userId, username, deviceId, "securechat-auth", "securechat", Instant.now().plusSeconds(300));
    }

    public static String token(UUID userId, String username, UUID deviceId, String issuer, String audience, Instant exp) {
        jwksUrl();
        try {
            JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(issuer).audience(List.of(audience))
                    .subject(userId.toString()).issueTime(new Date()).expirationTime(Date.from(exp))
                    .jwtID(UUID.randomUUID().toString()).claim("preferred_username", username)
                    .claim("sid", UUID.randomUUID().toString());
            if (deviceId != null) {
                c.claim("did", deviceId.toString());
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                    .type(JOSEObjectType.JWT).build(), c.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
