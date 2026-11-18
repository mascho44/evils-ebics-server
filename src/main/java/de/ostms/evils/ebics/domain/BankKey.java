package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Bank-owned RSA key material; private bytes are stored encrypted, never in clear text. */
@Entity(name = "bank_keys")
public class BankKey {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String keyType; private String algorithm;
    @Column(columnDefinition = "TEXT") private String publicKeyBase64;
    @Column(columnDefinition = "TEXT") private String encryptedPrivateKeyBase64;
    private String encryptionSaltBase64; private String encryptionIvBase64; private String fingerprintSha256; private boolean active; private LocalDateTime createdAt;
    protected BankKey() { }
    public BankKey(String keyType, String algorithm, String publicKeyBase64, String encryptedPrivateKeyBase64, String encryptionSaltBase64, String encryptionIvBase64, String fingerprintSha256) {
        this.keyType=keyType; this.algorithm=algorithm; this.publicKeyBase64=publicKeyBase64; this.encryptedPrivateKeyBase64=encryptedPrivateKeyBase64;
        this.encryptionSaltBase64=encryptionSaltBase64; this.encryptionIvBase64=encryptionIvBase64; this.fingerprintSha256=fingerprintSha256; this.active=true; this.createdAt=LocalDateTime.now();
    }
    public Long getId(){return id;} public String getKeyType(){return keyType;} public String getAlgorithm(){return algorithm;} public String getPublicKeyBase64(){return publicKeyBase64;}
    public String getEncryptedPrivateKeyBase64(){return encryptedPrivateKeyBase64;} public String getEncryptionSaltBase64(){return encryptionSaltBase64;} public String getEncryptionIvBase64(){return encryptionIvBase64;}
    public String getFingerprintSha256(){return fingerprintSha256;} public boolean isActive(){return active;} public LocalDateTime getCreatedAt(){return createdAt;}
    public void retire(){active=false;}
}
