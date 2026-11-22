package com.securechat.delivery;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** CQL for delivery state. Every query names its partition; receipt transitions are LWTs. */
@Component
public class DeliveryStore {
    private final CqlSession session;
    private final PreparedStatement receiptInsert;
    private final PreparedStatement receiptGet;
    private final PreparedStatement receiptsForMessage;
    private final PreparedStatement receiptDeliver;
    private final PreparedStatement receiptRead;
    private final PreparedStatement receiptFail;
    private final PreparedStatement pendingInsert;
    private final PreparedStatement pendingDelete;
    private final PreparedStatement pendingPage;
    private final PreparedStatement pendingAttempt;
    private final PreparedStatement bucketInsert;
    private final PreparedStatement bucketList;
    private final PreparedStatement bucketDelete;
    private final PreparedStatement deviceIndexInsert;
    private final PreparedStatement deviceIndexList;
    private final PreparedStatement deviceIndexDelete;
    private final PreparedStatement leaseInsert;
    private final PreparedStatement leaseRenew;
    private final PreparedStatement leaseSteal;

    DeliveryStore(CqlSession session) {
        this.session = session;
        CqlMigrator.migrate(session);
        receiptInsert = session.prepare("INSERT INTO message_receipts_by_message (message_id, recipient_device_id, recipient_user_id, "
                + "sender_user_id, conversation_id, status, message_created_at, bucket_date, updated_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) "
                + "IF NOT EXISTS");
        receiptGet = session.prepare("SELECT * FROM message_receipts_by_message WHERE message_id = ? AND recipient_device_id = ?");
        receiptsForMessage = session.prepare("SELECT * FROM message_receipts_by_message WHERE message_id = ?");
        receiptDeliver = session.prepare("UPDATE message_receipts_by_message SET status = 'DELIVERED', delivered_at = ?, updated_at = ? "
                + "WHERE message_id = ? AND recipient_device_id = ? IF status = 'PENDING'");
        receiptRead = session.prepare("UPDATE message_receipts_by_message SET status = 'READ', read_at = ?, updated_at = ? "
                + "WHERE message_id = ? AND recipient_device_id = ? IF status IN ('PENDING', 'DELIVERED')");
        receiptFail = session.prepare("UPDATE message_receipts_by_message SET status = 'FAILED', failure_reason = ?, updated_at = ? "
                + "WHERE message_id = ? AND recipient_device_id = ? IF status = 'PENDING'");
        pendingInsert = session.prepare("INSERT INTO pending_messages_by_device (recipient_device_id, bucket_date, created_at, message_id, "
                + "delivery_id, conversation_id, sender_user_id, sender_device_id, recipient_user_id, ciphertext, protocol, message_type, "
                + "signal_type, epoch, attempt_count, enqueued_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)");
        pendingDelete = session.prepare("DELETE FROM pending_messages_by_device WHERE recipient_device_id = ? AND bucket_date = ? "
                + "AND created_at = ? AND message_id = ?");
        pendingPage = session.prepare("SELECT * FROM pending_messages_by_device WHERE recipient_device_id = ? AND bucket_date = ? LIMIT ?");
        pendingAttempt = session.prepare("UPDATE pending_messages_by_device SET attempt_count = ?, last_attempt_at = ? "
                + "WHERE recipient_device_id = ? AND bucket_date = ? AND created_at = ? AND message_id = ? IF EXISTS");
        bucketInsert = session.prepare("INSERT INTO pending_buckets_by_device (recipient_device_id, bucket_date) VALUES (?, ?)");
        bucketList = session.prepare("SELECT bucket_date FROM pending_buckets_by_device WHERE recipient_device_id = ?");
        bucketDelete = session.prepare("DELETE FROM pending_buckets_by_device WHERE recipient_device_id = ? AND bucket_date = ?");
        deviceIndexInsert = session.prepare("INSERT INTO devices_with_pending (shard, recipient_device_id, recipient_user_id) VALUES (?, ?, ?)");
        deviceIndexList = session.prepare("SELECT recipient_device_id, recipient_user_id FROM devices_with_pending WHERE shard = ?");
        deviceIndexDelete = session.prepare("DELETE FROM devices_with_pending WHERE shard = ? AND recipient_device_id = ?");
        leaseInsert = session.prepare("INSERT INTO sweeper_leases (shard, owner, lease_until) VALUES (?, ?, ?) IF NOT EXISTS");
        leaseRenew = session.prepare("UPDATE sweeper_leases SET lease_until = ? WHERE shard = ? IF owner = ?");
        leaseSteal = session.prepare("UPDATE sweeper_leases SET owner = ?, lease_until = ? WHERE shard = ? IF lease_until < ?");
    }

    private ResultSet exec(BoundStatement s) {
        return session.execute(s.setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM).setSerialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL));
    }

    public static LocalDate day(Instant t) {
        return LocalDate.ofInstant(t, ZoneOffset.UTC);
    }

    public record Pending(UUID deviceId, LocalDate bucket, Instant createdAt, UUID messageId, UUID deliveryId, UUID conversationId,
            UUID senderUserId, UUID senderDeviceId, UUID recipientUserId, byte[] ciphertext, String protocol, String messageType,
            Integer signalType, Long epoch, int attempts, Instant lastAttemptAt) {
    }

    public record Receipt(UUID messageId, UUID deviceId, UUID recipientUserId, UUID senderUserId, UUID conversationId, String status,
            String failureReason, Instant messageCreatedAt, LocalDate bucket, Instant updatedAt, Instant deliveredAt, Instant readAt) {
    }

    /** @return empty if newly created, otherwise the existing receipt (duplicate task). */
    public Optional<Receipt> createReceipt(Pending p) {
        Row r = exec(receiptInsert.bind(p.messageId(), p.deviceId(), p.recipientUserId(), p.senderUserId(), p.conversationId(),
                p.createdAt(), p.bucket(), Instant.now())).one();
        return r.getBoolean("[applied]") ? Optional.empty() : Optional.of(receipt(r));
    }

    public Optional<Receipt> receipt(UUID messageId, UUID deviceId) {
        return Optional.ofNullable(exec(receiptGet.bind(messageId, deviceId)).one()).map(DeliveryStore::receipt);
    }

    public List<Receipt> receipts(UUID messageId) {
        List<Receipt> out = new ArrayList<>();
        exec(receiptsForMessage.bind(messageId)).forEach(r -> out.add(receipt(r)));
        return out;
    }

    public boolean markDelivered(UUID messageId, UUID deviceId) {
        Instant now = Instant.now();
        return exec(receiptDeliver.bind(now, now, messageId, deviceId)).one().getBoolean("[applied]");
    }

    public boolean markRead(UUID messageId, UUID deviceId) {
        Instant now = Instant.now();
        return exec(receiptRead.bind(now, now, messageId, deviceId)).one().getBoolean("[applied]");
    }

    public boolean markFailed(UUID messageId, UUID deviceId, String reason) {
        return exec(receiptFail.bind(reason, Instant.now(), messageId, deviceId)).one().getBoolean("[applied]");
    }

    public void putPending(Pending p, int shard) {
        exec(bucketInsert.bind(p.deviceId(), p.bucket()));
        exec(deviceIndexInsert.bind(shard, p.deviceId(), p.recipientUserId()));
        exec(pendingInsert.bind(p.deviceId(), p.bucket(), p.createdAt(), p.messageId(), p.deliveryId(), p.conversationId(), p.senderUserId(),
                p.senderDeviceId(), p.recipientUserId(), ByteBuffer.wrap(p.ciphertext()), p.protocol(), p.messageType(), p.signalType(),
                p.epoch(), Instant.now()));
    }

    public void deletePending(UUID deviceId, LocalDate bucket, Instant createdAt, UUID messageId) {
        exec(pendingDelete.bind(deviceId, bucket, createdAt, messageId));
    }

    public void recordAttempt(Pending p, int attempts) {
        exec(pendingAttempt.bind(attempts, Instant.now(), p.deviceId(), p.bucket(), p.createdAt(), p.messageId()));
    }

    public List<LocalDate> buckets(UUID deviceId) {
        List<LocalDate> out = new ArrayList<>();
        exec(bucketList.bind(deviceId)).forEach(r -> out.add(r.getLocalDate(0)));
        return out;
    }

    public void deleteBucket(UUID deviceId, LocalDate bucket) {
        exec(bucketDelete.bind(deviceId, bucket));
    }

    public List<Pending> pending(UUID deviceId, LocalDate bucket, int limit) {
        List<Pending> out = new ArrayList<>();
        for (Row r : exec(pendingPage.bind(deviceId, bucket, limit))) {
            ByteBuffer ct = r.getByteBuffer("ciphertext");
            byte[] b = new byte[ct.remaining()];
            ct.duplicate().get(b);
            out.add(new Pending(r.getUuid("recipient_device_id"), r.getLocalDate("bucket_date"), r.getInstant("created_at"),
                    r.getUuid("message_id"), r.getUuid("delivery_id"), r.getUuid("conversation_id"), r.getUuid("sender_user_id"),
                    r.getUuid("sender_device_id"), r.getUuid("recipient_user_id"), b, r.getString("protocol"), r.getString("message_type"),
                    r.isNull("signal_type") ? null : r.getInt("signal_type"), r.isNull("epoch") ? null : r.getLong("epoch"),
                    r.isNull("attempt_count") ? 0 : r.getInt("attempt_count"), r.getInstant("last_attempt_at")));
        }
        return out;
    }

    public record IndexedDevice(UUID deviceId, UUID userId) {
    }

    public List<IndexedDevice> devicesWithPending(int shard) {
        List<IndexedDevice> out = new ArrayList<>();
        exec(deviceIndexList.bind(shard)).forEach(r -> out.add(new IndexedDevice(r.getUuid(0), r.getUuid(1))));
        return out;
    }

    public void removeFromIndex(int shard, UUID deviceId) {
        exec(deviceIndexDelete.bind(shard, deviceId));
    }

    public boolean lease(int shard, String owner, Instant until) {
        Row r = exec(leaseInsert.bind(shard, owner, until)).one();
        if (r.getBoolean("[applied]")) {
            return true;
        }
        if (owner.equals(r.getString("owner"))) {
            return exec(leaseRenew.bind(until, shard, owner)).one().getBoolean("[applied]");
        }
        return exec(leaseSteal.bind(owner, until, shard, Instant.now())).one().getBoolean("[applied]");
    }

    private static Receipt receipt(Row r) {
        return new Receipt(r.getUuid("message_id"), r.getUuid("recipient_device_id"), r.getUuid("recipient_user_id"),
                r.getUuid("sender_user_id"), r.getUuid("conversation_id"), r.getString("status"), r.getString("failure_reason"),
                r.getInstant("message_created_at"), r.getLocalDate("bucket_date"), r.getInstant("updated_at"), r.getInstant("delivered_at"),
                r.getInstant("read_at"));
    }
}
