package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.message.delivered / chat.message.read v1 — a recipient device acknowledged. */
public record MessageReceipt(
        UUID messageId,
        UUID conversationId,
        UUID senderUserId,
        UUID recipientUserId,
        UUID recipientDeviceId,
        String status,
        Instant at) {
}
