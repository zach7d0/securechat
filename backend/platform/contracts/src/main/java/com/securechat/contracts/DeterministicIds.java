package com.securechat.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Name-based (RFC 9562 v5, SHA-1) identifiers that every producer/consumer derives identically, so
 * retries and duplicate events map to the same logical record. These are identifiers, not secrets.
 */
public final class DeterministicIds {

    private static final UUID NAMESPACE = UUID.fromString("6f0d3f6e-2b7e-4b56-9b1e-5ec0c4a7a001");

    private DeterministicIds() {
    }

    /** One delivery per (message, recipient device). */
    public static UUID deliveryId(UUID messageId, UUID recipientDeviceId) {
        return v5("delivery:" + messageId + ":" + recipientDeviceId);
    }

    /** Event id of the accepted event for a message (stable across outbox republish). */
    public static UUID acceptedEventId(UUID messageId) {
        return v5("accepted:" + messageId);
    }

    public static UUID fanoutCompletedEventId(UUID messageId) {
        return v5("fanout-completed:" + messageId);
    }

    public static UUID deliveryRequestedEventId(UUID deliveryId) {
        return v5("delivery-requested:" + deliveryId);
    }

    public static UUID receiptEventId(UUID messageId, UUID deviceId, String status) {
        return v5("receipt:" + status + ":" + messageId + ":" + deviceId);
    }

    public static UUID v5(String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(NAMESPACE));
            byte[] h = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (h[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (h[i] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] toBytes(UUID u) {
        byte[] b = new byte[16];
        long msb = u.getMostSignificantBits();
        long lsb = u.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (msb >>> (8 * (7 - i)));
            b[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
        }
        return b;
    }
}
