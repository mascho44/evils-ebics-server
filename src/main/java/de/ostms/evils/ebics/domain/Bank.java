package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity(name = "banks")
public class Bank {
    @Id private String hostId;
    private String hostName; private String protocolVersion; private String bic;
    protected Bank() { }
    public Bank(String hostId, String hostName, String protocolVersion, String bic) { this.hostId=hostId; this.hostName=hostName; this.protocolVersion=protocolVersion; this.bic=bic; }
    public String getHostId() { return hostId; } public String getHostName() { return hostName; } public String getProtocolVersion() { return protocolVersion; } public String getBic() { return bic; }
    public String hostId() { return hostId; } public String hostName() { return hostName; } public String protocolVersion() { return protocolVersion; } public String bic() { return bic; }
}
