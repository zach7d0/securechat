package com.securechat.monitoring;

import com.securechat.support.web.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read API for the Admin Service (internal only). Returns metadata; there is no content to return. */
@RestController
@RequestMapping("/internal/v1/monitoring")
class ReportController {
    private final JdbcTemplate jdbc;
    private final Duration retention;

    ReportController(JdbcTemplate jdbc, @Value("${securechat.monitoring.retention:30d}") Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @GetMapping("/messages/{messageId}")
    Map<String, Object> trace(@PathVariable UUID messageId) {
        List<Map<String, Object>> summary = jdbc.queryForList("SELECT * FROM message_delivery_summary WHERE message_id = ?", messageId);
        if (summary.isEmpty()) {
            throw ApiException.notFound("message");
        }
        Map<String, Object> out = new LinkedHashMap<>(summary.get(0));
        out.put("milestones", jdbc.queryForList("""
                SELECT milestone, service, device_id, event_time, error_category, correlation_id, trace_id
                FROM message_lifecycle_events WHERE message_id = ? ORDER BY event_time, milestone
                """, messageId));
        out.put("devices", jdbc.queryForList("""
                SELECT device_id, user_id, status, pending_at, delivered_at, read_at, failed_at, failure_reason
                FROM message_device_delivery WHERE message_id = ? ORDER BY device_id
                """, messageId));
        return out;
    }

    @GetMapping("/messages")
    List<Map<String, Object>> search(@RequestParam(required = false) UUID conversationId, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "50") int limit) {
        Instant f = from == null ? Instant.now().minus(Duration.ofDays(1)) : from;
        Instant t = to == null ? Instant.now().plusSeconds(60) : to;
        int n = Math.min(Math.max(limit, 1), 200);
        if (conversationId != null) {
            return jdbc.queryForList("SELECT * FROM message_delivery_summary WHERE conversation_id = ? AND accepted_at BETWEEN ? AND ? "
                    + "ORDER BY accepted_at DESC LIMIT ?", conversationId, Timestamp.from(f), Timestamp.from(t), n);
        }
        return jdbc.queryForList("SELECT * FROM message_delivery_summary WHERE accepted_at BETWEEN ? AND ? ORDER BY accepted_at DESC LIMIT ?",
                Timestamp.from(f), Timestamp.from(t), n);
    }

    @GetMapping("/reports/hourly")
    List<Map<String, Object>> hourly(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant f = from == null ? Instant.now().minus(Duration.ofHours(24)) : from;
        Instant t = to == null ? Instant.now() : to;
        return jdbc.queryForList("""
                SELECT bucket_hour, accepted_count, delivered_device_count, read_device_count, failed_device_count,
                  CASE WHEN latency_count > 0 THEN latency_sum_ms / latency_count END AS avg_delivery_latency_ms, latency_max_ms
                FROM message_metrics_hourly WHERE bucket_hour BETWEEN date_trunc('hour', ?::timestamptz) AND ? ORDER BY bucket_hour
                """, Timestamp.from(f), Timestamp.from(t));
    }

    @GetMapping("/reports/summary")
    Map<String, Object> summary(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        Instant f = from == null ? Instant.now().minus(Duration.ofHours(24)) : from;
        Instant t = to == null ? Instant.now() : to;
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT coalesce(sum(accepted_count),0) AS accepted, coalesce(sum(delivered_device_count),0) AS delivered_devices,
                  coalesce(sum(read_device_count),0) AS read_devices, coalesce(sum(failed_device_count),0) AS failed_devices,
                  CASE WHEN sum(latency_count) > 0 THEN sum(latency_sum_ms) / sum(latency_count) END AS avg_delivery_latency_ms,
                  max(latency_max_ms) AS max_delivery_latency_ms
                FROM message_metrics_hourly WHERE bucket_hour BETWEEN date_trunc('hour', ?::timestamptz) AND ?
                """, Timestamp.from(f), Timestamp.from(t)));
        out.put("pending_devices", jdbc.queryForObject("SELECT count(*) FROM message_device_delivery WHERE status = 'PENDING'", Long.class));
        out.put("failure_categories", jdbc.queryForList("""
                SELECT failure_reason AS category, count(*) AS count FROM message_device_delivery
                WHERE status = 'FAILED' AND failed_at BETWEEN ? AND ? GROUP BY failure_reason ORDER BY 2 DESC
                """, Timestamp.from(f), Timestamp.from(t)));
        return out;
    }

    /** Bounded retention of operational records. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purge() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retention));
        jdbc.update("DELETE FROM message_lifecycle_events WHERE event_time < ?", cutoff);
        jdbc.update("DELETE FROM message_device_delivery d USING message_delivery_summary s WHERE d.message_id = s.message_id AND s.accepted_at < ?", cutoff);
        jdbc.update("DELETE FROM message_delivery_summary WHERE accepted_at < ?", cutoff);
        jdbc.update("DELETE FROM processed_events WHERE processed_at < ?", cutoff);
    }
}
