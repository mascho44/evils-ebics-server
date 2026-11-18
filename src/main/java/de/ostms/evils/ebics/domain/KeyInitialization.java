package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Audit trail for simulated EBICS INI, HIA and HPB key-management requests. */
@Entity(name = "key_initializations")
public class KeyInitialization {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String hostId; private String partnerId; private String userId; private String orderType; private String protocolVersion; private LocalDateTime receivedAt;
    protected KeyInitialization() { }
    public KeyInitialization(String hostId, String partnerId, String userId, String orderType, String protocolVersion) {
        this.hostId = hostId; this.partnerId = partnerId; this.userId = userId; this.orderType = orderType; this.protocolVersion = protocolVersion; this.receivedAt = LocalDateTime.now();
    }
    public Long getId() { return id; } public String getHostId() { return hostId; } public String getPartnerId() { return partnerId; }
    public String getUserId() { return userId; } public String getOrderType() { return orderType; } public String getProtocolVersion() { return protocolVersion; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
}
