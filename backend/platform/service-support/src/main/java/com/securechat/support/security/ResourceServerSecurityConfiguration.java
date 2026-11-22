package com.securechat.support.security;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;

/**
 * Default security for SecureChat resource servers:
 * <ul>
 *   <li>{@code /internal/**}: service credential only ({@link InternalTokenFilter});</li>
 *   <li>configured public paths and actuator (management port, internal network only): open;</li>
 *   <li>everything else: RS256 JWT from Auth Service with issuer + audience validation.</li>
 * </ul>
 * Stateless; CSRF is disabled because no cookie authentication is used.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HttpSecurity.class, NimbusJwtDecoder.class})
@EnableConfigurationProperties(SecureChatSecurityProperties.class)
@ConditionalOnProperty(prefix = "securechat.security", name = "mode", havingValue = "resource-server", matchIfMissing = true)
public class ResourceServerSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(SecureChatSecurityProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwksUri()).build();
        decoder.setJwtValidator(validator(props.issuer(), props.audience()));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> aud = new JwtClaimValidator<List<String>>("aud", a -> a != null && a.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud);
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain secureChatFilterChain(HttpSecurity http, SecureChatSecurityProperties props) throws Exception {
        String[] open = props.publicPaths().toArray(String[]::new);
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalTokenFilter(props.internalToken()), AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/actuator/**", "/error").permitAll();
                    a.requestMatchers("/internal/**").hasAuthority(InternalTokenFilter.AUTHORITY);
                    if (open.length > 0) {
                        a.requestMatchers(open).permitAll();
                    }
                    a.anyRequest().authenticated();
                })
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(o -> o.jwt(j -> { }));
        return http.build();
    }
}
