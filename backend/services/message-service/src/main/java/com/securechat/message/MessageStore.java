package com.securechat.message;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.securechat.support.cassandra.CqlMigrator;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * All CQL for the Message Service. Writes use LOCAL_QUORUM; idempotency uses a lightweight
 * transaction (LOCAL_SERIAL). No query uses ALLOW FILTERING; every read names its partition.
 */
@Component
public class MessageStore {
    private static final ConsistencyLevel CL = ConsistencyLevel.LOCAL_QUORUM;
    private final CqlSession session;
    private final PreparedStatement claimIdem;
    private final PreparedStatement insertOutbox;
    private final PreparedStatement insertBucket;
    private final PreparedStatement insertMessage;
    private final PreparedStatement messageExists;
    private final PreparedStatement selectSender;
    private final PreparedStatement deleteMessage;
    /** Table default (365 days) — used when a message has no disappearing timer. */
    static final int DEFAULT_TTL = 31_536_000;
    private final PreparedStatement listBuckets;
    private final PreparedStatement pageFirst;
    private final PreparedStatement pageAfter;
    private final PreparedStatement outboxScan;
    private final PreparedStatement outboxUpdate;
    private final PreparedStatement outboxDelete;
    private final PreparedStatement markerInsert;
    private final PreparedStatement markerGet;
    private final PreparedStatement leaseInsert;
    private final PreparedStatement leaseRenew;
    private final PreparedStatement leaseSteal;
    private final PreparedStatement leaseGet;
    private final PreparedStatement leaseCursor;
    private final PreparedStatement leaseRelease;

    MessageStore(CqlSession session) {
        this.session = session;
        CqlMigrator.migrate(session);
        claimIdem = session.prepare("INSERT INTO message_idempotency (sender_device_id, client_message_id, message_id, conversation_id, "
                + "created_at, bucket_date) VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS");
        insertOutbox = session.prepare("INSERT INTO message_outbox_by_bucket (bucket_hour, shard, created_at, event_id, message_id, "
                + "conversation_id, message_bucket_date, message_created_at, event_type, payload, status, attempt_count, next_attempt_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?)");
        insertBucket = session.prepare("INSERT INTO conversation_buckets (conversation_id, bucket_date) VALUES (?, ?)");
        insertMessage = session.prepare("INSERT INTO messages_by_conversation (conversation_id, bucket_date, created_at, message_id, "
                + "sender_id, sender_device_id, ciphertext, encryption_protocol, message_type, client_message_id, epoch, received_at, "
                + "membership_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) USING TTL ?");
        selectSender = session.prepare("SELECT sender_id FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? "
                + "AND created_at = ? AND message_id = ?");
        deleteMessage = session.prepare("DELETE FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? "
                + "AND created_at = ? AND message_id = ?");
        messageExists = session.prepare("SELECT message_id FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? "
                + "AND created_at = ? AND message_id = ?");
        listBuckets = session.prepare("SELECT bucket_date FROM conversation_buckets WHERE conversation_id = ? AND bucket_date <= ? LIMIT ?");
        pageFirst = session.prepare("SELECT * FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? LIMIT ?");
        pageAfter = session.prepare("SELECT * FROM messages_by_conversation WHERE conversation_id = ? AND bucket_date = ? "
                + "AND (created_at, message_id) < (?, ?) LIMIT ?");
        outboxScan = session.prepare("SELECT * FROM message_outbox_by_bucket WHERE bucket_hour = ? AND shard = ? LIMIT ?");
        outboxUpdate = session.prepare("UPDATE message_outbox_by_bucket SET status = ?, attempt_count = ?, next_attempt_at = ?, last_error = ? "
                + "WHERE bucket_hour = ? AND shard = ? AND created_at = ? AND event_id = ? IF EXISTS");
        outboxDelete = session.prepare("DELETE FROM message_outbox_by_bucket WHERE bucket_hour = ? AND shard = ? AND created_at = ? AND event_id = ?");
        markerInsert = session.prepare("INSERT INTO message_outbox_published (bucket_hour, shard, event_id, published_at) VALUES (?, ?, ?, ?)");
        markerGet = session.prepare("SELECT published_at FROM message_outbox_published WHERE bucket_hour = ? AND shard = ? AND event_id = ?");
        leaseInsert = session.prepare("INSERT INTO outbox_shard_leases (shard, owner, lease_until, cursor_bucket) VALUES (?, ?, ?, ?) IF NOT EXISTS");
        leaseRenew = session.prepare("UPDATE outbox_shard_leases SET lease_until = ? WHERE shard = ? IF owner = ?");
        leaseSteal = session.prepare("UPDATE outbox_shard_leases SET owner = ?, lease_until = ? WHERE shard = ? IF lease_until < ?");
        leaseGet = session.prepare("SELECT owner, lease_until, cursor_bucket FROM outbox_shard_leases WHERE shard = ?");
        leaseCursor = session.prepare("UPDATE outbox_shard_leases SET cursor_bucket = ? WHERE shard = ? IF owner = ?");
        leaseRelease = session.prepare("UPDATE outbox_shard_leases SET lease_until = ? WHERE shard = ? IF owner = ?");
    }

    private ResultSet exec(BoundStatement s) {
        return session.execute(s.setConsistencyLevel(CL).setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL));
    }

    public static LocalDate day(Instant t) {
        return LocalDate.ofInstant(t, ZoneOffset.UTC);
    }

    public static Instant hour(Instant t) {
        return t.truncatedTo(ChronoUnit.HOURS);
    }

    // ------------------------------------------------------------------ idempotency + write path

    public record Claim(boolean fresh, UUID messageId, UUID conversationId, Instant createdAt, LocalDate bucketDate) {
    }

    /** LWT: the first submission wins; a retry resolves to the original message id / timestamp. */
    public Claim claimIdempotency(UUID senderDevice, UUID clientMessageId, UUID messageId, UUID conversationId, Instant createdAt) {
        Row r = exec(claimIdem.bind(senderDevice, clientMessageId, messageId, conversationId, createdAt, day(createdAt))).one();
        if (r.getBoolean("[applied]")) {
            return new Claim(true, messageId, conversationId, createdAt, day(createdAt));
        }
        return new Claim(false, r.getUuid("message_id"), r.getUuid("conversation_id"), r.getInstant("created_at"),
                r.getLocalDate("bucket_date"));
    }

    public record OutboxKey(Instant bucketHour, int shard, Instant createdAt, UUID eventId) {
    }

    public OutboxKey writeOutbox(int shard, UUID eventId, UUID messageId, UUID conversationId, LocalDate msgBucket,
            Instant msgCreatedAt, String eventType, byte[] payload) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant bucket = hour(now);
        exec(insertOutbox.bind(bucket, shard, now, eventId, messageId, conversationId, msgBucket, msgCreatedAt, eventType,
                ByteBuffer.wrap(payload), now));
        return new OutboxKey(bucket, shard, now, eventId);
    }

    public record StoredMessage(UUID conversationId, LocalDate bucketDate, Instant createdAt, UUID messageId, UUID senderId,
            UUID senderDeviceId, byte[] ciphertext, String protocol, String messageType, UUID clientMessageId, Long epoch,
            Instant receivedAt, long membershipVersion, Integer ttlSeconds) {
        public StoredMessage(UUID conversationId, LocalDate bucketDate, Instant createdAt, UUID messageId, UUID senderId,
                UUID senderDeviceId, byte[] ciphertext, String protocol, String messageType, UUID clientMessageId, Long epoch,
                Instant receivedAt, long membershipVersion) {
            this(conversationId, bucketDate, createdAt, messageId, senderId, senderDeviceId, ciphertext, protocol, messageType,
                    clientMessageId, epoch, receivedAt, membershipVersion, null);
        }
    }

    public void writeMessage(StoredMessage m) {
        exec(insertBucket.bind(m.conversationId(), m.bucketDate()));
        exec(insertMessage.bind(m.conversationId(), m.bucketDate(), m.createdAt(), m.messageId(), m.senderId(), m.senderDeviceId(),
                ByteBuffer.wrap(m.ciphertext()), m.protocol(), m.messageType(), m.clientMessageId(), m.epoch(), m.receivedAt(),
                m.membershipVersion(), m.ttlSeconds() == null ? DEFAULT_TTL : m.ttlSeconds()));
    }

    public UUID senderOf(UUID conversationId, LocalDate bucket, Instant createdAt, UUID messageId) {
        var row = exec(selectSender.bind(conversationId, bucket, createdAt, messageId)).one();
        return row == null ? null : row.getUuid("sender_id");
    }

    public void deleteMessage(UUID conversationId, LocalDate bucket, Instant createdAt, UUID messageId) {
        exec(deleteMessage.bind(conversationId, bucket, createdAt, messageId));
    }

    public boolean messageExists(UUID conversationId, LocalDate bucket, Instant createdAt, UUID messageId) {
        return exec(messageExists.bind(conversationId, bucket, createdAt, messageId)).one() != null;
    }

    // ------------------------------------------------------------------ history

    public List<LocalDate> buckets(UUID conversationId, LocalDate notAfter, int limit) {
        List<LocalDate> out = new ArrayList<>();
        for (Row r : exec(listBuckets.bind(conversationId, notAfter, limit))) {
            out.add(r.getLocalDate(0));
        }
        return out;
    }

    public List<StoredMessage> page(UUID conversationId, LocalDate bucket, Instant beforeCreated, UUID beforeId, int limit) {
        BoundStatement s = beforeCreated == null ? pageFirst.bind(conversationId, bucket, limit)
                : pageAfter.bind(conversationId, bucket, beforeCreated, beforeId, limit);
        List<StoredMessage> out = new ArrayList<>();
        for (Row r : exec(s)) {
            out.add(toMessage(r));
        }
        return out;
    }

    private static StoredMessage toMessage(Row r) {
        ByteBuffer ct = r.getByteBuffer("ciphertext");
        byte[] bytes = new byte[ct == null ? 0 : ct.remaining()];
        if (ct != null) {
            ct.duplicate().get(bytes);
        }
        return new StoredMessage(r.getUuid("conversation_id"), r.getLocalDate("bucket_date"), r.getInstant("created_at"),
                r.getUuid("message_id"), r.getUuid("sender_id"), r.getUuid("sender_device_id"), bytes, r.getString("encryption_protocol"),
                r.getString("message_type"), r.getUuid("client_message_id"), r.isNull("epoch") ? null : r.getLong("epoch"),
                r.getInstant("received_at"), r.isNull("membership_version") ? 0 : r.getLong("membership_version"));
    }

    // ------------------------------------------------------------------ outbox publisher

    public record OutboxRow(Instant bucketHour, int shard, Instant createdAt, UUID eventId, UUID messageId, UUID conversationId,
            LocalDate messageBucket, Instant messageCreatedAt, String eventType, byte[] payload, String status, int attempts,
            Instant nextAttemptAt) {
    }

    public List<OutboxRow> scanOutbox(Instant bucketHour, int shard, int limit) {
        List<OutboxRow> out = new ArrayList<>();
        for (Row r : exec(outboxScan.bind(bucketHour, shard, limit))) {
            ByteBuffer p = r.getByteBuffer("payload");
            byte[] b = new byte[p.remaining()];
            p.duplicate().get(b);
            out.add(new OutboxRow(r.getInstant("bucket_hour"), r.getInt("shard"), r.getInstant("created_at"), r.getUuid("event_id"),
                    r.getUuid("message_id"), r.getUuid("conversation_id"), r.getLocalDate("message_bucket_date"),
                    r.getInstant("message_created_at"), r.getString("event_type"), b, r.getString("status"), r.getInt("attempt_count"),
                    r.getInstant("next_attempt_at")));
        }
        return out;
    }

    public void updateOutbox(OutboxRow r, String status, int attempts, Instant next, String error) {
        exec(outboxUpdate.bind(status, attempts, next, error, r.bucketHour(), r.shard(), r.createdAt(), r.eventId()));
    }

    public void markPublished(OutboxRow r) {
        exec(markerInsert.bind(r.bucketHour(), r.shard(), r.eventId(), Instant.now()));
    }

    public boolean isPublished(OutboxRow r) {
        return exec(markerGet.bind(r.bucketHour(), r.shard(), r.eventId())).one() != null;
    }

    public void deleteOutbox(OutboxRow r) {
        exec(outboxDelete.bind(r.bucketHour(), r.shard(), r.createdAt(), r.eventId()));
    }

    public record Lease(String owner, Instant until, Instant cursor) {
    }

    public Optional<Lease> lease(int shard) {
        Row r = exec(leaseGet.bind(shard)).one();
        return r == null ? Optional.empty() : Optional.of(new Lease(r.getString(0), r.getInstant(1), r.getInstant(2)));
    }

    /** Acquire or renew the shard lease (LWT). */
    public boolean acquire(int shard, String owner, Instant until, Instant initialCursor) {
        Row ins = exec(leaseInsert.bind(shard, owner, until, initialCursor)).one();
        if (ins.getBoolean("[applied]")) {
            return true;
        }
        if (owner.equals(ins.getString("owner"))) {
            return exec(leaseRenew.bind(until, shard, owner)).one().getBoolean("[applied]");
        }
        return exec(leaseSteal.bind(owner, until, shard, Instant.now())).one().getBoolean("[applied]");
    }

    public void advanceCursor(int shard, String owner, Instant cursor) {
        exec(leaseCursor.bind(cursor, shard, owner));
    }

    public void release(int shard, String owner) {
        exec(leaseRelease.bind(Instant.EPOCH, shard, owner));
    }
}
