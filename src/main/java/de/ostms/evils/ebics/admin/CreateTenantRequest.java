package de.ostms.evils.ebics.admin;

/** initialAdminUsername/Password/DisplayName are optional - when given, the new tenant's first
 * scoped AdminUser is created in the same call, so a SUPERADMIN can hand a ready-to-use tenant
 * straight to whoever asked for it. */
public record CreateTenantRequest(String hostId, String hostName, String protocolVersion, String bic,
                                   String initialAdminUsername, String initialAdminPassword, String initialAdminDisplayName) { }
