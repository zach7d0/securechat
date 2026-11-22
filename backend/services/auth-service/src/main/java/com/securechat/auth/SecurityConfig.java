package com.securechat.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.securechat.support.security.ResourceServerSecurityConfiguration;
import com.securechat.support.security.SecureChatSecurityProperties;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
class SecurityConfig {

    @Bean
    SigningKeys signingKeys(AuthProperties props) {
        return SigningKeys.fromPemFile(props.privateKeyFile());
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(keys.jwk())));
    }

    /** Auth validates its own tokens with the local public key (no JWKS round trip to itself). */
    @Bean
    JwtDecoder jwtDecoder(SigningKeys keys, SecureChatSecurityProperties props) throws Exception {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.jwk().toPublicKey()).build();
        d.setJwtValidator(ResourceServerSecurityConfiguration.validator(props.issuer(), props.audience()));
        return d;
    }

    /** Argon2id, OWASP 2024 baseline: m=19 MiB, t=2, p=1, 16-byte salt, 32-byte hash. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
