package de.ostms.evils.ebics.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/** Minimal RFC 6238 TOTP (30s step, 6 digits, HMAC-SHA1 - the parameters every authenticator app
 * assumes by default) - implemented directly on top of javax.crypto rather than pulling in a
 * third-party TOTP library, since the algorithm is small and this is security-sensitive code
 * worth keeping auditable in one place. Used for optional SUPERADMIN 2FA (see AdminUserService). */
@Service
public class TotpService {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    /** Accepts the current step and one step before/after it, to tolerate clock drift between the
     * server and the user's phone without widening the window enough to matter for brute force. */
    private static final int ALLOWED_STEP_DRIFT = 1;
    private static final String ISSUER = "EVILS";

    private final SecureRandom random = new SecureRandom();

    /** A fresh, random 160-bit secret, Base32-encoded (the standard secret length for HMAC-SHA1 -
     * matches the key size, avoiding HMAC's own key-padding/hashing of an odd-length key). */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    /** otpauth:// URI for a user to scan/import into an authenticator app - shown alongside the
     * raw secret (for manual entry) during setup, never stored, never needed again afterwards. */
    public String otpauthUri(String secret, String username) {
        String label = ISSUER + ":" + username;
        return "otpauth://totp/" + urlEncode(label) + "?secret=" + secret + "&issuer=" + ISSUER
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + TIME_STEP_SECONDS;
    }

    /** True if {@code code} matches the TOTP for {@code secret} at the current time step or one
     * step of drift either side. */
    public boolean verify(String secret, String code) {
        if (code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) return false;
        long currentStep = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (long step = currentStep - ALLOWED_STEP_DRIFT; step <= currentStep + ALLOWED_STEP_DRIFT; step++) {
            if (code.equals(codeAt(secret, step))) return true;
        }
        return false;
    }

    /** Public (not private) so tests can compute an expected/valid code directly - both
     * TotpServiceTest (against the official RFC 4226 Appendix D HOTP test vector, without
     * depending on wall-clock time) and TotpLoginTest (to drive a real end-to-end login). Exposing
     * the raw computation isn't a security concern by itself: it still requires knowing the
     * secret, exactly like verify() already does internally. */
    public static String codeAt(String secret, long step) {
        byte[] key = Base32.decode(secret);
        byte[] stepBytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            stepBytes[i] = (byte) (step & 0xff);
            step >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(stepBytes);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            int truncated = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", truncated);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP-Berechnung fehlgeschlagen", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
