package com.securechat.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MVP provider: no APNs/FCM credentials are part of the MVP; records the generic push only. */
@Component
@ConditionalOnProperty(prefix = "securechat.notification", name = "provider", havingValue = "log", matchIfMissing = true)
class LoggingPushProvider implements PushProvider {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushProvider.class);

    @Override
    public void send(Push push) {
        log.info("push (log provider) userId={} deviceId={}", push.userId(), push.deviceId());
    }
}
