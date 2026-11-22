package com.securechat.contracts.events;

import java.time.Instant;
import java.util.UUID;

/** chat.device.connected / disconnected v1. */
public record DeviceConnection(UUID userId, UUID deviceId, String gatewayId, Instant at) {
}
