package com.securechat.notification;

import java.util.UUID;

/**
 * Push transport (APNs/FCM in production). The payload is fixed and generic — it never contains
 * message content, sender names or conversation names (E2EE: the server has none of them anyway).
 */
public interface PushProvider {
    String GENERIC_TITLE = "SecureChat";
    String GENERIC_BODY = "You have a new message";

    record Push(UUID userId, UUID deviceId, String title, String body) {
    }

    void send(Push push) throws Exception;
}
