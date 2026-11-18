package de.ostms.evils.ebics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * A customer/participant's PUBLIC key, uploaded via INI (SIGNATURE) or HIA (AUTHENTICATION,
 * ENCRYPTION) and parsed from the real EBICS PubKeyValue/RSAKeyValue XML (see PubKeyValueXml) -
 * unlike BankKey, there is deliberately no private-key field: in real EBICS the bank never
 * receives a customer's private key, only what the customer's own client already generated.
 * publicKeyBase64 uses the same X.509 SubjectPublicKeyInfo DER encoding as BankKey for storage
 * consistency, even though it arrives on the wire as separate Modulus/Exponent values.
 */
@Entity(name = "participant_keys")
public class ParticipantKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    private String keyType;
    private String version;
    private String algorithm;
    @Column(columnDefinition = "TEXT")
    private String publicKeyBase64;
    private String fingerprintSha256;
    private LocalDateTime receivedAt;

    protected ParticipantKey() {
    }

    public ParticipantKey(String userId, String keyType, String version, String algorithm,
                           String publicKeyBase64, String fingerprintSha256) {
        this.userId = userId;
        this.keyType = keyType;
        this.version = version;
        this.algorithm = algorithm;
        this.publicKeyBase64 = publicKeyBase64;
        this.fingerprintSha256 = fingerprintSha256;
        this.receivedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getKeyType() {
        return keyType;
    }

    public String getVersion() {
        return version;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public String getFingerprintSha256() {
        return fingerprintSha256;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
