package de.ostms.evils.ebics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.LocalDateTime;

/**
 * A public, unauthenticated request for a new tenant (see de.ostms.evils.ebics.admin.
 * TenantSignupController) - nothing here creates a Bank/AdminUser until a SUPERADMIN approves it
 * via TenantRequestController. adminPasswordHash is hashed at submission time (see
 * TenantRequestService.submit) so no plaintext password is ever stored, even temporarily; on
 * approval it is used directly (see AdminUserService.createWithHash) without re-hashing.
 * requesterEmail is used only to notify the requester of the approve/reject decision (see
 * TenantRequestService.approve/reject and de.ostms.evils.ebics.service.MailService) - nullable for
 * requests submitted before this field existed.
 */
@Entity(name = "tenant_requests")
public class TenantRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String hostId;
    private String hostName;
    private String protocolVersion;
    private String bic;
    private String adminUsername;
    private String adminDisplayName;
    @Column(columnDefinition = "TEXT")
    private String adminPasswordHash;
    private String requesterEmail;
    /** PENDING, APPROVED or REJECTED - plain string like EventOutbox.status, no dedicated
     * approval-queue precedent existed in this codebase to mirror more closely. */
    private String status;
    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;
    private String decidedBy;
    private String rejectionReason;

    protected TenantRequest() {
    }

    public TenantRequest(String hostId, String hostName, String protocolVersion, String bic,
                          String adminUsername, String adminDisplayName, String adminPasswordHash,
                          String requesterEmail) {
        this.hostId = hostId;
        this.hostName = hostName;
        this.protocolVersion = protocolVersion;
        this.bic = bic;
        this.adminUsername = adminUsername;
        this.adminDisplayName = adminDisplayName;
        this.adminPasswordHash = adminPasswordHash;
        this.requesterEmail = requesterEmail;
        this.status = "PENDING";
        this.requestedAt = LocalDateTime.now();
    }

    public void approve(String decidedBy) {
        this.status = "APPROVED";
        this.decidedAt = LocalDateTime.now();
        this.decidedBy = decidedBy;
    }

    public void reject(String decidedBy, String reason) {
        this.status = "REJECTED";
        this.decidedAt = LocalDateTime.now();
        this.decidedBy = decidedBy;
        this.rejectionReason = reason;
    }

    public Long getId() { return id; }
    public String getHostId() { return hostId; }
    public String getHostName() { return hostName; }
    public String getProtocolVersion() { return protocolVersion; }
    public String getBic() { return bic; }
    public String getAdminUsername() { return adminUsername; }
    public String getAdminDisplayName() { return adminDisplayName; }
    public String getAdminPasswordHash() { return adminPasswordHash; }
    public String getRequesterEmail() { return requesterEmail; }
    public String getStatus() { return status; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public String getRejectionReason() { return rejectionReason; }
}
