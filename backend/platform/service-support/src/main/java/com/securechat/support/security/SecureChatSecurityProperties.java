package com.securechat.support.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwksUri       Auth Service JWKS (resource servers validate RS256 access tokens)
 * @param issuer        expected {@code iss}
 * @param audience      expected {@code aud}
 * @param internalToken shared service credential for {@code /internal/**}
 * @param publicPaths   ant patterns reachable without a token (e.g. login)
 */
@ConfigurationProperties("securechat.security")
public record SecureChatSecurityProperties(
        String jwksUri,
        String issuer,
        String audience,
        String internalToken,
        List<String> publicPaths) {

    public SecureChatSecurityProperties {
        issuer = issuer == null ? "securechat-auth" : issuer;
        audience = audience == null ? "securechat" : audience;
        publicPaths = publicPaths == null ? new ArrayList<>() : publicPaths;
    }
}
