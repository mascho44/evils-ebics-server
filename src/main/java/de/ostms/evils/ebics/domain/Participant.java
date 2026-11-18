package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity(name = "participants")
public class Participant {
    @Id private String userId;
    /** Which tenant (Bank.hostId) this participant belongs to - always the same as its
     * Customer's hostId, denormalized here to avoid a join on every EBICS lookup (see
     * BankService.createParticipant, which enforces the match at creation time). */
    private String hostId;
    private String partnerId; private String name; private boolean active;
    private String authenticationKeyStatus; private String encryptionKeyStatus; private String signatureKeyStatus;
    @jakarta.persistence.Column(nullable=false, columnDefinition="boolean default false")
    private boolean signatureKeyReleased;
    public boolean isSignatureKeyReleased(){return signatureKeyReleased;}
    public void releaseSignatureKey(){if(!"INITIALIZED".equals(signatureKeyStatus))throw new IllegalStateException("Submit INI first");signatureKeyReleased=true;}
    /** Per-participant EBICS permissions: may this user submit payments
     * (BTU/FUL uploads), retrieve statements and reports (BTD downloads), and/or retrieve the
     * MT700/707/710/760 trade-finance documents (BTD with ServiceName=TRC)? All default to true
     * so existing participants - and rows created before these columns existed (ddl-auto:
     * update adds them with this default) - keep working exactly as before. The MT7xx permission
     * is separate from downloadAllowed on purpose: a user may need statements without being
     * allowed to see letters of credit. Both download permissions must hold for an MT7xx pull. */
    @jakarta.persistence.Column(nullable=false, columnDefinition="boolean default true")
    private boolean uploadAllowed = true;
    @jakarta.persistence.Column(nullable=false, columnDefinition="boolean default true")
    private boolean downloadAllowed = true;
    @jakarta.persistence.Column(nullable=false, columnDefinition="boolean default true")
    private boolean lcDownloadAllowed = true;
    public boolean isUploadAllowed(){return uploadAllowed;}
    public boolean isDownloadAllowed(){return downloadAllowed;}
    public boolean isLcDownloadAllowed(){return lcDownloadAllowed;}
    public void setUploadAllowed(boolean uploadAllowed){this.uploadAllowed=uploadAllowed;}
    public void setDownloadAllowed(boolean downloadAllowed){this.downloadAllowed=downloadAllowed;}
    public void setLcDownloadAllowed(boolean lcDownloadAllowed){this.lcDownloadAllowed=lcDownloadAllowed;}
    protected Participant() { }
    public Participant(String hostId, String userId, String partnerId, String name, boolean active, String authenticationKeyStatus, String encryptionKeyStatus, String signatureKeyStatus) { this.hostId=hostId; this.userId=userId; this.partnerId=partnerId; this.name=name; this.active=active; this.authenticationKeyStatus=authenticationKeyStatus; this.encryptionKeyStatus=encryptionKeyStatus; this.signatureKeyStatus=signatureKeyStatus; }
    public String getHostId() { return hostId; }
    public String getUserId() { return userId; } public String getPartnerId() { return partnerId; } public String getName() { return name; } public boolean isActive() { return active; }
    public String getAuthenticationKeyStatus() { return authenticationKeyStatus; } public String getEncryptionKeyStatus() { return encryptionKeyStatus; } public String getSignatureKeyStatus() { return signatureKeyStatus; }
    public String userId() { return userId; } public String partnerId() { return partnerId; } public String name() { return name; } public boolean active() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public void initializeSignatureKey() { this.signatureKeyStatus = "INITIALIZED"; }
    public void initializeAuthenticationAndEncryptionKeys() { this.authenticationKeyStatus = "INITIALIZED"; this.encryptionKeyStatus = "INITIALIZED"; }
    /** Reverts a participant to its pre-INI/HIA state so it can go through the EBICS
     * initialization process again (e.g. after the customer lost their key material) - stored
     * public keys themselves are removed separately by the caller (see KeyManagementService.reset). */
    public void resetKeys() { this.authenticationKeyStatus = "NOT_INITIALIZED"; this.encryptionKeyStatus = "NOT_INITIALIZED"; this.signatureKeyStatus = "NOT_INITIALIZED"; this.signatureKeyReleased=false; }
}
