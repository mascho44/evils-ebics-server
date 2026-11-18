package de.ostms.evils.ebics.client;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;

/** Three EBICS RSA key pairs, persisted in an authenticated, password-encrypted file. */
public final class EbicsKeyMaterial {
    private static final byte[] MAGIC = {'E','V','I','L','S','E','B','I','C','S',1};
    private final KeyPair signature;
    private final KeyPair authentication;
    private final KeyPair encryption;

    private EbicsKeyMaterial(KeyPair signature, KeyPair authentication, KeyPair encryption) {
        this.signature = signature; this.authentication = authentication; this.encryption = encryption;
    }

    public static EbicsKeyMaterial generate() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
        return new EbicsKeyMaterial(generator.generateKeyPair(), generator.generateKeyPair(), generator.generateKeyPair());
    }

    public KeyPair signature() { return signature; }
    public KeyPair authentication() { return authentication; }
    public KeyPair encryption() { return encryption; }

    public void save(Path path, char[] password) throws IOException, GeneralSecurityException {
        if (password == null || password.length < 12) throw new IllegalArgumentException("Password must contain at least 12 characters");
        byte[] salt = random(16), nonce = random(12), clear;
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            write(out, signature); write(out, authentication); write(out, encryption); clear = bytes.toByteArray();
        }
        byte[] key = derive(password, salt);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            byte[] encrypted = cipher.doFinal(clear);
            Path parent = path.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
            try (var out = new DataOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
                out.write(MAGIC); out.write(salt); out.write(nonce); out.writeInt(encrypted.length); out.write(encrypted);
            }
            try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
        } finally { Arrays.fill(key, (byte) 0); Arrays.fill(clear, (byte) 0); }
    }

    public static EbicsKeyMaterial load(Path path, char[] password) throws IOException, GeneralSecurityException {
        byte[] salt = new byte[16], nonce = new byte[12], encrypted;
        try (var in = new DataInputStream(Files.newInputStream(path))) {
            if (!Arrays.equals(in.readNBytes(MAGIC.length), MAGIC)) throw new IOException("Not an EVILS EBICS key file");
            in.readFully(salt); in.readFully(nonce); int size = in.readInt();
            if (size < 1 || size > 100_000) throw new IOException("Invalid encrypted key data length");
            encrypted = in.readNBytes(size); if (encrypted.length != size || in.read() != -1) throw new IOException("Truncated or trailing key data");
        }
        byte[] key = derive(password, salt), clear = null;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(MAGIC);
            clear = cipher.doFinal(encrypted);
            try (var in = new DataInputStream(new ByteArrayInputStream(clear))) {
                var result = new EbicsKeyMaterial(read(in), read(in), read(in));
                if (in.read() != -1) throw new IOException("Trailing clear key data"); return result;
            }
        } finally { Arrays.fill(key, (byte) 0); if (clear != null) Arrays.fill(clear, (byte) 0); }
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        var spec = new PBEKeySpec(password, salt, 310_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static byte[] random(int size) { byte[] value = new byte[size]; new SecureRandom().nextBytes(value); return value; }
    private static void write(DataOutputStream out, KeyPair pair) throws IOException { blob(out, pair.getPublic().getEncoded()); blob(out, pair.getPrivate().getEncoded()); }
    private static void blob(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static byte[] blob(DataInputStream in) throws IOException { int n=in.readInt(); if(n<1||n>10_000)throw new IOException("Invalid key length"); return in.readNBytes(n); }
    private static KeyPair read(DataInputStream in) throws IOException, GeneralSecurityException {
        var factory=KeyFactory.getInstance("RSA"); return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(blob(in))),factory.generatePrivate(new PKCS8EncodedKeySpec(blob(in))));
    }
}
