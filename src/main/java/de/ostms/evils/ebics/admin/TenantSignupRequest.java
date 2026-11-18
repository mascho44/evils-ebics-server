package de.ostms.evils.ebics.admin;

public record TenantSignupRequest(String hostId, String hostName, String protocolVersion, String bic,
                                   String adminUsername, String adminDisplayName, String adminPassword,
                                   String requesterEmail) { }
