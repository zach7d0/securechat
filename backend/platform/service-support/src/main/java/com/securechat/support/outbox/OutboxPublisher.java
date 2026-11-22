package com.securechat.support.outbox;

import com.securechat.support.kafka.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polling publisher for the PostgreSQL outbox.
 * <ol>
 *   <li>claim a bounded batch with {@code FOR UPDATE SKIP LOCKED} (safe with several instances);</li>
 *   <li>publish each record and wait for the Kafka ACK;</li>
 *   <li>only then set {@code published_at}.</li>
 * </ol>
 * A failure stops the batch (preserving per-aggregate order) and reschedules the row with
 * exponential backoff + jitter; after {@code stuckAfter} attempts the row is flagged STUCK (still
 * retried; alerted via {@code outbox_stuck}). Crash between ACK and commit → republish; consumers
 * deduplicate by event id. Never claims exactly-once.
 */
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int STUCK_AFTER = 10;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final int batchSize;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, EventPublisher publisher, MeterRegistry meters,
            int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.batchSize = batchSize;
        Gauge.builder("outbox.pending", pending, AtomicLong::get).tag("store", "postgres").register(meters);
        Gauge.builder("outbox.oldest.pending.age", oldestAgeSeconds, AtomicLong::get).baseUnit("seconds")
                .tag("store", "postgres").register(meters);
        Gauge.builder("outbox.stuck", stuck, AtomicLong::get).tag("store", "postgres").register(meters);
        published = Counter.builder("outbox.publish").tag("store", "postgres").tag("result", "success").register(meters);
        failed = Counter.builder("outbox.publish").tag("store", "postgres").tag("result", "failure").register(meters);
    }

    private record Row(UUID id, String topic, String key, String payload, int attempts) {
    }

    @Scheduled(fixedDelayString = "${securechat.outbox.poll-interval:300ms}", initialDelayString = "${securechat.outbox.initial-delay:2s}")
    public void poll() {
        try {
            int n;
            do {
                n = publishBatch();
            } while (n == batchSize);
        } catch (RuntimeException e) {
            log.warn("outbox poll failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of rows published in this batch */
    public int publishBatch() {
        Integer done = tx.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, topic, msg_key, payload, attempts FROM outbox_events
                    WHERE published_at IS NULL AND next_attempt_at <= now()
                    ORDER BY created_at, id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getInt(5)), batchSize);
            int ok = 0;
            for (Row r : rows) {
                try {
                    publisher.sendRaw(r.topic(), r.key(), r.payload(), null);
                } catch (RuntimeException e) {
                    int attempts = r.attempts() + 1;
                    jdbc.update("UPDATE outbox_events SET attempts = ?, next_attempt_at = ?, last_error = ?, status = ? WHERE id = ?",
                            attempts, Timestamp.from(Instant.now().plus(backoff(attempts))), e.getMessage(),
                            attempts >= STUCK_AFTER ? "STUCK" : "PENDING", r.id());
                    failed.increment();
                    log.warn("outbox publish failed id={} attempt={}", r.id(), attempts);
                    break; // keep ordering: later rows wait for this one
                }
                jdbc.update("UPDATE outbox_events SET published_at = now(), status = 'PUBLISHED' WHERE id = ?", r.id());
                published.increment();
                ok++;
            }
            return ok;
        });
        return done == null ? 0 : done;
    }

    @Scheduled(fixedDelayString = "${securechat.outbox.metrics-interval:10s}")
    public void refreshMetrics() {
        try {
            jdbc.query("""
                    SELECT count(*), COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0),
                           count(*) FILTER (WHERE status = 'STUCK') FROM outbox_events WHERE published_at IS NULL
                    """, rs -> {
                pending.set(rs.getLong(1));
                oldestAgeSeconds.set((long) rs.getDouble(2));
                stuck.set(rs.getLong(3));
            });
        } catch (RuntimeException e) {
            log.debug("outbox metrics refresh failed", e);
        }
    }

    /** min(2^attempts s, 5 min) ± 20 % jitter. */
    public static Duration backoff(int attempts) {
        long base = Math.min(300_000L, 1000L << Math.min(attempts, 18));
        double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
        return Duration.ofMillis((long) (base * jitter));
    }
}
