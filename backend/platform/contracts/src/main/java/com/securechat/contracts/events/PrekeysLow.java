package com.securechat.contracts.events;

import java.util.UUID;

/** chat.keys.prekeys.low v1 — the device should upload more one-time prekeys / key packages. */
public record PrekeysLow(UUID userId, UUID deviceId, long remainingPrekeys, long remainingKeyPackages) {
}
