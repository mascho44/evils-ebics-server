package de.ostms.evils.ebics.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Simulator audit record for an EBICS HAC customer acknowledgement. */
@Entity(name = "hac_acknowledgements")
public class HacAcknowledgement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String hostId; private String partnerId; private String userId; private String referencedOrderId; private String protocolVersion; private LocalDateTime receivedAt;
    protected HacAcknowledgement() { }
    public HacAcknowledgement(String hostId, String partnerId, String userId, String referencedOrderId, String protocolVersion) { this.hostId=hostId; this.partnerId=partnerId; this.userId=userId; this.referencedOrderId=referencedOrderId; this.protocolVersion=protocolVersion; this.receivedAt=LocalDateTime.now(); }
    public Long getId(){return id;} public String getHostId(){return hostId;} public String getPartnerId(){return partnerId;} public String getUserId(){return userId;} public String getReferencedOrderId(){return referencedOrderId;} public String getProtocolVersion(){return protocolVersion;} public LocalDateTime getReceivedAt(){return receivedAt;}
}
