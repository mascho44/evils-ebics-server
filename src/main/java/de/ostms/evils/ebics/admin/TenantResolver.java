package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.security.TenantContext;
import de.ostms.evils.ebics.service.BankService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/** Resolves which tenant an admin API request applies to: TenantContext (set by
 * TenantContextFilter from the logged-in AdminUser's own hostId) for a normal scoped admin/
 * operator, or an explicit X-Tenant-Host-Id header for a SUPERADMIN (TenantContext stays unset
 * for them - see TenantContextFilter). A SUPERADMIN with no header and exactly one tenant falls
 * back to that tenant silently - the common single-tenant deployment (including every instance
 * that existed before multi-tenancy, whose admins all became SUPERADMIN, see
 * BankService.backfillLegacyHostId) must keep working without every caller of the admin API
 * (e.g. the static admin page under src/main/resources/static) having to learn about tenants.
 * The header is only required once a second tenant actually exists. Used by every admin
 * controller that reads/writes tenant-owned data (Customer/Participant/Account/...). */
final class TenantResolver {
    private TenantResolver() {
    }

    static String effectiveHostId(String tenantHostIdHeader, BankService bankService) {
        String scoped = TenantContext.current();
        if (scoped != null) return scoped;
        if (tenantHostIdHeader != null && !tenantHostIdHeader.isBlank()) return tenantHostIdHeader;
        Collection<de.ostms.evils.ebics.domain.Bank> banks = bankService.banks();
        if (banks.size() == 1) return banks.iterator().next().hostId();
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "SUPERADMIN requests must specify X-Tenant-Host-Id (more than one tenant exists)");
    }
}
