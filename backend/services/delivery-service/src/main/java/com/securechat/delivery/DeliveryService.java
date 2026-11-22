package com.securechat.delivery;

import com.datastax.oss.driver.api.core.DriverException;
import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventType;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.MessageFailed;
import com.securechat.contracts.events.MessageReceipt;
import com.securechat.contracts.events.NotificationRequested;
import com.securechat.support.kafka.EventPublisher;
import com.securechat.support.kafka.RetryableDependencyException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Per-device delivery workflow. Persist first (receipt LWT + pending row), then route. State moves
 * only forward (PENDING → DELIVERED → READ, or PENDING → FAILED), so duplicate tasks and duplicate
 * acknowledgements are harmless. DELIVERED requires the recipient device's own ACK.
 */
@Service
public class DeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private final DeliveryStore store;
    private final GatewayRouter router;
    private final EventPublisher events;
    private final DeliveryProperties props;
    private final MeterRegistry meters;
    private final String owner;
    private final AtomicLong pendingDevices = new AtomicLong();
    private final AtomicLong pendingRows = new AtomicLong();
    private final AtomicLong oldestPendingAge = new AtomicLong();

    DeliveryService(DeliveryStore store, GatewayRouter router, EventPublisher events, DeliveryProperties props, MeterRegistry meters) {
        this.store = store;
        this.router = router;
        this.events = events;
        this.props = props;
        this.meters = meters;
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "delivery";
        }
        this.owner = host + "-" + UUID.randomUUID().toString().substring(0, 6);
        Gauge.builder("delivery.pending.devices", pendingDevices, AtomicLong::get).register(meters);
        Gauge.builder("delivery.pending.messages", pendingRows, AtomicLong::get).register(meters);
        Gauge.builder("delivery.oldest.pending.age", oldestPendingAge, AtomicLong::get).baseUnit("seconds").register(meters);
    }

    int shard(UUID deviceId) {
        return Math.floorMod(deviceId.hashCode(), props.shards());
    }

    // ------------------------------------------------------------------ intake

    public void accept(DeliveryRequested t) {
        byte[] ct = Base64.getDecoder().decode(t.ciphertext());
        DeliveryStore.Pending p = new DeliveryStore.Pending(t.recipientDeviceId(), DeliveryStore.day(t.createdAt()), t.createdAt(),
                t.messageId(), t.deliveryId(), t.conversationId(), t.senderUserId(), t.senderDeviceId(), t.recipientUserId(), ct,
                t.protocol(), t.messageType(), t.signalType(), t.epoch(), 0, null);
        try {
            Optional<DeliveryStore.Receipt> existing = store.createReceipt(p);
            if (existing.isPresent() && !"PENDING".equals(existing.get().status())) {
                meters.counter("delivery.tasks.duplicate").increment();
                return;      // already delivered/read/failed: replayed task is a no-op
            }
            store.putPending(p, shard(p.deviceId()));          // durable BEFORE any network send
        } catch (DriverException e) {
            throw new RetryableDependencyException("cassandra unavailable", e);
        }
        attempt(p, true);
    }

    /** Routes to the owning gateway if online; otherwise requests a generic push (first attempt only). */
    boolean attempt(DeliveryStore.Pending p, boolean first) {
        String gw = router.owner(p.deviceId());
        if (gw == null) {
            meters.counter("delivery.route", "result", "offline").increment();
            if (first) {
                try {
                    events.publish(EventType.NOTIFICATION_REQUESTED, UUID.randomUUID(), p.deviceId().toString(), p.messageId().toString(),
                            new NotificationRequested(p.recipientUserId(), p.deviceId(), p.messageId(), Instant.now()));
                } catch (RuntimeException e) {
                    log.debug("notification request not published");
                }
            }
            return false;
        }
        boolean sent = router.route(gw, p);
        meters.counter("delivery.route", "result", sent ? "routed" : "no_subscriber").increment();
        try {
            store.recordAttempt(p, p.attempts() + 1);
        } catch (DriverException e) {
            log.debug("attempt bookkeeping failed");
        }
        return sent;
    }

    // ------------------------------------------------------------------ acknowledgements

    /**
     * Applies receipts reported by the AUTHENTICATED device for itself. Messages that have no receipt
     * row for this device (not addressed to it) are ignored. Duplicates are no-ops.
     */
    public void acknowledge(UUID userId, UUID deviceId, String status, List<UUID> messageIds) {
        boolean read = "READ".equals(status);
        for (UUID mid : messageIds) {
            Optional<DeliveryStore.Receipt> r;
            try {
                r = store.receipt(mid, deviceId);
            } catch (DriverException e) {
                throw new RetryableDependencyException("cassandra unavailable", e);
            }
            if (r.isEmpty() || !userId.equals(r.get().recipientUserId())) {
                meters.counter("delivery.acks", "result", "ignored").increment();
                continue;
            }
            DeliveryStore.Receipt rc = r.get();
            boolean wasPending = "PENDING".equals(rc.status());
            boolean applied = read ? store.markRead(mid, deviceId) : store.markDelivered(mid, deviceId);
            if (!applied) {
                meters.counter("delivery.acks", "result", "duplicate").increment();
                continue;
            }
            if (wasPending) {
                store.deletePending(deviceId, rc.bucket(), rc.messageCreatedAt(), mid);
            }
            meters.counter("delivery.acks", "result", read ? "read" : "delivered").increment();
            if (wasPending || !read) {
                publishReceipt(EventType.MESSAGE_DELIVERED, rc, "DELIVERED");
            }
            if (read) {
                publishReceipt(EventType.MESSAGE_READ, rc, "READ");
            }
        }
    }

    private void publishReceipt(EventType type, DeliveryStore.Receipt r, String status) {
        events.publish(type, DeterministicIds.receiptEventId(r.messageId(), r.deviceId(), status), r.messageId().toString(),
                r.messageId().toString(), new MessageReceipt(r.messageId(), r.conversationId(), r.senderUserId(), r.recipientUserId(),
                        r.deviceId(), status, Instant.now()));
    }

    // ------------------------------------------------------------------ replay, sweep, revocation

    /** Reconnect: push every pending message of the device in creation order. Idempotent. */
    public int replay(UUID deviceId) {
        int n = 0;
        for (LocalDate b : store.buckets(deviceId)) {
            for (DeliveryStore.Pending p : store.pending(deviceId, b, props.replayLimit())) {
                if (n++ >= props.replayLimit()) {
                    return n;
                }
                attempt(p, false);
            }
        }
        meters.counter("delivery.replays").increment();
        return n;
    }

    @Scheduled(fixedDelayString = "${securechat.delivery.sweep-interval:10s}", initialDelay = 2000)
    public void sweep() {
        long devices = 0;
        long rows = 0;
        long oldest = 0;
        Instant now = Instant.now();
        for (int shard = 0; shard < props.shards(); shard++) {
            try {
                if (!store.lease(shard, owner, now.plus(props.sweeperLease()))) {
                    continue;
                }
                for (DeliveryStore.IndexedDevice d : store.devicesWithPending(shard)) {
                    long left = sweepDevice(shard, d.deviceId(), now);
                    if (left > 0) {
                        devices++;
                        rows += left;
                    }
                }
            } catch (DriverException e) {
                log.warn("delivery sweep shard {} failed: {}", shard, e.getClass().getSimpleName());
            }
        }
        pendingDevices.set(devices);
        pendingRows.set(rows);
        oldestPendingAge.set(oldestSeen == null ? 0 : Duration.between(oldestSeen, now).toSeconds());
        oldestSeen = null;
    }

    private volatile Instant oldestSeen;

    /** @return pending rows left for the device */
    long sweepDevice(int shard, UUID deviceId, Instant now) {
        long left = 0;
        boolean online = router.owner(deviceId) != null;
        for (LocalDate b : store.buckets(deviceId)) {
            List<DeliveryStore.Pending> rows = store.pending(deviceId, b, 1000);
            if (rows.isEmpty()) {
                if (b.isBefore(DeliveryStore.day(now))) {
                    store.deleteBucket(deviceId, b);
                }
                continue;
            }
            for (DeliveryStore.Pending p : rows) {
                if (p.createdAt().plus(props.expiry()).isBefore(now)) {
                    fail(p, "EXPIRED");
                    continue;
                }
                left++;
                if (oldestSeen == null || p.createdAt().isBefore(oldestSeen)) {
                    oldestSeen = p.createdAt();
                }
                Instant due = p.lastAttemptAt() == null ? Instant.EPOCH : p.lastAttemptAt().plus(backoff(p.attempts()));
                if (online && due.isBefore(now)) {
                    meters.counter("delivery.retries").increment();
                    attempt(p, false);
                }
            }
        }
        if (left == 0) {
            store.removeFromIndex(shard, deviceId);
        }
        return left;
    }

    Duration backoff(int attempts) {
        long base = props.retryBase().toMillis() << Math.min(Math.max(attempts - 1, 0), 10);
        long capped = Math.min(base, props.retryMax().toMillis());
        return Duration.ofMillis((long) (capped * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4)));
    }

    private void fail(DeliveryStore.Pending p, String reason) {
        if (store.markFailed(p.messageId(), p.deviceId(), reason)) {
            events.publish(EventType.MESSAGE_FAILED, DeterministicIds.receiptEventId(p.messageId(), p.deviceId(), "FAILED"),
                    p.messageId().toString(), p.messageId().toString(),
                    new MessageFailed(p.messageId(), p.conversationId(), p.recipientUserId(), p.deviceId(), reason, Instant.now()));
            meters.counter("delivery.failed", "reason", reason).increment();
        }
        store.deletePending(p.deviceId(), p.bucket(), p.createdAt(), p.messageId());
    }

    /** Revoked device: its pending work is terminally failed (it will never connect again). */
    public void revokeDevice(UUID deviceId) {
        for (LocalDate b : store.buckets(deviceId)) {
            for (DeliveryStore.Pending p : store.pending(deviceId, b, 5000)) {
                fail(p, "DEVICE_REVOKED");
            }
            store.deleteBucket(deviceId, b);
        }
        store.removeFromIndex(shard(deviceId), deviceId);
    }

    public List<DeliveryStore.Receipt> receipts(UUID messageId) {
        return store.receipts(messageId);
    }

    public long pendingCount() {
        return pendingRows.get();
    }
}
