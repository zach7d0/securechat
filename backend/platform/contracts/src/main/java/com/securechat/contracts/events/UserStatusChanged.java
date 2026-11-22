package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.user.status.changed v1 — status ACTIVE or SUSPENDED. */
public record UserStatusChanged(UUID userId, String status, Instant at) {
}
