package com.securechat.contracts;

import com.securechat.contracts.events.ConversationCreated;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.DeviceConnection;
import com.securechat.contracts.events.DeviceRegistered;
import com.securechat.contracts.events.DeviceRevoked;
import com.securechat.contracts.events.Envelope;
import com.securechat.contracts.events.FanoutCompleted;
import com.securechat.contracts.events.GroupCommitted;
import com.securechat.contracts.events.GroupMembershipChanged;
import com.securechat.contracts.events.MessageAccepted;
import com.securechat.contracts.events.MessageFailed;
import com.securechat.contracts.events.MessageReceipt;
import com.securechat.contracts.events.NotificationRequested;
import com.securechat.contracts.events.PrekeysLow;
import com.securechat.contracts.events.PresenceChanged;
import com.securechat.contracts.events.UserRegistered;
import com.securechat.contracts.events.UserStatusChanged;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One representative payload per event type (what producers emit). */
final class Samples {
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final UUID C = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-11-18T10:15:30.123Z");

    private Samples() {
    }

    static Object of(EventType t) {
        return switch (t) {
            case MESSAGE_ACCEPTED -> new MessageAccepted(A, B, "DIRECT", C, A, B, "SIGNAL", "SIGNAL_PREKEY", null, 3,
                    NOW, NOW, NOW, List.of(new Envelope(C, 3, "AAEC")), null);
            case MESSAGE_FANOUT_COMPLETED -> new FanoutCompleted(A, B, 2, List.of(B, C),
                    List.of(new FanoutCompleted.SkippedDevice(A, "NO_ENVELOPE")), NOW);
            case MESSAGE_DELIVERY_REQUESTED -> new DeliveryRequested(DeterministicIds.deliveryId(A, B), A, C, B, A, C, B,
                    "MLS", "MLS_APPLICATION", null, 4L, "AAEC", NOW);
            case MESSAGE_DELIVERED -> new MessageReceipt(A, B, C, A, B, "DELIVERED", NOW);
            case MESSAGE_READ -> new MessageReceipt(A, B, C, A, B, "READ", NOW);
            case MESSAGE_FAILED -> new MessageFailed(A, B, C, A, "EXPIRED", NOW);
            case NOTIFICATION_REQUESTED -> new NotificationRequested(A, B, C, NOW);
            case PRESENCE_CHANGED, PRESENCE_ONLINE -> new PresenceChanged(A, true, 2, null, NOW);
            case PRESENCE_OFFLINE -> new PresenceChanged(A, false, 0, NOW, NOW);
            case DEVICE_CONNECTED, DEVICE_DISCONNECTED -> new DeviceConnection(A, B, "gw-1", NOW);
            case DEVICE_REGISTERED -> new DeviceRegistered(A, B, "WEB", "Firefox on Linux", NOW);
            case DEVICE_REVOKED -> new DeviceRevoked(A, B, "USER", NOW);
            case USER_REGISTERED -> new UserRegistered(A, "alice", "Alice", NOW);
            case USER_STATUS_CHANGED -> new UserStatusChanged(A, "SUSPENDED", NOW);
            case CONVERSATION_CREATED -> new ConversationCreated(A, "GROUP", List.of(B, C), B, NOW);
            case GROUP_MEMBER_ADDED, GROUP_MEMBER_REMOVED -> new GroupMembershipChanged(A, List.of(B), List.of(B, C), 2, 3,
                    C, NOW);
            case GROUP_COMMIT -> new GroupCommitted(A, 5, B, List.of(B, C), NOW);
            case KEYS_PREKEYS_LOW -> new PrekeysLow(A, B, 12, 3);
        };
    }
}
