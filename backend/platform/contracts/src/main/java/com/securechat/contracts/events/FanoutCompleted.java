package com.securechat.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** chat.message.fanout.completed v1 — per-device delivery tasks were published for a message. */
public record FanoutCompleted(
        UUID messageId,
        UUID conversationId,
        int expectedDevices,
        List<UUID> deviceIds,
        List<SkippedDevice> skippedDevices,
        Instant completedAt) {

    /** reason: NO_ENVELOPE (sender did not encrypt for it), DEVICE_INACTIVE. */
    public record SkippedDevice(UUID deviceId, String reason) {
    }
}
