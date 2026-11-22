package com.securechat.contracts;

/** Kafka topic names. Topics are created by infra/kafka/create-topics.sh (auto-create is disabled). */
public final class Topics {
    public static final String MESSAGE_ACCEPTED = "chat.message.accepted";
    /** Reserved for future large-group batch splitting; unused in the MVP. */
    public static final String MESSAGE_FANOUT_REQUESTED = "chat.message.fanout.requested";
    public static final String MESSAGE_FANOUT_COMPLETED = "chat.message.fanout.completed";
    public static final String MESSAGE_DELIVERY_REQUESTED = "chat.message.delivery.requested";
    public static final String MESSAGE_DELIVERED = "chat.message.delivered";
    public static final String MESSAGE_READ = "chat.message.read";
    public static final String MESSAGE_FAILED = "chat.message.failed";
    public static final String NOTIFICATION_REQUESTED = "chat.notification.requested";
    public static final String PRESENCE_ONLINE = "chat.presence.online";
    public static final String PRESENCE_OFFLINE = "chat.presence.offline";
    public static final String PRESENCE_CHANGED = "chat.presence.changed";
    public static final String DEVICE_CONNECTED = "chat.device.connected";
    public static final String DEVICE_DISCONNECTED = "chat.device.disconnected";
    public static final String DEVICE_REGISTERED = "chat.device.registered";
    public static final String DEVICE_REVOKED = "chat.device.revoked";
    public static final String USER_REGISTERED = "chat.user.registered";
    public static final String USER_STATUS_CHANGED = "chat.user.status.changed";
    public static final String CONVERSATION_CREATED = "chat.conversation.created";
    public static final String GROUP_MEMBER_ADDED = "chat.group.member.added";
    public static final String GROUP_MEMBER_REMOVED = "chat.group.member.removed";
    public static final String GROUP_COMMIT = "chat.group.commit";
    public static final String KEYS_PREKEYS_LOW = "chat.keys.prekeys.low";

    private Topics() {
    }
}
