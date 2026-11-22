package com.securechat.message;

import com.datastax.oss.driver.api.core.DriverException;
import com.securechat.contracts.DeterministicIds;
import com.securechat.contracts.EventCodec;
import com.securechat.contracts.EventType;
import com.securechat.contracts.events.Envelope;
import com.securechat.contracts.events.MessageAccepted;
import com.securechat.support.ids.UuidV7;
import com.securechat.support.web.ApiException;
import com.securechat.support.web.Correlation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

/**
 * Accepts encrypted messages. The service never decrypts; it validates the transport envelope,
 * authorizes the sender (fail closed), deduplicates by (sender device, clientMessageId) and
 * persists in this order — idempotency LWT → outbox row → message row — returning ACCEPTED only
 * after both durable writes succeeded (see docs/SECURECHAT_ARCHITECTURE.md §8 and docs/OUTBOX_RUNBOOK.md).
 */
@Service
public class MessageService {
    private static final Logger log = LoggerFactory.getLogger(MessageService.class);
    private final MessageStore store;
    private final Dependencies deps;
    private final MessageProperties props;
    private final MeterRegistry meters;
    private final Timer persistTimer;

    MessageService(MessageStore store, Dependencies deps, MessageProperties props, MeterRegistry meters) {
        this.store = store;
        this.deps = deps;
        this.props = props;
        this.meters = meters;
        this.persistTimer = Timer.builder("message.persist").description("durable message + outbox write").register(meters);
    }

    public record SubmitRequest(UUID conversationId, UUID clientMessageId, String protocol, String messageType,
            List<Envelope> envelopes, String ciphertext, Long epoch, Integer expiresInSeconds) {
        public SubmitRequest(UUID conversationId, UUID clientMessageId, String protocol, String messageType,
                List<Envelope> envelopes, String ciphertext, Long epoch) {
            this(conversationId, clientMessageId, protocol, messageType, envelopes, ciphertext, epoch, null);
        }
    }

    /** Disappearing-message timers a chat can use (seconds): 5/15 min, 1/3/10 h, 1/3/7/30 days. */
    public static final java.util.Set<Integer> TIMERS = java.util.Set.of(300, 900, 3600, 10800, 36000, 86400, 259200, 604800, 2592000);
    /** "Delete for everyone" is allowed for this long after sending. */
    static final java.time.Duration DELETE_WINDOW = java.time.Duration.ofHours(24);

    public record Accepted(UUID messageId, UUID conversationId, UUID clientMessageId, Instant createdAt, String status,
            boolean duplicate) {
    }

    public Accepted submit(UUID senderUser, UUID senderDevice, SubmitRequest r) {
        try {
            Accepted a = doSubmit(senderUser, senderDevice, r);
            meters.counter("message.accepted", "protocol", r.protocol(), "duplicate", String.valueOf(a.duplicate())).increment();
            return a;
        } catch (ApiException e) {
            meters.counter("message.rejected", "code", e.code()).increment();
            throw e;
        }
    }

    private Accepted doSubmit(UUID senderUser, UUID senderDevice, SubmitRequest r) {
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (r.conversationId() == null || r.clientMessageId() == null || r.protocol() == null) {
            throw ApiException.badRequest("conversationId, clientMessageId and protocol are required");
        }
        if (r.expiresInSeconds() != null && !TIMERS.contains(r.expiresInSeconds())) {
            throw ApiException.badRequest("expiresInSeconds must be one of " + new java.util.TreeSet<>(TIMERS));
        }
        boolean signal = "SIGNAL".equals(r.protocol());
        if (!signal && !"MLS".equals(r.protocol())) {
            throw ApiException.badRequest("protocol must be SIGNAL or MLS");
        }
        byte[] blob;
        String messageType;
        Set<UUID> envelopeDevices = new HashSet<>();
        if (signal) {
            if (r.envelopes() == null || r.envelopes().isEmpty() || r.envelopes().size() > props.maxEnvelopes() || r.ciphertext() != null) {
                throw ApiException.badRequest("SIGNAL messages carry 1.." + props.maxEnvelopes() + " per-device envelopes and no group ciphertext");
            }
            boolean prekey = false;
            for (Envelope e : r.envelopes()) {
                if (e.deviceId() == null || !envelopeDevices.add(e.deviceId()) || e.deviceId().equals(senderDevice)) {
                    throw ApiException.badRequest("envelope device ids must be unique and must not include the sending device");
                }
                if (e.signalType() != 1 && e.signalType() != 3) {
                    throw ApiException.badRequest("signalType must be 1 (whisper) or 3 (prekey)");
                }
                checkCiphertext(e.ciphertext());
                prekey |= e.signalType() == 3;
            }
            messageType = prekey ? "SIGNAL_PREKEY" : "SIGNAL_WHISPER";
            blob = EventCodec.mapper().writeValueAsBytes(r.envelopes());
        } else {
            if (r.ciphertext() == null || r.envelopes() != null && !r.envelopes().isEmpty() || r.epoch() == null) {
                throw ApiException.badRequest("MLS messages carry one group ciphertext and its epoch");
            }
            blob = checkCiphertext(r.ciphertext());
            messageType = "MLS_APPLICATION";
        }

        // Sender device must be the caller's and ACTIVE (fail closed).
        Dependencies.DeviceStatus dev = deps.device(senderDevice);
        if (dev == null || !senderUser.equals(dev.userId())) {
            throw ApiException.forbidden("unknown sending device");
        }
        if (!"ACTIVE".equals(dev.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_REVOKED", "sending device is revoked");
        }
        // Current membership from the owning service — never a client-supplied claim (fail closed).
        Dependencies.Authorization auth = deps.authorize(r.conversationId(), senderUser);
        if (!auth.member()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER", "not a member of this conversation");
        }
        if (signal != "DIRECT".equals(auth.type())) {
            throw ApiException.badRequest(signal ? "SIGNAL is for direct conversations" : "MLS is for group conversations");
        }
        if (!signal && !r.epoch().equals(auth.epoch())) {
            throw new ApiException(HttpStatus.CONFLICT, "EPOCH_MISMATCH", "group is at epoch " + auth.epoch(),
                    Map.of("currentEpoch", auth.epoch()));
        }
        if (signal) {
            Map<UUID, List<UUID>> ready = deps.readyDevices(auth.memberUserIds());
            if (ready != null) {
                List<UUID> missing = new ArrayList<>();
                ready.values().forEach(ds -> ds.stream().filter(d -> !d.equals(senderDevice) && !envelopeDevices.contains(d))
                        .forEach(missing::add));
                if (!missing.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "DEVICE_LIST_STALE", "recipient device list changed",
                            Map.of("missingDeviceIds", missing));
                }
            }
        }

        Timer.Sample sample = Timer.start(meters);
        try {
            UUID candidateId = UuidV7.at(receivedAt.toEpochMilli());
            MessageStore.Claim claim = store.claimIdempotency(senderDevice, r.clientMessageId(), candidateId, r.conversationId(), receivedAt);
            if (!claim.fresh()) {
                if (!claim.conversationId().equals(r.conversationId())) {
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "clientMessageId was already used for another conversation");
                }
                if (store.messageExists(r.conversationId(), claim.bucketDate(), claim.createdAt(), claim.messageId())) {
                    log.info("duplicate submission resolved messageId={} conversationId={}", claim.messageId(), r.conversationId());
                    return new Accepted(claim.messageId(), r.conversationId(), r.clientMessageId(), claim.createdAt(), "ACCEPTED", true);
                }
                // A previous attempt claimed the id but did not finish: complete it with the ORIGINAL id/timestamp.
            }
            UUID messageId = claim.messageId();
            Instant createdAt = claim.createdAt();
            LocalDate bucket = claim.bucketDate();
            Instant persistedAt = Instant.now();
            MessageAccepted event = new MessageAccepted(messageId, r.conversationId(), auth.type(), senderUser, senderDevice,
                    r.clientMessageId(), r.protocol(), messageType, signal ? null : r.epoch(), auth.membershipVersion(), createdAt,
                    receivedAt, persistedAt, signal ? r.envelopes() : null, signal ? null : r.ciphertext());
            UUID eventId = DeterministicIds.acceptedEventId(messageId);
            String json = EventCodec.toJson(EventCodec.envelope(EventType.MESSAGE_ACCEPTED, eventId, messageId.toString(), event,
                    Correlation.current(), null, "message-service"));
            // 1) outbox first: no message can exist without its event responsibility
            store.writeOutbox(shard(r.conversationId()), eventId, messageId, r.conversationId(), bucket, createdAt,
                    EventType.MESSAGE_ACCEPTED.wireName(), json.getBytes(StandardCharsets.UTF_8));
            // 2) the ciphertext
            store.writeMessage(new MessageStore.StoredMessage(r.conversationId(), bucket, createdAt, messageId, senderUser, senderDevice,
                    blob, r.protocol(), messageType, r.clientMessageId(), signal ? null : r.epoch(), receivedAt, auth.membershipVersion(),
                    r.expiresInSeconds()));
            sample.stop(persistTimer);
            log.info("message accepted messageId={} conversationId={} protocol={}", messageId, r.conversationId(), r.protocol());
            return new Accepted(messageId, r.conversationId(), r.clientMessageId(), createdAt, "ACCEPTED", false);
        } catch (DriverException e) {
            meters.counter("message.persist.failures", "cause", e.getClass().getSimpleName()).increment();
            log.warn("message not accepted: cassandra {}", e.getClass().getSimpleName());
            throw ApiException.unavailable("STORE_UNAVAILABLE", "message store unavailable; retry with the same clientMessageId");
        }
    }

    private byte[] checkCiphertext(String b64) {
        if (b64 == null || b64.isEmpty() || b64.length() > props.maxCiphertextBytes() * 4 / 3 + 4) {
            throw ApiException.badRequest("ciphertext is required and must be at most " + props.maxCiphertextBytes() + " bytes");
        }
        try {
            byte[] b = Base64.getDecoder().decode(b64);
            if (b.length > props.maxCiphertextBytes()) {
                throw ApiException.badRequest("ciphertext too large");
            }
            return b;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("ciphertext must be base64");
        }
    }

    int shard(UUID conversationId) {
        return Math.floorMod(conversationId.hashCode(), props.outboxShards());
    }

    // ------------------------------------------------------------------ delete for everyone

    /**
     * The original sender removes a message from server history within 24 h ("delete for everyone"). The
     * recipients' devices hide it via an encrypted delete notice; this makes sure new devices and "Load older"
     * never fetch the ciphertext again.
     */
    public void deleteForEveryone(UUID userId, UUID conversationId, UUID messageId, Instant createdAt) {
        if (!deps.authorize(conversationId, userId).member()) {
            throw ApiException.notFound("conversation");
        }
        Instant at = createdAt.truncatedTo(ChronoUnit.MILLIS);
        UUID sender = store.senderOf(conversationId, MessageStore.day(at), at, messageId);
        if (sender == null) {
            throw ApiException.notFound("message");
        }
        if (!sender.equals(userId)) {
            throw ApiException.forbidden("only the sender can delete a message for everyone");
        }
        if (at.isBefore(Instant.now().minus(DELETE_WINDOW))) {
            throw ApiException.conflict("DELETE_WINDOW_EXPIRED", "messages can be deleted for everyone only within 24 hours");
        }
        store.deleteMessage(conversationId, MessageStore.day(at), at, messageId);
        log.info("message deleted for everyone messageId={} conversationId={}", messageId, conversationId);
    }

    // ------------------------------------------------------------------ history

    public record EnvelopeView(int signalType, String ciphertext) {
    }

    public record MessageView(UUID messageId, UUID conversationId, UUID senderUserId, UUID senderDeviceId, UUID clientMessageId,
            Instant createdAt, String protocol, String messageType, Long epoch, String ciphertext, EnvelopeView envelope) {
    }

    public record Page(List<MessageView> messages, String nextCursor) {
    }

    /**
     * Newest-first history. Cursor = (bucket, created_at, message_id) of the last item; each query
     * names its partition and uses a clustering-key slice (no ALLOW FILTERING). Signal messages
     * expose only the envelope addressed to the requesting device.
     */
    public Page history(UUID userId, UUID deviceId, UUID conversationId, String cursor, int limit) {
        if (!deps.authorize(conversationId, userId).member()) {
            throw ApiException.notFound("conversation");
        }
        int n = Math.min(Math.max(limit, 1), props.historyPageMax());
        Cursor c = Cursor.decode(cursor);
        LocalDate from = c == null ? MessageStore.day(Instant.now()) : c.bucket();
        LocalDate oldest = MessageStore.day(Instant.now()).minusDays(props.historyLookbackDays());
        List<MessageView> out = new ArrayList<>();
        boolean first = true;
        MessageStore.StoredMessage last = null;
        try {
            outer:
            while (out.size() < n) {
                List<LocalDate> buckets = store.buckets(conversationId, from, 30);
                if (buckets.isEmpty()) {
                    break;
                }
                for (LocalDate b : buckets) {
                    if (b.isBefore(oldest)) {
                        break outer;
                    }
                    boolean resume = first && c != null && b.equals(c.bucket());
                    List<MessageStore.StoredMessage> rows = store.page(conversationId, b, resume ? c.createdAt() : null,
                            resume ? c.messageId() : null, n - out.size());
                    first = false;
                    for (MessageStore.StoredMessage m : rows) {
                        out.add(view(m, deviceId));
                        last = m;
                    }
                    if (out.size() >= n) {
                        break outer;
                    }
                }
                LocalDate lastBucket = buckets.get(buckets.size() - 1);
                if (buckets.size() < 30) {
                    break;
                }
                from = lastBucket.minusDays(1);
            }
        } catch (DriverException e) {
            throw ApiException.unavailable("STORE_UNAVAILABLE", "message store unavailable");
        }
        String next = out.size() == n && last != null ? new Cursor(last.bucketDate(), last.createdAt(), last.messageId()).encode() : null;
        return new Page(out, next);
    }

    private static MessageView view(MessageStore.StoredMessage m, UUID deviceId) {
        if ("SIGNAL".equals(m.protocol())) {
            List<Envelope> envs = EventCodec.mapper().readValue(m.ciphertext(), new TypeReference<List<Envelope>>() { });
            EnvelopeView mine = envs.stream().filter(e -> e.deviceId().equals(deviceId)).findFirst()
                    .map(e -> new EnvelopeView(e.signalType(), e.ciphertext())).orElse(null);
            return new MessageView(m.messageId(), m.conversationId(), m.senderId(), m.senderDeviceId(), m.clientMessageId(),
                    m.createdAt(), m.protocol(), m.messageType(), null, null, mine);
        }
        return new MessageView(m.messageId(), m.conversationId(), m.senderId(), m.senderDeviceId(), m.clientMessageId(), m.createdAt(),
                m.protocol(), m.messageType(), m.epoch(), Base64.getEncoder().encodeToString(m.ciphertext()), null);
    }

    record Cursor(LocalDate bucket, Instant createdAt, UUID messageId) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (bucket + "|" + createdAt.toEpochMilli() + "|" + messageId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(LocalDate.parse(p[0]), Instant.ofEpochMilli(Long.parseLong(p[1])), UUID.fromString(p[2]));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("invalid cursor");
            }
        }
    }
}
