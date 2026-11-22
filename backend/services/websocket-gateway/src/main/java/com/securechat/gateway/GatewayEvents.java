package com.securechat.gateway;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.support.kafka.Consumed;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Status events pushed to locally connected clients. Every gateway instance uses its own consumer
 * group (broadcast) and starts at the latest offset: these notifications are ephemeral; clients
 * resynchronise state over REST on (re)connect.
 */
@Component
class GatewayEvents {
    private final LocalSessions sessions;
    private final Downstream downstream;

    GatewayEvents(LocalSessions sessions, Downstream downstream) {
        this.sessions = sessions;
        this.downstream = downstream;
    }

    @KafkaListener(topics = {Topics.MESSAGE_DELIVERED, Topics.MESSAGE_READ}, groupId = "#{@gatewayGroupId}")
    void receipt(String json) {
        EventEnvelope e = Consumed.parse(json);
        JsonNode p = e.payload();
        ObjectNode f = EventCodec.mapper().createObjectNode();
        f.put("type", "receipt").put("messageId", p.path("messageId").asString())
                .put("conversationId", p.path("conversationId").asString()).put("status", p.path("status").asString())
                .put("userId", p.path("recipientUserId").asString()).put("deviceId", p.path("recipientDeviceId").asString())
                .put("at", p.path("at").asString());
        String frame = f.toString();
        sessions.ofUser(UUID.fromString(p.path("senderUserId").asString())).forEach(c -> c.send(frame));
    }

    @KafkaListener(topics = Topics.PRESENCE_CHANGED, groupId = "#{@gatewayGroupId}")
    void presence(String json) {
        JsonNode p = Consumed.parse(json).payload();
        UUID user = UUID.fromString(p.path("userId").asString());
        ObjectNode f = EventCodec.mapper().createObjectNode();
        f.put("type", "presence").put("userId", user.toString()).put("online", p.path("online").asBoolean());
        if (p.hasNonNull("lastSeen")) {
            f.put("lastSeen", p.get("lastSeen").asString());
        }
        String frame = f.toString();
        // only users who share a conversation with the subject learn its presence
        sessions.authenticated().stream().filter(c -> c.peers.contains(user)).forEach(c -> c.send(frame));
    }

    @KafkaListener(topics = {Topics.CONVERSATION_CREATED, Topics.GROUP_MEMBER_ADDED, Topics.GROUP_MEMBER_REMOVED, Topics.GROUP_COMMIT},
            groupId = "#{@gatewayGroupId}")
    void membership(String json) {
        EventEnvelope e = Consumed.parse(json);
        JsonNode p = e.payload();
        String event = switch (e.eventType()) {
            case "conversation.created" -> "created";
            case "group.member.added" -> "member.added";
            case "group.member.removed" -> "member.removed";
            default -> "commit";
        };
        ObjectNode f = EventCodec.mapper().createObjectNode();
        f.put("type", "membership").put("conversationId", p.path("conversationId").asString()).put("event", event);
        if (p.has("epoch")) {
            f.put("epoch", p.get("epoch").asLong());
        }
        if (p.has("membershipVersion")) {
            f.put("membershipVersion", p.get("membershipVersion").asLong());
        }
        String frame = f.toString();
        Set<UUID> notify = new HashSet<>();
        p.path("memberUserIds").forEach(n -> notify.add(UUID.fromString(n.asString())));
        p.path("userIds").forEach(n -> notify.add(UUID.fromString(n.asString())));
        for (UUID u : notify) {
            for (LocalSessions.Conn c : sessions.ofUser(u)) {
                c.send(frame);
                if (!"commit".equals(event)) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            c.peers = new HashSet<>(downstream.peers(c.userId));
                        } catch (RuntimeException ignored) {
                            // keep previous peer set
                        }
                    });
                }
            }
        }
    }

    /**
     * A device came online. Its user's peers (and the user's other devices) are told, so a group member
     * whose client sees that this device is not yet in the MLS tree can add it right away (DEVICE_SYNC)
     * instead of waiting for its own next reconnect or membership change.
     */
    @KafkaListener(topics = Topics.DEVICE_CONNECTED, groupId = "#{@gatewayGroupId}")
    void deviceConnected(String json) {
        JsonNode p = Consumed.parse(json).payload();
        UUID user = UUID.fromString(p.path("userId").asString());
        UUID device = UUID.fromString(p.path("deviceId").asString());
        ObjectNode f = EventCodec.mapper().createObjectNode();
        f.put("type", "device").put("event", "connected").put("userId", user.toString()).put("deviceId", device.toString());
        String frame = f.toString();
        sessions.authenticated().stream()
                .filter(c -> (c.peers.contains(user) || user.equals(c.userId)) && !device.equals(c.deviceId))
                .forEach(c -> c.send(frame));
    }

    @KafkaListener(topics = Topics.DEVICE_REVOKED, groupId = "#{@gatewayGroupId}")
    void revoked(String json) {
        JsonNode p = Consumed.parse(json).payload();
        LocalSessions.Conn c = sessions.device(UUID.fromString(p.path("deviceId").asString()));
        if (c != null) {
            c.send("{\"type\":\"revoked\",\"reason\":\"device revoked\"}");
            c.close(ChatSocketHandler.REVOKED);
        }
    }

    @KafkaListener(topics = Topics.USER_STATUS_CHANGED, groupId = "#{@gatewayGroupId}")
    void status(String json) {
        JsonNode p = Consumed.parse(json).payload();
        if ("SUSPENDED".equals(p.path("status").asString())) {
            sessions.ofUser(UUID.fromString(p.path("userId").asString())).forEach(c -> {
                c.send("{\"type\":\"revoked\",\"reason\":\"account suspended\"}");
                c.close(ChatSocketHandler.REVOKED);
            });
        }
    }

    @KafkaListener(topics = Topics.KEYS_PREKEYS_LOW, groupId = "#{@gatewayGroupId}")
    void prekeysLow(String json) {
        JsonNode p = Consumed.parse(json).payload();
        LocalSessions.Conn c = sessions.device(UUID.fromString(p.path("deviceId").asString()));
        if (c != null) {
            c.send("{\"type\":\"prekeys_low\",\"remainingPrekeys\":" + p.path("remainingPrekeys").asLong()
                    + ",\"remainingKeyPackages\":" + p.path("remainingKeyPackages").asLong() + "}");
        }
    }

    static final CloseStatus GOING_AWAY = CloseStatus.GOING_AWAY;
}
