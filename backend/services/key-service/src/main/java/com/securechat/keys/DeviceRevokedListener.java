package com.securechat.keys;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.kafka.Consumed;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class DeviceRevokedListener {
    private final KeyService keys;

    DeviceRevokedListener(KeyService keys) {
        this.keys = keys;
    }

    @KafkaListener(topics = Topics.DEVICE_REVOKED, groupId = "key-service")
    void onRevoked(String json) {
        keys.revokeDevice(EventCodec.payload(Consumed.parse(json), DeviceRevoked.class).deviceId());
    }
}
