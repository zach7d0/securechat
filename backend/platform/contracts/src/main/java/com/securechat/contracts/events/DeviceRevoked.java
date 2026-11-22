package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.device.revoked v1 — sessions, key bundles and live sockets of the device must be cut. */
public record DeviceRevoked(UUID userId, UUID deviceId, String revokedBy, Instant at) {
}
