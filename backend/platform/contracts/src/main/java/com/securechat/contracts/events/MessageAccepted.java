package com.securechat.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * chat.message.accepted v1 — Message Service accepted responsibility (ciphertext + outbox durable).
 * Exactly one of {@code envelopes} (protocol SIGNAL) or {@code ciphertext} (protocol MLS) is set.
 */
public record MessageAccepted(
        UUID messageId,
        UUID conversationId,
        String conversationType,
        UUID senderUserId,
        UUID senderDeviceId,
        UUID clientMessageId,
        String protocol,
        String messageType,
        Long epoch,
        long membershipVersion,
        Instant createdAt,
        Instant receivedAt,
        Instant persistedAt,
        List<Envelope> envelopes,
        String ciphertext) {
}
