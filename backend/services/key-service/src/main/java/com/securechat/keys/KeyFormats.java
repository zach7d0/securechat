package com.securechat.keys;

import com.securechat.support.web.ApiException;
import java.util.Base64;

/**
 * Structural validation of PUBLIC key material produced by the client libraries
 * (@privacyresearch/libsignal-protocol-typescript, ts-mls). The server does not verify signatures:
 * that would require an XEdDSA implementation on the JVM; clients verify every signature with the
 * protocol library before use (libsignal processPreKey, MLS credential binding), which is the
 * authoritative check. Anything that is not exactly the expected public format is rejected.
 */
public final class KeyFormats {
    /** libsignal serialised Curve25519 public key: 0x05 type byte + 32 bytes. */
    public static final int CURVE_PUBLIC_LEN = 33;
    public static final int XEDDSA_SIGNATURE_LEN = 64;
    public static final int ED25519_PUBLIC_LEN = 32;
    public static final int MAX_KEY_PACKAGE_LEN = 4096;

    private KeyFormats() {
    }

    public static byte[] b64(String field, String value, int maxLen) {
        if (value == null || value.isEmpty() || value.length() > maxLen * 2) {
            throw ApiException.badRequest(field + " is required");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(field + " must be base64");
        }
    }

    public static byte[] curvePublicKey(String field, String value) {
        byte[] k = b64(field, value, CURVE_PUBLIC_LEN);
        if (k.length != CURVE_PUBLIC_LEN || k[0] != 0x05) {
            throw ApiException.badRequest(field + " must be a 33-byte Curve25519 public key (0x05 prefix)");
        }
        return k;
    }

    public static byte[] signature(String field, String value) {
        byte[] s = b64(field, value, XEDDSA_SIGNATURE_LEN);
        if (s.length != XEDDSA_SIGNATURE_LEN) {
            throw ApiException.badRequest(field + " must be a 64-byte signature");
        }
        return s;
    }

    public static byte[] ed25519PublicKey(String field, String value) {
        byte[] k = b64(field, value, ED25519_PUBLIC_LEN);
        if (k.length != ED25519_PUBLIC_LEN) {
            throw ApiException.badRequest(field + " must be a 32-byte Ed25519 public key");
        }
        return k;
    }

    /** MLSMessage framing: ProtocolVersion mls10 (0x0001), WireFormat mls_key_package (0x0005). */
    public static byte[] mlsKeyPackage(String field, String value) {
        byte[] kp = b64(field, value, MAX_KEY_PACKAGE_LEN);
        if (kp.length < 64 || kp.length > MAX_KEY_PACKAGE_LEN || kp[0] != 0 || kp[1] != 1 || kp[2] != 0 || kp[3] != 5) {
            throw ApiException.badRequest(field + " must be an MLS KeyPackage message");
        }
        return kp;
    }

    public static void registrationId(int id) {
        if (id < 1 || id > 16380) {
            throw ApiException.badRequest("registrationId must be 1..16380");
        }
    }

    public static String encode(byte[] b) {
        return b == null ? null : Base64.getEncoder().encodeToString(b);
    }
}
