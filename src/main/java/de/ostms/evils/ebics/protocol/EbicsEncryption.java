package de.ostms.evils.ebics.protocol;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.zip.Deflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implements the actual EBICS download encryption envelope (used for HPB and, later, any other
 * download order type) exactly as org.kopi.ebics.client.User#decrypt expects it on the client
 * side: the order data is zlib-deflated, then AES/CBC-encrypted with a random per-transaction key
 * using a fixed all-zero IV and ISO 10126 padding, and that transaction key is itself
 * RSA/PKCS1-encrypted with the recipient's E002 public key. A real EBICS client asking for HPB
 * will try to genuinely decrypt this, so - unlike the rest of this simulator's simplified
 * responses - this part cannot be faked without breaking real client interoperability.
 */
final class EbicsEncryption {

    private static final SecureRandom RANDOM = new SecureRandom();

    private EbicsEncryption() {
    }

    record Envelope(byte[] transactionKey, byte[] orderData) {
    }

    static Envelope encrypt(byte[] plainXml, RSAPublicKey recipientEncryptionKey) {
        try {
            byte[] compressed = deflate(plainXml);
            byte[] aesKey = new byte[16];
            RANDOM.nextBytes(aesKey);
            byte[] encrypted = aesCbcIso10126Encrypt(compressed, aesKey);
            byte[] wrappedKey = rsaPkcs1Encrypt(aesKey, recipientEncryptionKey);
            return new Envelope(wrappedKey, encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt download order data", e);
        }
    }

    static String sha256Base64(RSAPublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    /** ANSI X9.23/ISO 10126 padding: random fill bytes, last byte is the pad length (1-16). */
    private static byte[] aesCbcIso10126Encrypt(byte[] data, byte[] key) throws GeneralSecurityException {
        int padLength = 16 - (data.length % 16);
        byte[] padded = new byte[data.length + padLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        byte[] padBytes = new byte[padLength - 1];
        RANDOM.nextBytes(padBytes);
        System.arraycopy(padBytes, 0, padded, data.length, padLength - 1);
        padded[padded.length - 1] = (byte) padLength;

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return cipher.doFinal(padded);
    }

    private static byte[] rsaPkcs1Encrypt(byte[] data, RSAPublicKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }
}
