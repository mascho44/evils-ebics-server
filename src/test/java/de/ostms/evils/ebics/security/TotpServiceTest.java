package de.ostms.evils.ebics.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService totp = new TotpService();

    /** RFC 4226 Appendix D's official HOTP test vector (key "12345678901234567890" as raw ASCII
     * bytes, counter 0 -> "755224"). TOTP.codeAt(secret, step) is exactly HOTP(secret, counter)
     * with the counter already computed from time, so this pins the HMAC/truncation logic against
     * a real, independently-verifiable vector instead of only testing self-consistency. */
    @Test
    void codeAtMatchesOfficialHotpTestVector() {
        String secret = Base32.encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

        assertThat(TotpService.codeAt(secret, 0)).isEqualTo("755224");
        assertThat(TotpService.codeAt(secret, 1)).isEqualTo("287082");
    }

    @Test
    void verifyAcceptsCodeForCurrentStepAndRejectsWrongOrMalformedCodes() {
        String secret = totp.generateSecret();
        long currentStep = java.time.Instant.now().getEpochSecond() / 30;
        String validCode = TotpService.codeAt(secret, currentStep);

        assertThat(totp.verify(secret, validCode)).isTrue();
        assertThat(totp.verify(secret, "000000".equals(validCode) ? "111111" : "000000")).isFalse();
        assertThat(totp.verify(secret, "abcdef")).isFalse();
        assertThat(totp.verify(secret, null)).isFalse();
    }

    @Test
    void verifyToleratesOneStepOfClockDriftButNotTwo() {
        String secret = totp.generateSecret();
        long currentStep = java.time.Instant.now().getEpochSecond() / 30;

        assertThat(totp.verify(secret, TotpService.codeAt(secret, currentStep - 1))).isTrue();
        assertThat(totp.verify(secret, TotpService.codeAt(secret, currentStep + 1))).isTrue();
        assertThat(totp.verify(secret, TotpService.codeAt(secret, currentStep - 2))).isFalse();
        assertThat(totp.verify(secret, TotpService.codeAt(secret, currentStep + 2))).isFalse();
    }

    @Test
    void base32RoundTripsArbitraryBytes() {
        byte[] original = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, (byte) 255, (byte) 254, 10, 20, 30, 40, 50, 60, 70, 80};

        assertThat(Base32.decode(Base32.encode(original))).isEqualTo(original);
    }

    @Test
    void otpauthUriContainsSecretAndUsername() {
        String secret = totp.generateSecret();

        String uri = totp.otpauthUri(secret, "superadmin");

        assertThat(uri).startsWith("otpauth://totp/EVILS%3Asuperadmin?");
        assertThat(uri).contains("secret=" + secret);
    }
}
