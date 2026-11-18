package de.ostms.evils.ebics.service;

import de.ostms.evils.ebics.domain.BankKey;
import de.ostms.evils.ebics.persistence.BankKeyRepository;
import de.ostms.evils.ebics.security.CredentialProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.*;

@Service
public class BankKeyService {
    private static final List<String> KEY_TYPES = List.of("AUTHENTICATION", "ENCRYPTION", "SIGNATURE");
    private final BankKeyRepository keys; private final String masterSecret;
    public BankKeyService(BankKeyRepository keys, CredentialProvider credentials) { this.keys=keys; this.masterSecret=credentials.masterSecret(); }
    public boolean isConfigured() { return masterSecret != null && masterSecret.length() >= 16; }
    public List<BankKey> all() { return keys.findAllByOrderByCreatedAtDesc(); }
    @Transactional public java.security.PrivateKey authenticationPrivateKey() throws Exception {
        return privateKey("AUTHENTICATION");
    }
    @Transactional public java.security.PrivateKey privateKey(String type) throws Exception {
        var key=keys.findByKeyTypeAndActiveTrue(type).stream().findFirst().orElseThrow();
        byte[] bytes=KeyMaterialProtector.unprotect(new KeyMaterialProtector.ProtectedMaterial(
                Base64.getDecoder().decode(key.getEncryptedPrivateKeyBase64()),Base64.getDecoder().decode(key.getEncryptionSaltBase64()),Base64.getDecoder().decode(key.getEncryptionIvBase64())),masterSecret.toCharArray());
        try{return java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(bytes));}
        finally{Arrays.fill(bytes,(byte)0);}
    }

    /** The keys HPB hands back to a customer (authentication + encryption; the bank's own
     * SIGNATURE key never leaves the server). Either or both may be missing if no key set has
     * been generated yet - callers render whatever is present, see EbicsResponseBuilder.hpb. */
    public List<BankKey> activeAuthenticationAndEncryptionKeys() {
        var result = new ArrayList<BankKey>();
        result.addAll(keys.findByKeyTypeAndActiveTrue("AUTHENTICATION"));
        result.addAll(keys.findByKeyTypeAndActiveTrue("ENCRYPTION"));
        return result;
    }
    @Transactional public List<BankKey> generateActiveKeySet() {
        if (!isConfigured()) throw new IllegalStateException("EVILS_CRYPTO_MASTER_SECRET must contain at least 16 characters");
        KEY_TYPES.forEach(type -> keys.findByKeyTypeAndActiveTrue(type).forEach(BankKey::retire));
        return KEY_TYPES.stream().map(this::generate).toList();
    }
    private BankKey generate(String type) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
            var pair = generator.generateKeyPair();
            byte[] privateBytes = pair.getPrivate().getEncoded();
            try {
                var protectedMaterial = KeyMaterialProtector.protect(privateBytes, masterSecret.toCharArray());
                return keys.save(new BankKey(type, "RSA-3072", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                        Base64.getEncoder().encodeToString(protectedMaterial.ciphertext()), Base64.getEncoder().encodeToString(protectedMaterial.salt()),
                        Base64.getEncoder().encodeToString(protectedMaterial.iv()), fingerprint(pair.getPublic().getEncoded())));
            } finally { Arrays.fill(privateBytes, (byte) 0); }
        } catch (Exception e) { throw new IllegalStateException("Could not generate bank key", e); }
    }
    private String fingerprint(byte[] publicKey) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey)); }
}
