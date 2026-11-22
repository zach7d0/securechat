package com.securechat.delivery;

import com.securechat.contracts.EventCodec;
import java.util.Base64;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * Redis answers "which gateway owns this device right now?"; the payload travels on the owning
 * gateway's Pub/Sub channel (the explicit internal transport). A publish is NOT a delivery: only the
 * device's ACK marks DELIVERED. Redis errors make the device look offline (message stays pending).
 */
@Component
public class GatewayRouter {
    private final StringRedisTemplate redis;

    GatewayRouter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return the owning gateway id, or null if the device has no live connection */
    public String owner(UUID deviceId) {
        try {
            String v = redis.opsForValue().get("connection:device:" + deviceId);
            return v == null ? null : v.substring(0, v.indexOf('|'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return true if a gateway subscriber received the frame */
    public boolean route(String gatewayId, DeliveryStore.Pending p) {
        ObjectNode frame = EventCodec.mapper().createObjectNode();
        frame.put("type", "message").put("deliveryId", p.deliveryId().toString()).put("messageId", p.messageId().toString())
                .put("conversationId", p.conversationId().toString()).put("senderUserId", p.senderUserId().toString())
                .put("senderDeviceId", p.senderDeviceId().toString()).put("protocol", p.protocol()).put("messageType", p.messageType())
                .put("ciphertext", Base64.getEncoder().encodeToString(p.ciphertext())).put("createdAt", p.createdAt().toString());
        if (p.signalType() != null) {
            frame.put("signalType", p.signalType());
        }
        if (p.epoch() != null) {
            frame.put("epoch", p.epoch());
        }
        ObjectNode env = EventCodec.mapper().createObjectNode();
        env.put("type", "deliver").put("deviceId", p.deviceId().toString()).set("frame", frame);
        try {
            Long receivers = redis.convertAndSend("gw:" + gatewayId, env.toString());
            return receivers != null && receivers > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
