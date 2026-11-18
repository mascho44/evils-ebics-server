package de.ostms.evils.ebics.admin;

import de.ostms.evils.ebics.domain.AdminUser;
import de.ostms.evils.ebics.domain.Bank;
import de.ostms.evils.ebics.security.EvilsUserDetails;
import de.ostms.evils.ebics.service.AdminUserService;
import de.ostms.evils.ebics.service.BankService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/** SUPERADMIN-only tenant management (each tenant = one fake bank, see BankService's class
 * comment). Restricted to role ADMIN at the URL level (see SecurityConfiguration); the actual
 * "must be a superadmin, not just any tenant's ADMIN" check happens here, since Spring Security's
 * matchers can't express "hostId == null" on the principal. */
@RestController
@RequestMapping("/admin/api/tenants")
public class TenantController {

    private final BankService bankService;
    private final AdminUserService adminUsers;

    public TenantController(BankService bankService, AdminUserService adminUsers) {
        this.bankService = bankService;
        this.adminUsers = adminUsers;
    }

    private static void requireSuperadmin(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof EvilsUserDetails user) || !user.isSuperadmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur für Systemadministratoren (SUPERADMIN).");
        }
    }

    @GetMapping
    public Collection<Bank> all(Authentication authentication) {
        requireSuperadmin(authentication);
        return bankService.banks();
    }

    public record CreateTenantResponse(Bank bank, AdminUser initialAdmin) { }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTenantResponse create(@RequestBody CreateTenantRequest request, Authentication authentication) {
        requireSuperadmin(authentication);
        Bank bank = bankService.createBank(request.hostId(), request.hostName(), request.protocolVersion(), request.bic());
        AdminUser initialAdmin = null;
        if (request.initialAdminUsername() != null && !request.initialAdminUsername().isBlank()) {
            initialAdmin = adminUsers.create(bank.hostId(), request.initialAdminUsername(),
                    request.initialAdminDisplayName(), request.initialAdminPassword(), "ADMIN");
        }
        return new CreateTenantResponse(bank, initialAdmin);
    }

    @DeleteMapping("/{hostId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String hostId, Authentication authentication) {
        requireSuperadmin(authentication);
        bankService.deleteBank(hostId);
    }
}
