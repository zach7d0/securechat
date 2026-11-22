package com.securechat.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotpTest {
    /** RFC 6238 Appendix B (SHA-1 seed "12345678901234567890"): 8-digit values truncated to 6 digits. */
    @Test
    void matchesRfc6238TestVectors() {
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.code(seed, Totp.step(59_000L))).isEqualTo("287082");          // 94287082
        assertThat(Totp.code(seed, Totp.step(1_111_111_109_000L))).isEqualTo("081804"); // 07081804
        assertThat(Totp.code(seed, Totp.step(1_234_567_890_000L))).isEqualTo("005924"); // 89005924
        assertThat(Totp.code(seed, Totp.step(2_000_000_000_000L))).isEqualTo("279037"); // 69279037
    }

    @Test
    void verifyAllowsOneStepSkewOnlyAndBase32RoundTrips() {
        byte[] s = Totp.newSecret();
        long now = 1_800_000_000_000L;
        assertThat(Totp.verify(s, Totp.code(s, Totp.step(now) - 1), now)).isEqualTo(Totp.step(now) - 1);
        assertThat(Totp.verify(s, Totp.code(s, Totp.step(now) - 3), now)).isEqualTo(-1);
        assertThat(Totp.verify(s, "abc", now)).isEqualTo(-1);
        assertThat(Totp.fromBase32(Totp.base32(s))).isEqualTo(s);
    }
}
