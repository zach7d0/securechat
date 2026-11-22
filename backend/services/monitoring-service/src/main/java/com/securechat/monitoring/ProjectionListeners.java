package com.securechat.monitoring;

import com.securechat.contracts.Topics;
import com.securechat.support.kafka.Consumed;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Own consumer group ("monitoring-projection"): never shares offsets with Fanout or Delivery. */
@Component
class ProjectionListeners {
    private final Projection projection;

    ProjectionListeners(Projection projection) {
        this.projection = projection;
    }

    @KafkaListener(topics = Topics.MESSAGE_ACCEPTED, groupId = "monitoring-projection")
    void accepted(ConsumerRecord<String, String> rec) {
        projection.accepted(Consumed.parse(rec.value()), Instant.ofEpochMilli(rec.timestamp()));
    }

    @KafkaListener(topics = Topics.MESSAGE_FANOUT_COMPLETED, groupId = "monitoring-projection")
    void fanout(String json) {
        projection.fanoutCompleted(Consumed.parse(json));
    }

    @KafkaListener(topics = Topics.MESSAGE_DELIVERY_REQUESTED, groupId = "monitoring-projection")
    void requested(String json) {
        projection.deliveryRequested(Consumed.parse(json));
    }

    @KafkaListener(topics = Topics.MESSAGE_DELIVERED, groupId = "monitoring-projection")
    void delivered(String json) {
        projection.receipt(Consumed.parse(json), "DELIVERED");
    }

    @KafkaListener(topics = Topics.MESSAGE_READ, groupId = "monitoring-projection")
    void read(String json) {
        projection.receipt(Consumed.parse(json), "READ");
    }

    @KafkaListener(topics = Topics.MESSAGE_FAILED, groupId = "monitoring-projection")
    void failed(String json) {
        projection.failed(Consumed.parse(json));
    }
}
