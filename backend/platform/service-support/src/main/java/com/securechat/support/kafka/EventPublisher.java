package com.securechat.support.kafka;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.EventType;
import com.securechat.support.web.Correlation;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Publishes envelopes and <b>waits for the broker acknowledgement</b> ({@code acks=all},
 * idempotent producer configured in securechat-defaults.yml). A returned call means Kafka
 * acknowledged the record; an exception means publication must be treated as not done.
 */
public class EventPublisher {
    public static final String CORRELATION_HEADER = "correlationId";
    private final KafkaTemplate<String, String> kafka;
    private final String producer;
    private final Duration timeout;

    public EventPublisher(KafkaTemplate<String, String> kafka, String producer, Duration timeout) {
        this.kafka = kafka;
        this.producer = producer;
        this.timeout = timeout;
    }

    public String producer() {
        return producer;
    }

    public EventEnvelope envelope(EventType type, UUID eventId, String aggregateId, Object payload) {
        return EventCodec.envelope(type, eventId, aggregateId, payload, Correlation.current(), null, producer);
    }

    /** Builds and synchronously publishes. */
    public void publish(EventType type, UUID eventId, String key, String aggregateId, Object payload) {
        sendRaw(type.topic(), key, EventCodec.toJson(envelope(type, eventId, aggregateId, payload)), Correlation.current());
    }

    /** Starts an asynchronous send (callers must join all futures before committing offsets). */
    public CompletableFuture<SendResult<String, String>> sendAsync(String topic, String key, String json, String correlationId) {
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, key, json);
        if (correlationId != null) {
            rec.headers().add(CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return kafka.send(rec);
        } catch (RuntimeException e) {
            // e.g. metadata unavailable (broker down): KafkaTemplate throws synchronously instead of
            // failing the future. Normalise so every caller sees one failure path.
            return CompletableFuture.failedFuture(e);
        }
    }

    public void sendRaw(String topic, String key, String json, String correlationId) {
        try {
            sendAsync(topic, key, json, correlationId).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException(topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishFailedException(topic, e);
        }
    }

    public static void await(CompletableFuture<?> f, Duration timeout, String topic) {
        try {
            f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException(topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishFailedException(topic, e);
        }
    }

    public static class PublishFailedException extends RetryableDependencyException {
        public PublishFailedException(String topic, Throwable cause) {
            super("kafka publish to " + topic + " not acknowledged: " + cause.getClass().getSimpleName(), cause);
        }
    }
}
