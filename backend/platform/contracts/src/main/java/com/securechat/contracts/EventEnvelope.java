package com.securechat.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Envelope carried by every Kafka record (JSON). Consumers must ignore unknown fields and
 * deduplicate by {@link #eventId()}. Within one {@link #eventVersion()} producers may only add
 * optional payload fields.
 *
 * @param eventId       stable, unique per logical event (reused on republish)
 * @param eventType     e.g. {@code message.accepted}; see {@link EventType}
 * @param eventVersion  major schema version of the payload
 * @param correlationId connects related business operations across services
 * @param traceId       distributed trace id at the producer (may be null)
 * @param timestamp     when the producer created the event
 * @param aggregateId   id of the entity the event is about (message, device, conversation…)
 * @param producer      producing service name
 * @param payload       event-type specific payload (see {@code events} package)
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String correlationId,
        String traceId,
        Instant timestamp,
        String aggregateId,
        String producer,
        JsonNode payload) {
}
