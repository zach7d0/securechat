package com.securechat.admin;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securechat.admin")
public record AdminProperties(String privateKeyFile, Duration tokenTtl, String issuer, String audience, boolean mfaRequired,
        String mfaEncryptionKey, String bootstrapUsername, String bootstrapPassword, int maxFailedLogins, Duration lockout,
        String prometheusUrl, String prometheusUser, String prometheusPassword, List<String> healthTargets) {
}
