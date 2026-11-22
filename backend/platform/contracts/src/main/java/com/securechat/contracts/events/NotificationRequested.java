package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.notification.requested v1 — generic "new message" push; carries no content. */
public record NotificationRequested(UUID recipientUserId, UUID recipientDeviceId, UUID messageId, Instant at) {
}
