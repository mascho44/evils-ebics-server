package de.ostms.evils.ebics.security;

/**
 * The current request's tenant (Bank.hostId), set by TenantContextFilter from the authenticated
 * AdminUser's own hostId. Only ever set for a scoped admin/operator login - a SUPERADMIN request
 * (hostId == null on their AdminUser) leaves this unset, and affected admin endpoints then require
 * an explicit X-Tenant-Host-Id header instead (see AdminController.effectiveHostId). The EBICS
 * protocol path (EbicsController) is unauthenticated and never touches this - it resolves hostId
 * straight from the parsed request XML.
 */
public final class TenantContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String hostId) {
        CURRENT.set(hostId);
    }

    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
