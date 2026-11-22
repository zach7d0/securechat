package com.securechat.gateway;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securechat.gateway")
public record GatewayProperties(String id, List<String> allowedOrigins, Duration authTimeout, Duration connectionTtl,
        Duration tokenGrace, Duration presenceHeartbeatInterval, int maxFrameBytes, int messagesPerSecond) {

    public GatewayProperties {
        if (id == null || id.isBlank()) {
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "gw";
            }
            id = host + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
    }
}
