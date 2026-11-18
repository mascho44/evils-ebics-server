package de.ostms.evils.ebics.admin;
import de.ostms.evils.ebics.domain.AdminUser;
import de.ostms.evils.ebics.security.EvilsUserDetails;
import de.ostms.evils.ebics.service.AdminUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Every AdminUser belongs to exactly one tenant (hostId), except SUPERADMIN (hostId == null) -
 * this endpoint always acts within the CALLING user's own scope: a tenant-scoped admin lists/
 * creates users only within their own tenant, a superadmin lists/creates SUPERADMINs. To create
 * the first scoped admin for a brand new tenant, use TenantController instead. */
@RestController @RequestMapping("/admin/api/admin-users")
public class AdminUserController {
    private final AdminUserService users;
    public AdminUserController(AdminUserService users) { this.users=users; }

    private static String hostIdOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof EvilsUserDetails user ? user.hostId() : null;
    }

    @GetMapping public List<AdminUser> all(Authentication authentication) {
        String hostId = hostIdOf(authentication);
        return hostId == null ? users.all() : users.allForTenant(hostId);
    }
    /** The calling user's own record - used by admin.html to render the correct initial 2FA state
     * (enabled/not) without every admin needing "list all admin users" visibility into it. */
    @GetMapping("/me") public AdminUser me(Authentication authentication) {
        return users.byUsername(authentication.getName());
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public AdminUser create(@RequestBody CreateAdminUserRequest request, Authentication authentication) {
        return users.create(hostIdOf(authentication), request.username(), request.displayName(), request.password(), request.role());
    }
    // The three mutating methods pass the CALLING user's hostId so AdminUserService can reject a
    // target outside the caller's own tenant (see AdminUserService.requireTargetInActingScope) -
    // without it, a tenant admin could reset any other tenant's (or a SUPERADMIN's) password by
    // just knowing the username.
    @PutMapping("/{username}/password") @ResponseStatus(HttpStatus.NO_CONTENT) public void changePassword(@PathVariable String username, @RequestBody ChangeAdminPasswordRequest request, Authentication authentication) { users.changePassword(username, request.password(), authentication.getName(), hostIdOf(authentication)); }
    @PatchMapping("/{username}/active") public AdminUser active(@PathVariable String username, @RequestParam boolean value, Authentication authentication) { return users.setActive(username, value, authentication.getName(), hostIdOf(authentication)); }
    @DeleteMapping("/{username}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String username, Authentication authentication) { users.delete(username, authentication.getName(), hostIdOf(authentication)); }

    // Self-service 2FA (see AdminUserService.beginTotpSetup/confirmTotpSetup/disableTotp) - always
    // acts on the CALLING user's own account (authentication.getName()), never an arbitrary
    // username, and is restricted to SUPERADMIN accounts for now (matches the current requirement;
    // nothing here is inherently superadmin-specific if that ever needs widening). Same
    // "must actually be superadmin, not just any tenant's ADMIN" pattern as TenantController -
    // Spring Security's URL rules can't express "hostId == null" on the principal.
    private static void requireSuperadmin(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof EvilsUserDetails user) || !user.isSuperadmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nur für Systemadministratoren (SUPERADMIN).");
        }
    }

    @PostMapping("/me/2fa/setup") public AdminUserService.TotpSetup setupTotp(Authentication authentication) {
        requireSuperadmin(authentication);
        return users.beginTotpSetup(authentication.getName());
    }

    @PostMapping("/me/2fa/enable") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enableTotp(@RequestBody ConfirmTotpRequest request, Authentication authentication) {
        requireSuperadmin(authentication);
        users.confirmTotpSetup(authentication.getName(), request.code());
    }

    @PostMapping("/me/2fa/disable") @ResponseStatus(HttpStatus.NO_CONTENT) public void disableTotp(Authentication authentication) {
        requireSuperadmin(authentication);
        users.disableTotp(authentication.getName());
    }
}
