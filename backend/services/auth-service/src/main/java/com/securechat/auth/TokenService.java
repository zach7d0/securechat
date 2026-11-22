package com.securechat.auth;

import com.securechat.support.security.SecureChatSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Issues RS256 access tokens and opaque refresh tokens (only their SHA-256 is stored). */
@Component
public class TokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JwtEncoder encoder;
    private final AuthProperties props;
    private final SecureChatSecurityProperties security;

    TokenService(JwtEncoder encoder, AuthProperties props, SecureChatSecurityProperties security) {
        this.encoder = encoder;
        this.props = props;
        this.security = security;
    }

    public record AccessToken(String value, Instant expiresAt) {
    }

    public AccessToken accessToken(UUID userId, String username, UUID sessionId, UUID deviceId) {
        Instant now = Instant.now();
        Instant exp = now.plus(props.accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(security.issuer())
                .audience(List.of(security.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString())
                .claim("preferred_username", username)
                .claim("sid", sessionId.toString());
        if (deviceId != null) {
            claims.claim("did", deviceId.toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue(), exp);
    }

    /** 256-bit random, URL-safe. */
    public static String newRefreshToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Refresh tokens have 256 bits of entropy, so a plain SHA-256 is an adequate lookup hash. */
    public static String hash(String refreshToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
