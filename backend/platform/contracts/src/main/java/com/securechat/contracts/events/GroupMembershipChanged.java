package com.securechat.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** chat.group.member.added / removed v1. {@code memberUserIds} = membership after the change. */
public record GroupMembershipChanged(
        UUID conversationId,
        List<UUID> userIds,
        List<UUID> memberUserIds,
        long membershipVersion,
        long epoch,
        UUID actorUserId,
        Instant at) {
}
