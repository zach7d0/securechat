package com.securechat.delivery;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.DeviceConnection;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.support.kafka.Consumed;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class DeliveryListeners {
    private final DeliveryService delivery;

    DeliveryListeners(DeliveryService delivery) {
        this.delivery = delivery;
    }

    /** Partitioned by recipient device → per-device order. */
    @KafkaListener(topics = Topics.MESSAGE_DELIVERY_REQUESTED, groupId = "delivery-service")
    void task(String json) {
        delivery.accept(EventCodec.payload(Consumed.parse(json), DeliveryRequested.class));
    }

    @KafkaListener(topics = Topics.DEVICE_CONNECTED, groupId = "delivery-service")
    void connected(String json) {
        delivery.replay(EventCodec.payload(Consumed.parse(json), DeviceConnection.class).deviceId());
    }

    @KafkaListener(topics = Topics.DEVICE_REVOKED, groupId = "delivery-service")
    void revoked(String json) {
        delivery.revokeDevice(EventCodec.payload(Consumed.parse(json), DeviceRevoked.class).deviceId());
    }
}
