package com.securechat.message;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("securechat.messages")
public record MessageProperties(
        int outboxShards,
        int outboxBatchSize,
        Duration outboxPollInterval,
        Duration outboxLease,
        Duration outboxQuarantineGrace,
        int outboxStuckAfter,
        int maxCiphertextBytes,
        int maxEnvelopes,
        int historyPageMax,
        int historyLookbackDays) {
}
