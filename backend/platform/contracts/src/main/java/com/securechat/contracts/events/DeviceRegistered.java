package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.device.registered v1. */
public record DeviceRegistered(UUID userId, UUID deviceId, String platform, String deviceName, Instant at) {
}
