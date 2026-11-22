package com.securechat.contracts;

import com.securechat.contracts.events.ConversationCreated;
import com.securechat.contracts.events.DeliveryRequested;
import com.securechat.contracts.events.DeviceConnection;
import com.securechat.contracts.events.DeviceRegistered;
import com.securechat.contracts.events.DeviceRevoked;
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
import java.util.Arrays;
import java.util.Optional;

/** Registry of every event: wire name, current major version, topic and payload type. */
public enum EventType {
    MESSAGE_ACCEPTED("message.accepted", 1, Topics.MESSAGE_ACCEPTED, MessageAccepted.class),
    MESSAGE_FANOUT_COMPLETED("message.fanout.completed", 1, Topics.MESSAGE_FANOUT_COMPLETED, FanoutCompleted.class),
    MESSAGE_DELIVERY_REQUESTED("message.delivery.requested", 1, Topics.MESSAGE_DELIVERY_REQUESTED, DeliveryRequested.class),
    MESSAGE_DELIVERED("message.delivered", 1, Topics.MESSAGE_DELIVERED, MessageReceipt.class),
    MESSAGE_READ("message.read", 1, Topics.MESSAGE_READ, MessageReceipt.class),
    MESSAGE_FAILED("message.failed", 1, Topics.MESSAGE_FAILED, MessageFailed.class),
    NOTIFICATION_REQUESTED("notification.requested", 1, Topics.NOTIFICATION_REQUESTED, NotificationRequested.class),
    PRESENCE_CHANGED("presence.changed", 1, Topics.PRESENCE_CHANGED, PresenceChanged.class),
    PRESENCE_ONLINE("presence.online", 1, Topics.PRESENCE_ONLINE, PresenceChanged.class),
    PRESENCE_OFFLINE("presence.offline", 1, Topics.PRESENCE_OFFLINE, PresenceChanged.class),
    DEVICE_CONNECTED("device.connected", 1, Topics.DEVICE_CONNECTED, DeviceConnection.class),
    DEVICE_DISCONNECTED("device.disconnected", 1, Topics.DEVICE_DISCONNECTED, DeviceConnection.class),
    DEVICE_REGISTERED("device.registered", 1, Topics.DEVICE_REGISTERED, DeviceRegistered.class),
    DEVICE_REVOKED("device.revoked", 1, Topics.DEVICE_REVOKED, DeviceRevoked.class),
    USER_REGISTERED("user.registered", 1, Topics.USER_REGISTERED, UserRegistered.class),
    USER_STATUS_CHANGED("user.status.changed", 1, Topics.USER_STATUS_CHANGED, UserStatusChanged.class),
    CONVERSATION_CREATED("conversation.created", 1, Topics.CONVERSATION_CREATED, ConversationCreated.class),
    GROUP_MEMBER_ADDED("group.member.added", 1, Topics.GROUP_MEMBER_ADDED, GroupMembershipChanged.class),
    GROUP_MEMBER_REMOVED("group.member.removed", 1, Topics.GROUP_MEMBER_REMOVED, GroupMembershipChanged.class),
    GROUP_COMMIT("group.commit", 1, Topics.GROUP_COMMIT, GroupCommitted.class),
    KEYS_PREKEYS_LOW("keys.prekeys.low", 1, Topics.KEYS_PREKEYS_LOW, PrekeysLow.class);

    private final String wireName;
    private final int version;
    private final String topic;
    private final Class<?> payloadType;

    EventType(String wireName, int version, String topic, Class<?> payloadType) {
        this.wireName = wireName;
        this.version = version;
        this.topic = topic;
        this.payloadType = payloadType;
    }

    public String wireName() {
        return wireName;
    }

    public int version() {
        return version;
    }

    public String topic() {
        return topic;
    }

    public Class<?> payloadType() {
        return payloadType;
    }

    /** JSON schema resource for this event type and version. */
    public String schemaResource() {
        return "/schemas/" + wireName + ".v" + version + ".json";
    }

    public static Optional<EventType> fromWire(String wireName) {
        return Arrays.stream(values()).filter(t -> t.wireName.equals(wireName)).findFirst();
    }
}
