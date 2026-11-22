package com.securechat.fanout;

import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventEnvelope;
import com.securechat.contracts.EventType;
import com.securechat.contracts.Topics;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.Envelope;
import com.securechat.contracts.events.FanoutCompleted;
import com.securechat.contracts.events.MessageAccepted;
import com.securechat.support.kafka.EventPublisher;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Turns one accepted message into one deterministic delivery task per eligible recipient device:
 * <ul>
 *   <li>recipients = CURRENT members (authoritative or version-checked cache) × their ACTIVE devices;</li>
 *   <li>the originating device never receives its own message; the sender's other devices do (echo);</li>
 *   <li>SIGNAL: only devices the sender encrypted for (others are reported as NO_ENVELOPE);</li>
 *   <li>MLS: the single group ciphertext goes to every eligible device;</li>
 *   <li>{@code deliveryId = UUIDv5(messageId, deviceId)} so duplicates collapse downstream.</li>
 * </ul>
 * All sends are awaited before the Kafka offset is committed; any failure retries the record.
 */
@Service
public class FanoutService {
    private static final Logger log = LoggerFactory.getLogger(FanoutService.class);
    private final Directory directory;
    private final EventPublisher publisher;
    private final MeterRegistry meters;
    private final Duration doneTtl;
    private final Timer latency;
    private final DistributionSummary recipients;

    FanoutService(Directory directory, EventPublisher publisher, MeterRegistry meters,
            @Value("${securechat.fanout.done-marker-ttl:24h}") Duration doneTtl) {
        this.directory = directory;
        this.publisher = publisher;
        this.meters = meters;
        this.doneTtl = doneTtl;
        this.latency = Timer.builder("fanout.latency").description("accepted event → delivery tasks published").register(meters);
        this.recipients = DistributionSummary.builder("fanout.recipient.devices").register(meters);
    }

    public void fanout(EventEnvelope env) {
        MessageAccepted m = EventCodec.payload(env, MessageAccepted.class);
        if (directory.alreadyFannedOut(m.messageId())) {
            meters.counter("fanout.duplicates.skipped").increment();
            return;
        }
        Timer.Sample sample = Timer.start(meters);
        Directory.Members members = directory.members(m.conversationId(), m.membershipVersion());
        Map<UUID, List<UUID>> devices = directory.activeDevices(members.userIds());
        Map<UUID, UUID> owner = new HashMap<>();
        devices.forEach((u, ds) -> ds.forEach(d -> owner.put(d, u)));
        boolean signal = "SIGNAL".equals(m.protocol());
        Map<UUID, Envelope> envelopes = new HashMap<>();
        if (signal && m.envelopes() != null) {
            m.envelopes().forEach(e -> envelopes.put(e.deviceId(), e));
        }
        List<UUID> targets = new ArrayList<>();
        List<FanoutCompleted.SkippedDevice> skipped = new ArrayList<>();
        Set<UUID> candidates = new HashSet<>(owner.keySet());
        if (signal) {
            for (UUID d : envelopes.keySet()) {
                if (!owner.containsKey(d)) {
                    skipped.add(new FanoutCompleted.SkippedDevice(d, "DEVICE_INACTIVE"));   // revoked or no longer a member
                }
            }
        }
        for (UUID d : candidates) {
            if (d.equals(m.senderDeviceId())) {
                continue;
            }
            if (signal && !envelopes.containsKey(d)) {
                skipped.add(new FanoutCompleted.SkippedDevice(d, "NO_ENVELOPE"));
                continue;
            }
            targets.add(d);
        }
        List<CompletableFuture<?>> sends = new ArrayList<>(targets.size());
        for (UUID d : targets) {
            UUID deliveryId = DeterministicIds.deliveryId(m.messageId(), d);
            Envelope e = envelopes.get(d);
            DeliveryRequested task = new DeliveryRequested(deliveryId, m.messageId(), m.conversationId(), m.senderUserId(),
                    m.senderDeviceId(), owner.get(d), d, m.protocol(), m.messageType(), e == null ? null : e.signalType(), m.epoch(),
                    e == null ? m.ciphertext() : e.ciphertext(), m.createdAt());
            String json = EventCodec.toJson(EventCodec.envelope(EventType.MESSAGE_DELIVERY_REQUESTED,
                    DeterministicIds.deliveryRequestedEventId(deliveryId), deliveryId.toString(), task, env.correlationId(), null,
                    publisher.producer()));
            sends.add(publisher.sendAsync(Topics.MESSAGE_DELIVERY_REQUESTED, d.toString(), json, env.correlationId()));
        }
        // backpressure + correctness: every task must be acknowledged before the offset moves on
        EventPublisher.await(CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)), Duration.ofSeconds(30),
                Topics.MESSAGE_DELIVERY_REQUESTED);
        FanoutCompleted done = new FanoutCompleted(m.messageId(), m.conversationId(), targets.size(), targets, skipped, Instant.now());
        publisher.sendRaw(Topics.MESSAGE_FANOUT_COMPLETED, m.conversationId().toString(), EventCodec.toJson(EventCodec.envelope(
                EventType.MESSAGE_FANOUT_COMPLETED, DeterministicIds.fanoutCompletedEventId(m.messageId()), m.messageId().toString(),
                done, env.correlationId(), null, publisher.producer())), env.correlationId());
        directory.markFannedOut(m.messageId(), doneTtl);
        sample.stop(latency);
        recipients.record(targets.size());
        log.info("fanout messageId={} conversationId={} devices={} skipped={}", m.messageId(), m.conversationId(), targets.size(),
                skipped.size());
    }
}
