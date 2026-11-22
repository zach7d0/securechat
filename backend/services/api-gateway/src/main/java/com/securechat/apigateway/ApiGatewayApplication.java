package com.securechat.apigateway;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Edge for REST traffic. JWTs are validated here (defence in depth — every service validates again);
 * {@code /internal/**} is never routed; a client-supplied X-Internal-Token is stripped; rate limits
 * use Redis (fail-open if Redis is down — the services still authenticate and authorize).
 */
@SpringBootApplication
@EnableWebFluxSecurity
public class ApiGatewayApplication {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${securechat.jwks-uri}") String jwks, @Value("${securechat.issuer}") String issuer,
            @Value("${securechat.audience}") String audience) {
        NimbusReactiveJwtDecoder d = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", a -> a != null && a.contains(audience))));
        return d;
    }

    /**
     * Admin API: a separate trust domain. Admin tokens are signed with the admin key and validated by
     * admin-service, so this chain must NOT run bearer-token validation against the user JWKS.
     */
    @Bean
    @Order(1)
    SecurityWebFilterChain adminRoutes(ServerHttpSecurity http) {
        return http.securityMatcher(org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers("/api/admin/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(a -> a.anyExchange().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(a -> a
                        // actuator is served only on the management port (internal network); main port has none
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .pathMatchers("/api/v1/**").authenticated()
                        .anyExchange().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(o -> o.jwt(j -> { }))
                .build();
    }

    /** Authenticated user id, else client IP. */
    @Bean
    @org.springframework.context.annotation.Primary
    KeyResolver userKeyResolver() {
        return ex -> ex.getPrincipal().map(p -> "u:" + p.getName()).switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(ex)));
    }

    @Bean
    KeyResolver ipKeyResolver() {
        return ex -> Mono.just("ip:" + clientIp(ex));
    }

    /** nginx sets X-Real-IP to the TCP peer; direct calls use the socket address. */
    static String clientIp(ServerWebExchange ex) {
        String real = ex.getRequest().getHeaders().getFirst("X-Real-IP");
        if (real != null && real.length() <= 45) {
            return real;
        }
        var addr = ex.getRequest().getRemoteAddress();
        return addr == null ? "unknown" : addr.getAddress().getHostAddress();
    }

    /** Correlation id: accept a safe caller value or create one; forward and echo it. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    GlobalFilter correlationId() {
        return (ex, chain) -> {
            String id = ex.getRequest().getHeaders().getFirst("X-Correlation-Id");
            String cid = id != null && SAFE_ID.matcher(id).matches() ? id : UUID.randomUUID().toString();
            ServerWebExchange mutated = ex.mutate().request(r -> r.headers(h -> {
                h.set("X-Correlation-Id", cid);
                h.remove("X-Internal-Token");
            })).build();
            mutated.getResponse().getHeaders().set("X-Correlation-Id", cid);
            return chain.filter(mutated);
        };
    }
}
