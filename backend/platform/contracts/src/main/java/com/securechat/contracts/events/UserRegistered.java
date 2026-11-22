package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.user.registered v1. */
public record UserRegistered(UUID userId, String username, String displayName, Instant at) {
}
