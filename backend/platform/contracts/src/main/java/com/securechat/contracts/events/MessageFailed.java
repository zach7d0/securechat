package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.message.failed v1 — terminal failure for one recipient device (EXPIRED, DEVICE_REVOKED). */
public record MessageFailed(
        UUID messageId,
        UUID conversationId,
        UUID recipientUserId,
        UUID recipientDeviceId,
        String reason,
        Instant at) {
}
