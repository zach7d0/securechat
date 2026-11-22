package com.securechat.monitoring;

import com.securechat.contracts.EventEnvelope;
import com.securechat.support.outbox.ProcessedEvents;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Builds the operational projection from lifecycle events. Each event is applied once (event-id
 * dedupe in the same transaction). Per-device status only moves forward
 * (PENDING < DELIVERED < READ; FAILED only from PENDING), so out-of-order events across topics
 * converge. Hourly counters are incremented only on a real first transition, so duplicates never
 * inflate reports. Ciphertext fields in the events are ignored and never stored.
 */
@Service
public class Projection {
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;

    Projection(JdbcTemplate jdbc, ProcessedEvents processed) {
        this.jdbc = jdbc;
        this.processed = processed;
    }

    private static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static Instant instant(JsonNode n) {
        return n == null || n.isNull() ? null : Instant.parse(n.asString());
    }

    private static UUID uuid(JsonNode n) {
        return n == null || n.isNull() ? null : UUID.fromString(n.asString());
    }

    private void milestone(EventEnvelope e, String m, UUID messageId, UUID conv, String service, UUID device, Instant at, String error) {
        jdbc.update("""
                INSERT INTO message_lifecycle_events (event_id, milestone, message_id, conversation_id, service, device_id, event_time,
                  error_category, correlation_id, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, e.eventId(), m, messageId, conv, service, device, ts(at), error, e.correlationId(), e.traceId());
    }

    private void bump(Instant at, String column, long n) {
        jdbc.update("INSERT INTO message_metrics_hourly (bucket_hour, " + column + ") VALUES (?, ?) "
                + "ON CONFLICT (bucket_hour) DO UPDATE SET " + column + " = message_metrics_hourly." + column + " + EXCLUDED." + column,
                ts(at.truncatedTo(ChronoUnit.HOURS)), n);
    }

    private void refreshCounts(UUID messageId) {
        jdbc.update("""
                INSERT INTO message_delivery_summary (message_id) VALUES (?) ON CONFLICT DO NOTHING
                """, messageId);
        jdbc.update("""
                UPDATE message_delivery_summary s SET
                  delivered_devices = (SELECT count(*) FROM message_device_delivery d WHERE d.message_id = s.message_id AND d.status IN ('DELIVERED','READ')),
                  read_devices = (SELECT count(*) FROM message_device_delivery d WHERE d.message_id = s.message_id AND d.status = 'READ'),
                  pending_devices = (SELECT count(*) FROM message_device_delivery d WHERE d.message_id = s.message_id AND d.status = 'PENDING'),
                  failed_devices = (SELECT count(*) FROM message_device_delivery d WHERE d.message_id = s.message_id AND d.status = 'FAILED'),
                  updated_at = now()
                WHERE s.message_id = ?
                """, messageId);
    }

    @Transactional
    public void accepted(EventEnvelope e, Instant publishedAt) {
        if (!processed.markIfNew(e.eventId(), e.eventType())) {
            return;
        }
        JsonNode p = e.payload();
        UUID mid = uuid(p.get("messageId"));
        UUID conv = uuid(p.get("conversationId"));
        Instant accepted = instant(p.get("createdAt"));
        milestone(e, "RECEIVED", mid, conv, "message-service", null, instant(p.get("receivedAt")), null);
        milestone(e, "PERSISTED", mid, conv, "message-service", null, instant(p.get("persistedAt")), null);
        milestone(e, "ACCEPTED", mid, conv, "message-service", null, accepted, null);
        milestone(e, "PUBLISHED", mid, conv, "outbox-publisher", null, publishedAt, null);
        jdbc.update("""
                INSERT INTO message_delivery_summary (message_id, conversation_id, conversation_type, protocol, sender_user_id, sender_device_id,
                  accepted_at, published_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (message_id) DO UPDATE SET conversation_id = EXCLUDED.conversation_id, conversation_type = EXCLUDED.conversation_type,
                  protocol = EXCLUDED.protocol, sender_user_id = EXCLUDED.sender_user_id, sender_device_id = EXCLUDED.sender_device_id,
                  accepted_at = EXCLUDED.accepted_at, published_at = EXCLUDED.published_at, correlation_id = EXCLUDED.correlation_id, updated_at = now()
                """, mid, conv, p.path("conversationType").asString(null), p.path("protocol").asString(null), uuid(p.get("senderUserId")),
                uuid(p.get("senderDeviceId")), ts(accepted), ts(publishedAt), e.correlationId());
        bump(accepted, "accepted_count", 1);
    }

    @Transactional
    public void fanoutCompleted(EventEnvelope e) {
        if (!processed.markIfNew(e.eventId(), e.eventType())) {
            return;
        }
        JsonNode p = e.payload();
        UUID mid = uuid(p.get("messageId"));
        Instant at = instant(p.get("completedAt"));
        milestone(e, "FANOUT_COMPLETED", mid, uuid(p.get("conversationId")), "fanout-service", null, at, null);
        jdbc.update("INSERT INTO message_delivery_summary (message_id, conversation_id) VALUES (?, ?) ON CONFLICT DO NOTHING", mid,
                uuid(p.get("conversationId")));
        jdbc.update("UPDATE message_delivery_summary SET expected_devices = ?, fanout_at = ? WHERE message_id = ?",
                p.path("expectedDevices").asInt(), ts(at), mid);
        for (JsonNode d : p.path("deviceIds")) {
            jdbc.update("INSERT INTO message_device_delivery (message_id, device_id, status, pending_at) VALUES (?, ?, 'PENDING', ?) "
                    + "ON CONFLICT DO NOTHING", mid, uuid(d), ts(at));
        }
        refreshCounts(mid);
    }

    @Transactional
    public void deliveryRequested(EventEnvelope e) {
        if (!processed.markIfNew(e.eventId(), e.eventType())) {
            return;
        }
        JsonNode p = e.payload();
        UUID mid = uuid(p.get("messageId"));
        UUID dev = uuid(p.get("recipientDeviceId"));
        milestone(e, "DELIVERY_PENDING", mid, uuid(p.get("conversationId")), "delivery-service", dev, e.timestamp(), null);
        jdbc.update("""
                INSERT INTO message_device_delivery (message_id, device_id, user_id, status, pending_at) VALUES (?, ?, ?, 'PENDING', ?)
                ON CONFLICT (message_id, device_id) DO UPDATE SET user_id = EXCLUDED.user_id,
                  pending_at = COALESCE(message_device_delivery.pending_at, EXCLUDED.pending_at)
                """, mid, dev, uuid(p.get("recipientUserId")), ts(e.timestamp()));
        refreshCounts(mid);
    }

    /** DELIVERED / READ receipts. */
    @Transactional
    public void receipt(EventEnvelope e, String status) {
        if (!processed.markIfNew(e.eventId(), e.eventType())) {
            return;
        }
        JsonNode p = e.payload();
        UUID mid = uuid(p.get("messageId"));
        UUID dev = uuid(p.get("recipientDeviceId"));
        Instant at = instant(p.get("at"));
        milestone(e, status, mid, uuid(p.get("conversationId")), "delivery-service", dev, at, null);
        jdbc.update("INSERT INTO message_device_delivery (message_id, device_id, user_id, status) VALUES (?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING",
                mid, dev, uuid(p.get("recipientUserId")));
        int changed;
        if ("DELIVERED".equals(status)) {
            changed = jdbc.update("UPDATE message_device_delivery SET status = 'DELIVERED', delivered_at = ? WHERE message_id = ? AND device_id = ? "
                    + "AND status = 'PENDING'", ts(at), mid, dev);
            if (changed == 1) {
                bump(at, "delivered_device_count", 1);
                Instant accepted = jdbc.query("SELECT accepted_at FROM message_delivery_summary WHERE message_id = ?",
                        (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), mid).stream().findFirst().orElse(null);
                if (accepted != null) {
                    long ms = Math.max(0, at.toEpochMilli() - accepted.toEpochMilli());
                    jdbc.update("""
                            INSERT INTO message_metrics_hourly (bucket_hour, latency_sum_ms, latency_count, latency_max_ms) VALUES (?, ?, 1, ?)
                            ON CONFLICT (bucket_hour) DO UPDATE SET latency_sum_ms = message_metrics_hourly.latency_sum_ms + EXCLUDED.latency_sum_ms,
                              latency_count = message_metrics_hourly.latency_count + 1,
                              latency_max_ms = GREATEST(message_metrics_hourly.latency_max_ms, EXCLUDED.latency_max_ms)
                            """, ts(at.truncatedTo(ChronoUnit.HOURS)), ms, ms);
                }
            }
        } else {
            changed = jdbc.update("UPDATE message_device_delivery SET status = 'READ', read_at = ?, delivered_at = COALESCE(delivered_at, ?) "
                    + "WHERE message_id = ? AND device_id = ? AND status IN ('PENDING', 'DELIVERED')", ts(at), ts(at), mid, dev);
            if (changed == 1) {
                bump(at, "read_device_count", 1);
            }
        }
        refreshCounts(mid);
    }

    @Transactional
    public void failed(EventEnvelope e) {
        if (!processed.markIfNew(e.eventId(), e.eventType())) {
            return;
        }
        JsonNode p = e.payload();
        UUID mid = uuid(p.get("messageId"));
        UUID dev = uuid(p.get("recipientDeviceId"));
        Instant at = instant(p.get("at"));
        String reason = p.path("reason").asString("UNKNOWN");
        milestone(e, "FAILED", mid, uuid(p.get("conversationId")), "delivery-service", dev, at, reason);
        jdbc.update("INSERT INTO message_device_delivery (message_id, device_id, user_id, status) VALUES (?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING",
                mid, dev, uuid(p.get("recipientUserId")));
        if (jdbc.update("UPDATE message_device_delivery SET status = 'FAILED', failed_at = ?, failure_reason = ? WHERE message_id = ? "
                + "AND device_id = ? AND status = 'PENDING'", ts(at), reason, mid, dev) == 1) {
            bump(at, "failed_device_count", 1);
        }
        refreshCounts(mid);
    }
}
