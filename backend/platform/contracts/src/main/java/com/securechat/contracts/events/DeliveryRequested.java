package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.message.delivery.requested v1 — one deterministic task per recipient device. */
public record DeliveryRequested(
        UUID deliveryId,
        UUID messageId,
        UUID conversationId,
        UUID senderUserId,
        UUID senderDeviceId,
        UUID recipientUserId,
        UUID recipientDeviceId,
        String protocol,
        String messageType,
        Integer signalType,
        Long epoch,
        String ciphertext,
        Instant createdAt) {
}
