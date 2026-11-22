package com.securechat.admin;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.securechat.support.security.ResourceServerSecurityConfiguration;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Admin trust domain: tokens are signed with the ADMIN key (iss/aud securechat-admin), so end-user
 * tokens are rejected here and admin tokens are rejected by every chat service. Authorities:
 * ROLE_&lt;role&gt; for RBAC plus STAGE_&lt;stage&gt; (FULL, MFA_ENROLLMENT, PASSWORD_CHANGE).
 */
@Configuration
@EnableMethodSecurity
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.securechat.support.security.SecureChatSecurityProperties.class)
class AdminSecurityConfig {

    @Bean
    SigningKeys adminKeys(AdminProperties props) {
        return SigningKeys.fromPemFile(props.privateKeyFile());
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(keys.jwk())));
    }

    @Bean
    JwtDecoder jwtDecoder(SigningKeys keys, AdminProperties props) throws Exception {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.jwk().toPublicKey()).build();
        d.setJwtValidator(ResourceServerSecurityConfiguration.validator(props.issuer(), props.audience()));
        return d;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }

    static List<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> out = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.forEach(r -> out.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        out.add(new SimpleGrantedAuthority("STAGE_" + jwt.getClaimAsString("stage")));
        return out;
    }

    @Bean
    SecurityFilterChain adminChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(AdminSecurityConfig::authorities);
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**", "/error").permitAll()
                        .requestMatchers("/api/admin/v1/auth/login").permitAll()
                        .requestMatchers("/api/admin/v1/auth/password", "/api/admin/v1/mfa/**", "/api/admin/v1/me").authenticated()
                        .requestMatchers("/api/admin/v1/**").hasAuthority("STAGE_FULL")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)));
        return http.build();
    }
}
