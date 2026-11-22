package com.securechat.contracts;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Serialises envelopes and typed payloads. Unknown fields are ignored (forward compatibility). */
public final class EventCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private EventCodec() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static EventEnvelope envelope(EventType type, UUID eventId, String aggregateId, Object payload,
            String correlationId, String traceId, String producer) {
        if (!type.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException(type + " requires payload " + type.payloadType().getSimpleName());
        }
        return new EventEnvelope(eventId, type.wireName(), type.version(), correlationId, traceId, Instant.now(),
                aggregateId, producer, MAPPER.valueToTree(payload));
    }

    public static String toJson(EventEnvelope envelope) {
        return MAPPER.writeValueAsString(envelope);
    }

    public static EventEnvelope fromJson(String json) {
        return MAPPER.readValue(json, EventEnvelope.class);
    }

    public static <T> T payload(EventEnvelope envelope, Class<T> type) {
        return MAPPER.treeToValue(envelope.payload(), type);
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }
}
