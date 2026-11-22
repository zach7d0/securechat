package com.securechat.admin;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-256-GCM at-rest encryption for admin TOTP secrets (key from ADMIN_MFA_ENCRYPTION_KEY). */
@Component
public class SecretBox {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    SecretBox(AdminProperties props) {
        byte[] k;
        try {
            k = Base64.getDecoder().decode(props.mfaEncryptionKey() == null ? "" : props.mfaEncryptionKey());
        } catch (IllegalArgumentException e) {
            k = new byte[0];
        }
        if (k.length != 32) {
            throw new IllegalStateException("securechat.admin.mfa-encryption-key must be 32 random bytes, base64");
        }
        this.key = new SecretKeySpec(k, "AES");
    }

    public byte[] seal(byte[] plain, String aad) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(aad.getBytes());
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] open(byte[] sealed, String aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, 12));
            c.updateAAD(aad.getBytes());
            return c.doFinal(sealed, 12, sealed.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot decrypt MFA secret", e);
        }
    }
}
