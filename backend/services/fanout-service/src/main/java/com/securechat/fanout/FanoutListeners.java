package com.securechat.fanout;

import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.Topics;
import com.securechat.support.kafka.Consumed;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class FanoutListeners {
    private final FanoutService fanout;
    private final Directory directory;

    FanoutListeners(FanoutService fanout, Directory directory) {
        this.fanout = fanout;
        this.directory = directory;
    }

    /** Partitioned by conversation id → per-conversation order is preserved. */
    @KafkaListener(topics = Topics.MESSAGE_ACCEPTED, groupId = "fanout-service")
    void accepted(String json) {
        fanout.fanout(Consumed.parse(json));
    }

    @KafkaListener(topics = {Topics.GROUP_MEMBER_ADDED, Topics.GROUP_MEMBER_REMOVED, Topics.CONVERSATION_CREATED},
            groupId = "fanout-service-invalidation")
    void membership(String json) {
        EventEnvelope e = Consumed.parse(json);
        directory.invalidateConversation(UUID.fromString(e.payload().path("conversationId").asString()));
    }

    @KafkaListener(topics = {Topics.DEVICE_REGISTERED, Topics.DEVICE_REVOKED}, groupId = "fanout-service-invalidation")
    void devices(String json) {
        EventEnvelope e = Consumed.parse(json);
        directory.invalidateUserDevices(UUID.fromString(e.payload().path("userId").asString()));
    }
}
