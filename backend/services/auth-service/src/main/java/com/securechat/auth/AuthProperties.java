package com.securechat.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securechat.auth")
public record AuthProperties(
        String privateKeyFile,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int loginAttemptsPerUser,
        int loginAttemptsPerIp,
        Duration loginWindow) {
}
