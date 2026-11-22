package com.securechat.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securechat.delivery")
public record DeliveryProperties(int shards, Duration expiry, Duration sweepInterval, Duration sweeperLease, Duration retryBase,
        Duration retryMax, int replayLimit) {
}
