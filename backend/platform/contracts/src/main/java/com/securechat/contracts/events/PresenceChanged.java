package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.presence.changed / online / offline v1 — aggregate user presence across devices. */
public record PresenceChanged(UUID userId, boolean online, int activeDevices, Instant lastSeen, Instant at) {
}
