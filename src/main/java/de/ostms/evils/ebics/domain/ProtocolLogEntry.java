package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Metadata-only audit log for accepted EBICS simulator requests. No request payloads are stored. */
@Entity(name = "protocol_log_entries")
public class ProtocolLogEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private LocalDateTime receivedAt; private String hostId; private String partnerId; private String userId;
    private String orderType; private String orderId; private String protocolVersion; private String outcome;
    protected ProtocolLogEntry() { }
    public ProtocolLogEntry(String hostId, String partnerId, String userId, String orderType, String orderId, String protocolVersion, String outcome) {
        this.receivedAt = LocalDateTime.now(); this.hostId = hostId; this.partnerId = partnerId; this.userId = userId;
        this.orderType = orderType; this.orderId = orderId; this.protocolVersion = protocolVersion; this.outcome = outcome;
    }
    public Long getId() { return id; } public LocalDateTime getReceivedAt() { return receivedAt; } public String getHostId() { return hostId; }
    public String getPartnerId() { return partnerId; } public String getUserId() { return userId; } public String getOrderType() { return orderType; }
    public String getOrderId() { return orderId; } public String getProtocolVersion() { return protocolVersion; } public String getOutcome() { return outcome; }
}
