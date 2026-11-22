package com.securechat.message;

import com.datastax.oss.driver.api.core.DriverException;
import com.securechat.contracts.Topics;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.support.outbox.OutboxPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cassandra outbox → Kafka (at-least-once).
 * <ol>
 *   <li>Each shard is owned by one instance via an LWT lease (30 s, renewed every poll).</li>
 *   <li>Rows are read from the shard's cursor bucket up to the current hour.</li>
 *   <li>Before publishing, the message row must exist (see §8.1); a missing message past the grace
 *       window is QUARANTINED (alerted, never silently dropped).</li>
 *   <li>Publish with acks=all + idempotent producer and WAIT for the broker ACK.</li>
 *   <li>Write the published marker, then delete the pending row.</li>
 * </ol>
 * Crash after ACK and before the marker → republish (consumers dedupe by event id). Crash after
 * the marker → the next pass sees the marker and only deletes. Failures back off exponentially
 * with jitter per row; later rows of the same conversation wait (per-conversation order).
 */
@Component
public class CassandraOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(CassandraOutboxPublisher.class);
    private final MessageStore store;
    private final EventPublisher publisher;
    private final MessageProperties props;
    private final String owner;
    private final boolean enabled;
    private final Set<Integer> owned = ConcurrentHashMap.newKeySet();
    private final Map<Integer, long[]> shardStats = new ConcurrentHashMap<>();
    private final AtomicLong quarantinedGauge = new AtomicLong();
    private final Counter published;
    private final Counter failed;
    private final Counter quarantined;
    private final Counter duplicateSkips;

    CassandraOutboxPublisher(MessageStore store, EventPublisher publisher, MessageProperties props, MeterRegistry meters,
            @Value("${securechat.messages.outbox-publisher-enabled:true}") boolean enabled) {
        this.store = store;
        this.publisher = publisher;
        this.props = props;
        this.enabled = enabled;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "message-service";
        }
        this.owner = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        Gauge.builder("outbox.pending", this, p -> p.shardStats.values().stream().mapToLong(s -> s[0]).sum())
                .tag("store", "cassandra").register(meters);
        Gauge.builder("outbox.oldest.pending.age", this, p -> p.oldestAgeSeconds()).baseUnit("seconds")
                .tag("store", "cassandra").register(meters);
        Gauge.builder("outbox.quarantined", quarantinedGauge, AtomicLong::get).tag("store", "cassandra").register(meters);
        Gauge.builder("outbox.shards.owned", owned, Set::size).tag("store", "cassandra").register(meters);
        published = Counter.builder("outbox.publish").tag("store", "cassandra").tag("result", "success").register(meters);
        failed = Counter.builder("outbox.publish").tag("store", "cassandra").tag("result", "failure").register(meters);
        quarantined = Counter.builder("outbox.quarantine").tag("store", "cassandra").register(meters);
        duplicateSkips = Counter.builder("outbox.marker.skips").tag("store", "cassandra").register(meters);
    }

    private long oldestAgeSeconds() {
        long now = System.currentTimeMillis();
        return shardStats.values().stream().filter(s -> s[1] > 0).mapToLong(s -> (now - s[1]) / 1000).max().orElse(0);
    }

    public String owner() {
        return owner;
    }

    @Scheduled(fixedDelayString = "${securechat.messages.outbox-poll-interval:300ms}", initialDelay = 1000)
    public void poll() {
        if (!enabled) {
            return;
        }
        long q = 0;
        for (int shard = 0; shard < props.outboxShards(); shard++) {
            try {
                Instant now = Instant.now();
                if (!store.acquire(shard, owner, now.plus(props.outboxLease()), MessageStore.hour(now).minus(Duration.ofHours(1)))) {
                    owned.remove(shard);
                    shardStats.remove(shard);
                    continue;
                }
                owned.add(shard);
                ShardResult r = processShard(shard);
                q += r.quarantined;
                if (r.kafkaDown) {
                    break; // broker unavailable: stop this pass, rows stay pending
                }
            } catch (DriverException e) {
                log.warn("outbox shard {} pass failed: cassandra {}", shard, e.getClass().getSimpleName());
            }
        }
        quarantinedGauge.set(q);
    }

    private record ShardResult(boolean kafkaDown, long quarantined) {
    }

    ShardResult processShard(int shard) {
        MessageStore.Lease lease = store.lease(shard).orElseThrow();
        Instant nowHour = MessageStore.hour(Instant.now());
        Instant cursor = lease.cursor() == null ? nowHour : lease.cursor();
        Instant advanceTo = null;
        boolean prefixEmpty = true;
        long pending = 0;
        long oldest = 0;
        long quarantinedRows = 0;
        Instant now = Instant.now();
        Instant leaseRenewAt = now.plus(props.outboxLease().dividedBy(3));
        boolean kafkaDown = false;
        for (Instant b = cursor; !b.isAfter(nowHour); b = b.plus(Duration.ofHours(1))) {
            List<MessageStore.OutboxRow> rows = store.scanOutbox(b, shard, props.outboxBatchSize());
            if (rows.isEmpty()) {
                // advance the cursor only across a contiguous prefix of empty buckets older than 1h
                if (prefixEmpty && b.isBefore(nowHour.minus(Duration.ofHours(1)))) {
                    advanceTo = b.plus(Duration.ofHours(1));
                }
                continue;
            }
            prefixEmpty = false;
            Set<UUID> blocked = new HashSet<>();
            for (MessageStore.OutboxRow row : rows) {
                if ("QUARANTINED".equals(row.status())) {
                    quarantinedRows++;
                    continue;
                }
                pending++;
                if (oldest == 0 || row.createdAt().toEpochMilli() < oldest) {
                    oldest = row.createdAt().toEpochMilli();
                }
                if (kafkaDown || blocked.contains(row.conversationId())) {
                    continue; // keep counting the backlog for metrics, but publish nothing more
                }
                if (row.nextAttemptAt() != null && row.nextAttemptAt().isAfter(now)) {
                    blocked.add(row.conversationId());
                    continue;
                }
                if (store.isPublished(row)) {
                    // crashed after the marker but before the delete: never republish
                    store.deleteOutbox(row);
                    duplicateSkips.increment();
                    pending--;
                    continue;
                }
                if (!store.messageExists(row.conversationId(), row.messageBucket(), row.messageCreatedAt(), row.messageId())) {
                    if (row.createdAt().plus(props.outboxQuarantineGrace()).isBefore(now)) {
                        store.updateOutbox(row, "QUARANTINED", row.attempts(), null, "message row missing");
                        quarantined.increment();
                        log.error("outbox event quarantined: message row missing messageId={} eventId={}", row.messageId(), row.eventId());
                    }
                    blocked.add(row.conversationId());
                    continue;
                }
                try {
                    publisher.sendRaw(Topics.MESSAGE_ACCEPTED, row.conversationId().toString(),
                            new String(row.payload(), StandardCharsets.UTF_8), null);
                } catch (EventPublisher.PublishFailedException e) {
                    int attempts = row.attempts() + 1;
                    store.updateOutbox(row, attempts >= props.outboxStuckAfter() ? "STUCK" : "PENDING", attempts,
                            Instant.now().plus(OutboxPublisher.backoff(attempts)), e.getMessage());
                    failed.increment();
                    log.warn("outbox publish failed messageId={} attempt={}", row.messageId(), attempts);
                    kafkaDown = true;
                    continue;
                }
                store.markPublished(row);
                store.deleteOutbox(row);
                published.increment();
                pending--;
                if (Instant.now().isAfter(leaseRenewAt)) {
                    // long pass: renew so no other instance takes the shard mid-batch; stop if lost
                    if (!store.acquire(shard, owner, Instant.now().plus(props.outboxLease()), cursor)) {
                        owned.remove(shard);
                        record(shard, pending, oldest);
                        return new ShardResult(false, quarantinedRows);
                    }
                    leaseRenewAt = Instant.now().plus(props.outboxLease().dividedBy(3));
                }
            }
        }
        if (advanceTo != null && advanceTo.isAfter(cursor)) {
            store.advanceCursor(shard, owner, advanceTo);
        }
        record(shard, pending, oldest);
        return new ShardResult(kafkaDown, quarantinedRows);
    }

    private void record(int shard, long pending, long oldest) {
        shardStats.put(shard, new long[] {Math.max(pending, 0), pending > 0 ? oldest : 0});
    }

    public record ShardStatus(int shard, String owner, Instant leaseUntil, Instant cursor, long pendingRows, long quarantinedRows,
            Instant oldestPending) {
    }

    /** Operational view used by the recovery runbook (internal endpoint). */
    public List<ShardStatus> status() {
        List<ShardStatus> out = new ArrayList<>();
        Instant nowHour = MessageStore.hour(Instant.now());
        for (int s = 0; s < props.outboxShards(); s++) {
            var lease = store.lease(s).orElse(null);
            Instant cursor = lease == null || lease.cursor() == null ? nowHour : lease.cursor();
            long p = 0;
            long q = 0;
            Instant oldest = null;
            for (Instant b = cursor; !b.isAfter(nowHour); b = b.plus(Duration.ofHours(1))) {
                for (var row : store.scanOutbox(b, s, 5000)) {
                    if ("QUARANTINED".equals(row.status())) {
                        q++;
                    } else {
                        p++;
                    }
                    if (oldest == null || row.createdAt().isBefore(oldest)) {
                        oldest = row.createdAt();
                    }
                }
            }
            out.add(new ShardStatus(s, lease == null ? null : lease.owner(), lease == null ? null : lease.until(), cursor, p, q, oldest));
        }
        return out;
    }

    /** Recovery: move QUARANTINED rows back to PENDING (e.g. after a manual fix). */
    public int requeueQuarantined() {
        int n = 0;
        Instant nowHour = MessageStore.hour(Instant.now());
        for (int s = 0; s < props.outboxShards(); s++) {
            var lease = store.lease(s).orElse(null);
            Instant cursor = lease == null || lease.cursor() == null ? nowHour : lease.cursor();
            for (Instant b = cursor; !b.isAfter(nowHour); b = b.plus(Duration.ofHours(1))) {
                for (var row : store.scanOutbox(b, s, 5000)) {
                    if ("QUARANTINED".equals(row.status())) {
                        store.updateOutbox(row, "PENDING", 0, Instant.now(), null);
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @PreDestroy
    void releaseLeases() {
        for (int s : owned) {
            try {
                store.release(s, owner);
            } catch (RuntimeException e) {
                log.debug("lease release failed shard={}", s);
            }
        }
    }
}
