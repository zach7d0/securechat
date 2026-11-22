package com.securechat.auth;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.kafka.Consumed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Device revoked in User Service → revoke every refresh session bound to it (idempotent). */
@Component
class DeviceRevokedListener {
    private static final Logger log = LoggerFactory.getLogger(DeviceRevokedListener.class);
    private final AuthService auth;

    DeviceRevokedListener(AuthService auth) {
        this.auth = auth;
    }

    @KafkaListener(topics = Topics.DEVICE_REVOKED, groupId = "auth-service")
    void onRevoked(String json) {
        EventEnvelope env = Consumed.parse(json);
        DeviceRevoked e = EventCodec.payload(env, DeviceRevoked.class);
        int n = auth.revokeDeviceSessions(e.deviceId());
        log.info("device sessions revoked deviceId={} sessions={}", e.deviceId(), n);
    }
}
