package com.securechat.message;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.testsupport.Infra;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

final class TestSupport {
    private TestSupport() {
    }

    /** Collects all envelopes on {@code topic} matching {@code filter} for {@code window}. */
    static List<EventEnvelope> collect(String topic, Predicate<EventEnvelope> filter, Duration window) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Infra.kafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "t-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<EventEnvelope> out = new ArrayList<>();
        try (var c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(topic));
            long end = System.currentTimeMillis() + window.toMillis();
            while (System.currentTimeMillis() < end) {
                for (var rec : c.poll(Duration.ofMillis(300))) {
                    EventEnvelope e = EventCodec.fromJson(rec.value());
                    if (filter.test(e)) {
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }
}
