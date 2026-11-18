package de.ostms.evils.ebics.service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/** AES-256-GCM protection for private key bytes using a PBKDF2-derived key. */
public final class KeyMaterialProtector {
    private static final SecureRandom RANDOM = new SecureRandom();
    private KeyMaterialProtector() { }
    public record ProtectedMaterial(byte[] ciphertext, byte[] salt, byte[] iv) { }
    public static ProtectedMaterial protect(byte[] plaintext, char[] masterSecret) {
        try {
            byte[] salt = new byte[16], iv = new byte[12]; RANDOM.nextBytes(salt); RANDOM.nextBytes(iv);
            byte[] key = derive(masterSecret, salt);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                return new ProtectedMaterial(cipher.doFinal(plaintext), salt, iv);
            } finally { Arrays.fill(key, (byte) 0); }
        } catch (Exception e) { throw new IllegalStateException("Could not protect private key material", e); }
    }
    public static byte[] unprotect(ProtectedMaterial protectedMaterial, char[] masterSecret) {
        try {
            byte[] key = derive(masterSecret, protectedMaterial.salt());
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, protectedMaterial.iv()));
                return cipher.doFinal(protectedMaterial.ciphertext());
            } finally { Arrays.fill(key, (byte) 0); }
        } catch (Exception e) { throw new IllegalStateException("Could not decrypt private key material", e); }
    }
    private static byte[] derive(char[] secret, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, 210_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
