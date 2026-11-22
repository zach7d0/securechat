package com.securechat.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** chat.group.commit v1 — an MLS commit moved the group to {@code epoch}; members fetch it. */
public record GroupCommitted(UUID conversationId, long epoch, UUID senderDeviceId, List<UUID> memberUserIds,
        Instant at) {
}
