package com.securechat.contracts.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** chat.conversation.created v1. */
public record ConversationCreated(UUID conversationId, String type, List<UUID> memberUserIds, UUID createdBy,
        Instant at) {
}
