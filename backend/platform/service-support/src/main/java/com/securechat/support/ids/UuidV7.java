package com.securechat.support.ids;

import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 UUIDv7: 48-bit Unix-ms timestamp + random; time-ordered identifiers (not secrets). */
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return at(System.currentTimeMillis());
    }

    public static UUID at(long epochMillis) {
        long rand = RANDOM.nextLong();
        long msb = (epochMillis << 16) | 0x7000L | ((rand >>> 52) & 0x0fffL);
        long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static long timestampMillis(UUID v7) {
        return v7.getMostSignificantBits() >>> 16;
    }
}
