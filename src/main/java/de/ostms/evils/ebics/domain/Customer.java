package de.ostms.evils.ebics.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity(name = "customers")
public class Customer {
    @Id private String partnerId;
    /** Which tenant (Bank.hostId) owns this customer - see de.ostms.evils.ebics.security.TenantContext. */
    private String hostId;
    private String name; private boolean active;
    protected Customer() { }
    public Customer(String hostId, String partnerId, String name, boolean active) { this.hostId=hostId; this.partnerId=partnerId; this.name=name; this.active=active; }
    public String getHostId() { return hostId; } public String getPartnerId() { return partnerId; } public String getName() { return name; } public boolean isActive() { return active; }
}
