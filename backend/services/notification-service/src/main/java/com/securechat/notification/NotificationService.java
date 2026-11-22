package com.securechat.notification;

import com.securechat.contracts.EventCodec;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.NotificationRequested;
import com.securechat.support.kafka.Consumed;
import com.securechat.support.kafka.RetryableDependencyException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Sends at most one generic push per device per interval and never the same message twice
 * (bounded in-memory windows; a restart may at worst send one extra generic push). Provider
 * failures are retried with backoff by the shared consumer error policy; they never affect
 * message persistence or delivery.
 */
@Service
public class NotificationService {
    private final PushProvider provider;
    private final MeterRegistry meters;
    private final long minIntervalMs;
    private final long dedupeMs;
    private final Map<UUID, Long> lastPushPerDevice = lru(50_000);
    private final Map<String, Long> seen = lru(200_000);

    NotificationService(PushProvider provider, MeterRegistry meters,
            @Value("${securechat.notification.min-interval-per-device:30s}") Duration minInterval,
            @Value("${securechat.notification.dedupe-window:10m}") Duration dedupe) {
        this.provider = provider;
        this.meters = meters;
        this.minIntervalMs = minInterval.toMillis();
        this.dedupeMs = dedupe.toMillis();
    }

    private static <K> Map<K, Long> lru(int max) {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Long> e) {
                return size() > max;
            }
        });
    }

    @KafkaListener(topics = Topics.NOTIFICATION_REQUESTED, groupId = "notification-service")
    void onRequested(String json) {
        handle(EventCodec.payload(Consumed.parse(json), NotificationRequested.class));
    }

    /** @return true if a push was sent */
    public boolean handle(NotificationRequested r) {
        long now = System.currentTimeMillis();
        String key = r.messageId() + ":" + r.recipientDeviceId();
        Long s = seen.get(key);
        if (s != null && now - s < dedupeMs) {
            meters.counter("notification.skipped", "reason", "duplicate").increment();
            return false;
        }
        Long last = lastPushPerDevice.get(r.recipientDeviceId());
        if (last != null && now - last < minIntervalMs) {
            seen.put(key, now);
            meters.counter("notification.skipped", "reason", "rate_limited").increment();
            return false;
        }
        try {
            provider.send(new PushProvider.Push(r.recipientUserId(), r.recipientDeviceId(), PushProvider.GENERIC_TITLE,
                    PushProvider.GENERIC_BODY));
        } catch (Exception e) {
            meters.counter("notification.provider.failures").increment();
            throw new RetryableDependencyException("push provider unavailable", e);
        }
        seen.put(key, now);
        lastPushPerDevice.put(r.recipientDeviceId(), now);
        meters.counter("notification.sent").increment();
        return true;
    }
}
