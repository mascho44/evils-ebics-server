package de.ostms.evils.ebics.security;

/** RFC 4648 Base32 (no padding on encode, padding tolerated on decode) - the JDK has no built-in
 * Base32 (only Base64), and authenticator apps universally expect a Base32 secret, so this exists
 * purely to serve TotpService without pulling in a dependency for ~20 lines of bit-shuffling. */
final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    static String encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                out.append(ALPHABET.charAt((buffer >> bitsLeft) & 0x1f));
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] decode(String encoded) {
        String cleaned = encoded.trim().toUpperCase(java.util.Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bitsLeft = 0;
        for (char c : cleaned.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) continue;
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
