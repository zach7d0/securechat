package com.securechat.support.outbox;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.EventType;
import com.securechat.support.web.Correlation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an event into {@code outbox_events} inside the caller's transaction, so the business
 * change and the event commit (or roll back) together. Publication happens later in
 * {@link OutboxPublisher}. Table DDL: see service-support README.
 */
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final String producer;

    public OutboxWriter(JdbcTemplate jdbc, String producer) {
        this.jdbc = jdbc;
        this.producer = producer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(EventType type, String key, String aggregateId, Object payload) {
        return write(type, UUID.randomUUID(), key, aggregateId, payload);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(EventType type, UUID eventId, String key, String aggregateId, Object payload) {
        EventEnvelope env = EventCodec.envelope(type, eventId, aggregateId, payload, Correlation.current(), null, producer);
        jdbc.update("""
                INSERT INTO outbox_events (id, topic, msg_key, event_type, payload, created_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                """, eventId, type.topic(), key, type.wireName(), EventCodec.toJson(env),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return eventId;
    }
}
