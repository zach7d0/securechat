package com.securechat.admin;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30 s step, 6 digits) — the interoperable profile used by authenticator
 * apps — built on the JDK's HMAC implementation (RFC 4226 dynamic truncation). Accepts ±1 step of
 * clock skew; callers reject reuse of an already-accepted step (replay protection).
 */
public final class Totp {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] s = new byte[20];
        RANDOM.nextBytes(s);
        return s;
    }

    public static long step(long epochMillis) {
        return epochMillis / 1000 / 30;
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the matching step (within ±1), or -1 */
    public static long verify(byte[] secret, String code, long nowMillis) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        long now = step(nowMillis);
        for (long s = now - 1; s <= now + 1; s++) {
            if (MessageDigest.isEqual(code(secret, s).getBytes(), code.getBytes())) {
                return s;
            }
        }
        return -1;
    }

    public static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    public static byte[] fromBase32(String s) {
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toUpperCase().toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) {
                continue;
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        byte[] r = new byte[out.position()];
        out.flip().get(r);
        return r;
    }
}
